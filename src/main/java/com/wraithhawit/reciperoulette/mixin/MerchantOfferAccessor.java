package com.wraithhawit.reciperoulette.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sets a new offer's result in place, so every other field (uses, xp, price) stays as made. */
@Mixin(MerchantOffer.class)
public interface MerchantOfferAccessor {
    @Mutable
    @Accessor("result")
    void reciperoulette$setResult(ItemStack result);
}
