package com.wraithhawit.reciperoulette;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;

/**
 * Applies the recipe scramble's swap to villager and wandering trader trades (config
 * {@code scrambleTrades}). The swap is the one the last recipe load built, so trades and recipes
 * always agree; with the scramble disabled there is none and trades stay as they are.
 */
public final class TradeScramble {
    private record State(Map<String, String> mapping, Map<String, OutputWalker.Stack> variants) {
    }

    private static volatile State state;

    private TradeScramble() {
    }

    /** Called by the engine after each recipe load; null clears it (scramble off). */
    static void publish(Map<String, String> mapping, Map<String, OutputWalker.Stack> variants) {
        state = mapping == null ? null : new State(Map.copyOf(mapping), Map.copyOf(variants));
    }

    public static boolean active() {
        return state != null && RouletteConfig.SPEC.isLoaded() && RouletteConfig.SCRAMBLE_TRADES.get();
    }

    /**
     * The trade result {@code stack} swaps to, or {@code stack} itself when it has no swap (the
     * item never comes out of a recipe, or is excluded). A stack whose exact variant (item plus
     * components, e.g. an enchanted book) is not in the swap goes by its item alone.
     */
    public static ItemStack map(ItemStack stack, HolderLookup.Provider registries) {
        State s = state;
        if (s == null || stack.isEmpty()) {
            return stack;
        }
        RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        JsonObject components = null;
        DataComponentPatch patch = stack.getComponentsPatch();
        if (!patch.isEmpty()) {
            components = DataComponentPatch.CODEC.encodeStart(ops, patch).result()
                    .filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElse(null);
        }
        String to = s.mapping().get(OutputWalker.variantKey(id, components));
        if (to == null && components != null) {
            to = s.mapping().get(id);
        }
        if (to == null) {
            return stack;
        }
        OutputWalker.Stack target = s.variants().get(to);
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(target.id()));
        if (item == Items.AIR) {
            return stack;
        }
        ItemStack out = new ItemStack(item);
        JsonObject targetComponents = target.components() != null ? target.components()
                : ItemDefaults.fallback(target.id());
        if (targetComponents != null) {
            DataComponentPatch.CODEC.parse(ops, targetComponents).result().ifPresent(out::applyComponents);
        }
        out.setCount(Math.max(1, Math.min(stack.getCount(), out.getMaxStackSize())));
        return out;
    }
}
