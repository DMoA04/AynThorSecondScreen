package com.exojosh.client;

import com.exojosh.client.mixin.ItemStackRenderStateAccessor;
import com.exojosh.client.mixin.NativeImageInvoker;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;

import java.io.ByteArrayOutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Base64;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Renders an item/block into a private offscreen GPU texture using the real
 * vanilla model pipeline, then reads it back as PNG bytes.
 *
 * <h2>Why this exists</h2>
 * The old approach (ItemIconResolver) just read textures/item/&lt;name&gt;.png
 * off the resource manager. That only works for items whose icon happens to
 * BE a single texture file named after the item. It structurally cannot work
 * for:
 *   - blocks with no matching texture file at all (jungle_stairs has no
 *     textures/block/jungle_stairs.png -- its model references jungle_planks)
 *   - blocks whose texture name differs from the item name
 *     (stripped_birch_wood renders from stripped_birch_log)
 *   - blocks with different textures per face (spruce logs: log top vs. side)
 *   - anything composited, tinted, or 3D-modelled
 * Those are exactly the items that were coming back blank. And even when it
 * did resolve, the result was a flat 16x16 face, not vanilla's isometric
 * block view.
 *
 * <h2>How it works</h2>
 * Confirmed against decompiled 26.2 (./gradlew genClientOnlySources): this is
 * the same sequence {@code GuiItemAtlas.drawToSlot} uses to fill the game's
 * own "UI items atlas", just pointed at our own texture instead. 26.2's
 * rendering rewrite replaced the old immediate-mode
 * {@code MultiBufferSource}/{@code ItemRenderer.renderItem} path with a
 * two-phase submit/dispatch pipeline, so the sequence below is the current
 * vanilla one, not the 1.21.11 one this class originally matched:
 *
 *   1. {@code ItemModelResolver.updateForTopItem(...)} with
 *      {@code ItemDisplayContext.GUI} bakes the item's real model into an
 *      {@code ItemStackRenderState} -- including vanilla's GUI display
 *      transform, which IS the isometric 30/225 rotation.
 *   2. RenderSystem.outputColorTextureOverride / outputDepthTextureOverride
 *      redirect all subsequent draws to our texture. This is a public hook
 *      (WorldRenderer and GuiRenderer both use it) -- no framebuffer mixin
 *      needed.
 *   3. {@code ItemStackRenderState.submit(...)} records the item into a
 *      {@code SubmitNodeStorage}; {@code GameRenderer.featureRenderDispatcher()
 *      .renderAllFeatures(...)} is the single call vanilla itself uses to turn
 *      a populated {@code SubmitNodeStorage} into actual draw calls, so there
 *      is no queue to hand-drain here any more.
 *   4. CommandEncoder.copyTextureToBuffer() pulls the pixels back, same
 *      pattern as vanilla's ScreenshotRecorder. That readback is fenced and
 *      therefore asynchronous, hence the callback.
 *
 * Nothing is ever drawn to the real window framebuffer, so there is no
 * on-screen flash.
 *
 * <h2>Threading / timing</h2>
 * Must be called on the render thread, from inside an active per-frame GUI
 * render pass -- confirmed against decompiled 26.2, not recollection, and the
 * opposite of what held for 1.21.11. Calling it from {@code END_CLIENT_TICK}
 * (as this mod did for 1.21.11, when the render thread simply having no open
 * render pass was the only requirement) now completes with no exception but
 * produces a fully transparent texture: {@code GameRenderer.gameRenderState()}'s
 * per-frame state, which {@code GameRenderer.featureRenderDispatcher()} reads
 * from, isn't populated outside a real frame. {@code ThorHudClient} drives
 * this from a no-op {@code HudElement} registered via
 * {@code HudElementRegistry.addLast}, the nearest hook guaranteed to run every
 * frame in that context, rather than from its tick handler. The completion
 * callback also fires on the render thread, once the GPU fence signals
 * (RenderSystem.executePendingTasks drives that each frame).
 */
public final class ItemIconRenderer {

