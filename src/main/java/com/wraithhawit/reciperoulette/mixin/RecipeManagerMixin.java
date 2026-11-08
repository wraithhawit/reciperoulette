package com.wraithhawit.reciperoulette.mixin;

import com.google.gson.JsonElement;
import com.wraithhawit.reciperoulette.RouletteEngine;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Runs the scramble inside {@code RecipeManager.apply}, right after vanilla has stored the parsed
 * recipes and just before its "Loaded {} recipes" log line. The point is chosen against what
 * other mods hook here:
 *
 * <ul>
 *   <li>KubeJS edits the recipe JSON at HEAD, so its recipes are already parsed here.</li>
 *   <li>GregTech, at TAIL, copies vanilla furnace-style recipes into its machines and builds its
 *       machine lookup from the stored recipes. Running before TAIL means GT builds from the
 *       scrambled recipes; running after it would leave GT machines on the old outputs.</li>
 * </ul>
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @Shadow
    @Final
    private HolderLookup.Provider registries;

    @Shadow
    public abstract Collection<RecipeHolder<?>> getRecipes();

    @Shadow
    public abstract void replaceRecipes(Iterable<RecipeHolder<?>> recipes);

    @Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;)V", remap = false))
    private void reciperoulette$scramble(Map<ResourceLocation, JsonElement> json, ResourceManager resourceManager,
                                         ProfilerFiller profiler, CallbackInfo ci) {
        List<RecipeHolder<?>> scrambled = RouletteEngine.scramble(getRecipes(), registries);
        if (scrambled != null) {
            replaceRecipes(scrambled);
        }
    }
}
