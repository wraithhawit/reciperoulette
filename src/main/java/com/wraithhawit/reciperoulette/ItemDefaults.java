package com.wraithhawit.reciperoulette;

import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Components for a swapped-in result that has none of its own. Output variants carry their own
 * components (see {@link RouletteEngine}), so this only covers stacks that no recipe JSON gives
 * components to but that the game needs them on.
 */
final class ItemDefaults {
    private ItemDefaults() {
    }

    private static final JsonObject OAK_WOOD = new JsonObject();

    static {
        OAK_WOOD.addProperty("sophisticatedstorage:wood_type", "oak");
    }

    /**
     * Sophisticated Storage's tiered chests and barrels (iron, gold, limited barrels, ...) come from
     * upgrade recipes that copy the wood type from the input, so their JSON results have no
     * components. A chest with no wood type is a stack EMI and JEI never list (Sophisticated Storage
     * tells them to compare by wood type), so give them oak, like the base chest recipe.
     *
     * @return the components, or null for none
     */
    static JsonObject fallback(String id) {
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
