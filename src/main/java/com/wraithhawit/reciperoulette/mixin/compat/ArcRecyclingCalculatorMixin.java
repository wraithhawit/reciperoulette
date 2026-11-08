package com.wraithhawit.reciperoulette.mixin.compat;

import blusunrize.immersiveengineering.api.crafting.ArcRecyclingRecipe;
import blusunrize.immersiveengineering.api.crafting.IngredientWithSize;
import blusunrize.immersiveengineering.api.crafting.TagOutput;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static com.wraithhawit.reciperoulette.RecipeRoulette.LOGGER;

/**
 * Immersive Engineering works out Arc Furnace recycling from crafting recipes when the server
 * starts. When an item's recipe gives less than a nugget of metal per item (scrambled recipes do:
 * "one ingot makes 16 of these"), IE builds a recycling recipe with no outputs and its constructor
 * throws {@code IndexOutOfBoundsException}, which crashes the server on world load. This skips that
 * item's recycling recipe instead.
 *
 * <p>Seen in IE 12.4.2-194 ({@code ArcFurnaceRecipe.<init>} line 55, from
 * {@code ArcRecyclingCalculator.makeRecipe} line 143). Optional: without IE nothing is applied.
 */
@Pseudo
@Mixin(targets = "blusunrize.immersiveengineering.common.crafting.ArcRecyclingCalculator", remap = false)
public abstract class ArcRecyclingCalculatorMixin {
    @WrapOperation(method = "makeRecipe", remap = false,
            at = @At(value = "NEW", target = "blusunrize/immersiveengineering/api/crafting/ArcRecyclingRecipe", remap = false))
    private ArcRecyclingRecipe reciperoulette$skipEmpty(Supplier<RegistryAccess> tags,
                                                       List<Pair<TagOutput, Double>> outputs, IngredientWithSize input,
                                                       int time, int energy, Operation<ArcRecyclingRecipe> original) {
        try {
            return original.call(tags, outputs, input, time, energy);
        } catch (IndexOutOfBoundsException e) {
            String item;
            try {
                ItemStack[] stacks = input.getMatchingStacks();
                item = stacks.length > 0 ? stacks[0].toString() : Arrays.toString(stacks);
            } catch (RuntimeException ignored) {
                item = "?";
            }
            LOGGER.info("[reciperoulette] skipped an Immersive Engineering arc recycling recipe with no outputs "
                    + "(input {}, outputs {})", item, outputs);
            return null;
        }
    }

    @WrapOperation(method = "run", remap = false,
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", remap = false))
    private boolean reciperoulette$dropSkipped(List<Object> list, Object recipe, Operation<Boolean> original) {
        return recipe != null && original.call(list, recipe);
    }
}