    /**
     * Vanilla renders GUI items at 16 * guiScale. We're rendering for a
     * second screen where hotbar slots are much larger than 16px, so go
     * straight to 64 -- still a tiny PNG, but no upscaling mush on the app.
     */
    public static final int ICON_SIZE = 64;

    /** Vanilla's full-brightness packed lightmap value for GUI items. */
    private static final int FULL_BRIGHT = 15728880;

    /**
     * COPY_SRC (readback) | COPY_DST | TEXTURE_BINDING | RENDER_ATTACHMENT.
     * COPY_DST is required since 26.2: {@code CommandEncoder.clearColorAndDepthTextures}
     * now clears via a transfer-style write internally and
     * {@code verifyColorTexture}/{@code verifyDepthTexture} throw
     * {@code IllegalStateException("... must have USAGE_COPY_DST")} without
     * it -- confirmed against decompiled 26.2, not recollection. Without this
     * every icon request failed with that exception and silently fell back
     * to {@link ItemIconResolver}, which is why nothing looked broken for
     * flat/single-texture items (stick, sand) and only multi-texture blocks
     * with no matching flat file (crafting_table) came back with no icon at
     * all -- the real renderer was actually failing for every item.
     */
    private static final int COLOR_USAGE = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST
            | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

    /** Never read back or sampled, but still needs COPY_DST -- see {@link #COLOR_USAGE}. */
    private static final int DEPTH_USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT;

    /** Matches ScreenshotRecorder's readback buffer usage (MAP_READ | COPY_DST). */
    private static final int READBACK_BUFFER_USAGE = 9;

    /** Fully transparent black, matching GuiRenderer.CLEAR_COLOR. */
    private static final Vector4f TRANSPARENT = new Vector4f(0.0F);

    // Reused across icons -- these are GPU allocations, we don't want one per
    // request. Safe to reuse even with async readback in flight: GL orders
    // copyTextureToBuffer against the draws that preceded it, so a later
    // icon's draws can't corrupt an earlier icon's already-issued read.
    private static GpuTexture colorTexture;
    private static GpuTextureView colorView;
    private static GpuTexture depthTexture;
    private static GpuTextureView depthView;
    private static Projection projection;
    private static ProjectionMatrixBuffer projectionMatrixBuffer;

    private ItemIconRenderer() {
    }

    /**
     * Renders {@code stack} and hands the caller base64-encoded PNG bytes.
     *
     * @param onComplete invoked on the render thread with the base64 PNG, or
     *                   with {@code null} if the item has no renderable model
     *                   or the render/readback failed. Always invoked exactly
     *                   once, so callers can rely on it to answer a pending
     *                   request either way rather than silently dropping it.
     */
    public static void renderBase64Png(ItemStack stack, Consumer<String> onComplete) {
        Minecraft client = Minecraft.getInstance();

        ItemStackRenderState renderState = new ItemStackRenderState();
        client.getItemModelResolver().updateForTopItem(
                renderState, stack, ItemDisplayContext.GUI, client.level, client.player, 0);

        if (renderState.isEmpty()) {
            onComplete.accept(null);
            return;
        }

        try {
            ensureResources();
            drawToOffscreenTexture(renderState);
            readBackAsync(onComplete);
        } catch (RuntimeException e) {
            System.out.println("[ThorHud] Icon render failed for " + stack + ": " + e);
            e.printStackTrace();
            onComplete.accept(null);
        }
    }

    private static void ensureResources() {
        if (colorTexture != null) return;

        GpuDevice device = RenderSystem.getDevice();
        colorTexture = device.createTexture("ThorHud icon", COLOR_USAGE, GpuFormat.RGBA8_UNORM, ICON_SIZE, ICON_SIZE, 1, 1);
        colorView = device.createTextureView(colorTexture);
        depthTexture = device.createTexture("ThorHud icon depth", DEPTH_USAGE, GpuFormat.D32_FLOAT, ICON_SIZE, ICON_SIZE, 1, 1);
        depthView = device.createTextureView(depthTexture);

        // Same near/far as GuiItemAtlas's own item projection.
        projection = new Projection();
        projectionMatrixBuffer = new ProjectionMatrixBuffer("thorhud-icon");
    }

