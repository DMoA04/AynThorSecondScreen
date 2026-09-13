package com.exojosh.client.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code ItemStackRenderState}'s private layer array so
 * {@code ItemIconRenderer} can force every layer's foil type off before
 * submitting, since 26.2 dropped the per-draw-command interception point
 * that used to do this (see {@code ItemIconRenderer.suppressGlint}).
 *
 * {@code LayerRenderState.setFoilType} is already public; only the array
 * that holds the layers, and the count of them actually in use, are private.
 */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {

    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] thorhud$getLayers();

    @Accessor("activeLayerCount")
    int thorhud$getActiveLayerCount();
}
