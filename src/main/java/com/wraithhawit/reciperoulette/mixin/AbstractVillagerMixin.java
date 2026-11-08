package com.wraithhawit.reciperoulette.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.wraithhawit.reciperoulette.TradeScramble;

import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Swaps the result of each new trade (config {@code scrambleTrades}). Villagers and wandering
 * traders both build their offers in {@code addOffersFromItemListings}, from vanilla's trade lists
 * and the ones mods add through NeoForge's trade events, so one hook covers all of them. Only new
 * offers pass here; saved ones load from NBT unchanged.
 */
@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerMixin {
    @ModifyExpressionValue(method = "addOffersFromItemListings", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/npc/VillagerTrades$ItemListing;getOffer(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/item/trading/MerchantOffer;"))
    private MerchantOffer reciperoulette$scrambleOffer(MerchantOffer offer) {
        if (offer == null || !TradeScramble.active()) {
            return offer;
        }
        AbstractVillager self = (AbstractVillager) (Object) this;
        ItemStack mapped = TradeScramble.map(offer.getResult(), self.registryAccess());
        if (mapped != offer.getResult()) {
            ((MerchantOfferAccessor) offer).reciperoulette$setResult(mapped);
        }
        return offer;
    }
}