    private static void drawToOffscreenTexture(ItemStackRenderState renderState) {
        Minecraft client = Minecraft.getInstance();
        GpuDevice device = RenderSystem.getDevice();

        // Transparent background + cleared depth, otherwise we'd composite
        // onto whatever the previous icon left behind. Matches
        // GuiRenderer.CLEAR_COLOR (fully transparent black); clearColor
        // takes a Vector4fc since 26.2, not a packed int. Depth clears to
        // 0.0, not the old 1.0 -- both GuiItemAtlas and PictureInPictureRenderer
        // clear to 0.0, matching 26.2's reverse-Z depth convention (near=1,
        // far=0). Clearing to 1.0 (old convention) put every drawn fragment
        // on the wrong side of the depth test, so nothing ever passed --
        // confirmed against decompiled 26.2, not recollection.
        device.createCommandEncoder().clearColorAndDepthTextures(colorTexture, TRANSPARENT, depthTexture, 0.0);

        RenderSystem.backupProjectionMatrix();
        GpuTextureView previousColor = RenderSystem.outputColorTextureOverride;
        GpuTextureView previousDepth = RenderSystem.outputDepthTextureOverride;

        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();

        try {
            RenderSystem.outputColorTextureOverride = colorView;
            RenderSystem.outputDepthTextureOverride = depthView;
            projection.setupOrtho(-1000.0F, 1000.0F, ICON_SIZE, ICON_SIZE, true);
            RenderSystem.setProjectionMatrix(projectionMatrixBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
            modelView.identity();

            // GuiItemAtlas.drawToSlot does this around every draw into its
            // own offscreen atlas, not just as an optimisation: without an
            // explicit scissor sized to our texture, whatever scissor rect
            // (if any) is still active from the last thing the render thread
            // drew wins, and it has no reason to cover our 64x64 texture's
            // coordinate space. Draws that get scissored out don't fail --
            // they just draw nothing, which is why this silently produced a
            // fully transparent icon instead of an exception.
            RenderSystem.enableScissorForRenderTypeDraws(0, 0, ICON_SIZE, ICON_SIZE);

            // Flat-shade sprite-style items, 3D-light actual block models --
            // this is what makes a block read as isometric rather than as a
            // uniformly-lit silhouette.
            client.gameRenderer.lighting().setupFor(
                    renderState.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);

            // Centre the item and scale the model's [-0.5, 0.5] unit cube up
            // to fill the texture. Y is negated because the ortho projection
            // is Y-down. Mirrors GuiItemAtlas.drawToSlot.
            PoseStack matrices = new PoseStack();
            matrices.translate(ICON_SIZE / 2.0F, ICON_SIZE / 2.0F, 0.0F);
            matrices.scale(ICON_SIZE, -ICON_SIZE, ICON_SIZE);

            suppressGlint(renderState);

            SubmitNodeStorage queue = new SubmitNodeStorage();
            renderState.submit(matrices, queue, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);

            // renderAllFeatures is the same single call GuiItemAtlas.drawToSlot
            // uses to turn a populated SubmitNodeStorage into actual draw
            // calls -- the old per-command MultiBufferSource/ItemRenderer
            // drain doesn't exist any more in 26.2's rendering pipeline.
            client.gameRenderer.featureRenderDispatcher().renderAllFeatures(queue);
        } finally {
            RenderSystem.disableScissorForRenderTypeDraws();
            modelView.popMatrix();
            RenderSystem.outputColorTextureOverride = previousColor;
            RenderSystem.outputDepthTextureOverride = previousDepth;
            RenderSystem.restoreProjectionMatrix();
        }
    }

    /**
     * Forces every layer of {@code renderState} to render foil-free, so an
     * enchanted item renders as the plain item and the companion app draws
     * the glint over it instead.
     *
     * <h2>Why this has to happen before submit, not at draw time</h2>
     * In 1.21.11 this was done by intercepting each queued draw command and
     * overriding the foil type passed to {@code ItemRenderer.renderItem}.
     * 26.2's rendering rewrite removed that interception point: items are now
     * submitted into a {@link SubmitNodeStorage} and drained in bulk by
     * {@code GameRenderer.featureRenderDispatcher().renderAllFeatures(...)},
     * with no per-command hook left to override. The foil type is instead
     * baked into each {@code ItemStackRenderState.LayerRenderState} at submit
     * time, from a private array with no public way to iterate it -- hence
     * {@link ItemStackRenderStateAccessor}, the same accessor-mixin pattern
     * already used for {@code KeyBinding}'s counter and {@code NativeImage}'s
     * writer.
     *
     * Skipping this and letting the real glint through reproduces the actual
     * bug it was written to fix: rendered offscreen, outside vanilla's frame
     * setup, the glint's additive pass covers the item instead of shimmering
     * over it -- an enchanted golden apple came back as a flat square of glint
     * texture with no apple in it. Even composited correctly it would be the
     * wrong answer here anyway: the result is a still PNG, so vanilla's
     * scrolling shimmer would be frozen. Drawing it app-side gets the
     * animation back and masks it to the item's own alpha, which is what
     * makes a sword glint along the blade rather than lighting up the whole
     * square. The app needs only the {@code hasGlint} flag the snapshot
     * already carries, plus the glint texture, which {@link HudAssetCatalog}
     * serves.
     */
    private static void suppressGlint(ItemStackRenderState renderState) {
        ItemStackRenderStateAccessor accessor = (ItemStackRenderStateAccessor) (Object) renderState;
        ItemStackRenderState.LayerRenderState[] layers = accessor.thorhud$getLayers();
        int layerCount = accessor.thorhud$getActiveLayerCount();
        for (int i = 0; i < layerCount; i++) {
            layers[i].setFoilType(ItemStackRenderState.FoilType.NONE);
        }
    }

    private static void readBackAsync(Consumer<String> onComplete) {
        int pixelSize = colorTexture.getFormat().blockSize();
        long size = (long) ICON_SIZE * ICON_SIZE * pixelSize;

        GpuDevice device = RenderSystem.getDevice();
        GpuBuffer readback = device.createBuffer(() -> "ThorHud icon readback", READBACK_BUFFER_USAGE, size);
        CommandEncoder encoder = device.createCommandEncoder();

        encoder.copyTextureToBuffer(colorTexture, readback, 0L, () -> {
            String base64 = null;
            try (GpuBufferSlice.MappedView mapped = readback.map(true, false)) {
                base64 = encodeBase64Png(mapped, pixelSize);
            } catch (Exception e) {
                System.out.println("[ThorHud] Icon readback failed: " + e);
                e.printStackTrace();
            } finally {
                readback.close();
                onComplete.accept(base64);
            }
        }, 0);
    }

    private static String encodeBase64Png(GpuBufferSlice.MappedView mapped, int pixelSize) throws Exception {
        // NativeImage's setColor takes little-endian RGBA (ABGR when read as
        // an int), which is exactly the layout an RGBA8 texture reads back
        // as -- so the ints go straight across. Row order is flipped because
        // GL reads bottom-up. Alpha is preserved (unlike ScreenshotRecorder,
        // which forces it opaque) so the icon stays cut-out on the app side.
        try (NativeImage image = new NativeImage(ICON_SIZE, ICON_SIZE, false)) {
            for (int y = 0; y < ICON_SIZE; y++) {
                for (int x = 0; x < ICON_SIZE; x++) {
                    int abgr = mapped.data().getInt((x + y * ICON_SIZE) * pixelSize);
                    image.setPixelABGR(x, ICON_SIZE - y - 1, abgr);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (WritableByteChannel channel = Channels.newChannel(out)) {
                if (!((NativeImageInvoker) (Object) image).thorhud$write(channel)) {
                    return null;
                }
            }
            return Base64.getEncoder().encodeToString(out.toByteArray());
        }
    }
}
