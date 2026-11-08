package com.wraithhawit.reciperoulette;

import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The components a scrambled recipe gives its new result item. A swapped-in item gets the
 * components its own recipes most often give it, not none: Sophisticated Storage's oak chest
 * recipe makes {@code sophisticatedstorage:chest} with {@code wood_type: oak}, and a chest with no
 * wood type is a stack EMI and JEI never list (Sophisticated Storage tells them to compare by wood
 * type, and its creative tab only holds wood variants), so every chest looked uncraftable.
 *
 * <p>Items whose own recipes carry no components get none, except for the mod fallbacks in
 * {@link #fallback}.
 */
final class ItemDefaults {
    /** Item id -> components JSON text -> how many output stacks had it. */
    private final Map<String, Map<String, Integer>> votes = new HashMap<>();
    /** Components JSON text -> the parsed object (shared between items with equal components). */
    private final Map<String, JsonObject> parsed = new HashMap<>();
    private Map<String, JsonObject> resolved;

    /** Records one output stack of {@code id}; {@code components} may be null. */
    void record(String id, JsonObject components) {
        if (components == null || components.isEmpty()) {
            return;
        }
        String text = components.toString();
        parsed.putIfAbsent(text, components.deepCopy());
        votes.computeIfAbsent(id, k -> new LinkedHashMap<>()).merge(text, 1, Integer::sum);
    }

    /** The components to give {@code id} as a recipe result, or null for none. */
    JsonObject componentsFor(String id) {
        if (resolved == null) {
            resolved = new HashMap<>();
            for (Map.Entry<String, Map<String, Integer>> e : votes.entrySet()) {
                // Most common wins; ties go to the first seen (LinkedHashMap order).
                String best = null;
                int bestCount = 0;
                for (Map.Entry<String, Integer> v : e.getValue().entrySet()) {
                    if (v.getValue() > bestCount) {
                        best = v.getKey();
                        bestCount = v.getValue();
                    }
                }
                resolved.put(e.getKey(), parsed.get(best));
            }
        }
        JsonObject own = resolved.get(id);
        return own != null ? own : fallback(id);
    }

    int itemsWithComponents() {
        return votes.size();
    }

    private static final JsonObject OAK_WOOD = new JsonObject();

    static {
        OAK_WOOD.addProperty("sophisticatedstorage:wood_type", "oak");
    }

    /**
     * Sophisticated Storage's tiered chests and barrels (iron, gold, limited barrels, ...) come from
     * upgrade recipes that copy the wood type from the input, so their JSON results have no
     * components to learn from. Give them oak, like the base chest recipe.
     */
    private static JsonObject fallback(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !"sophisticatedstorage".equals(rl.getNamespace())) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        for (Class<?> c = item.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("net.p3pp3rf1y.sophisticatedstorage.item.WoodStorageBlockItem")) {
                return OAK_WOOD;
            }
        }
        return null;
    }
}
