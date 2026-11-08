package com.wraithhawit.reciperoulette;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Finds and rewrites the output items of a recipe that has been encoded to JSON with its own
 * codec. Recipe formats differ per mod, so outputs are found by key name instead of per-type
 * code:
 *
 * <ul>
 *   <li>A subtree is an output when its key contains {@code result}, {@code output} or
 *       {@code product} ({@code result}, {@code results}, {@code outputs}, {@code main_output},
 *       {@code byproducts}, ...). Everything under it is output.</li>
 *   <li>Inside an output, an item is a string under {@code id}, {@code item} or {@code items}
 *       (string or array: NeoForge ingredients, GregTech outputs), or a string sitting directly
 *       under the output key ({@code "result": "minecraft:stone"}), or directly in an array under
 *       the output key.</li>
 *   <li>Subtrees whose key names a fluid, chemical or gas are skipped, and so is a FluidStack-shaped
 *       object: {@code amount}, no {@code count}, and an {@code id}/{@code fluid} that
 *       {@code isFluid} accepts. The registry check matters: Modern Industrialization writes item
 *       counts as {@code amount} too ({@code {"item": "...", "amount": 1}}).</li>
 *   <li>A string only counts when {@code isItem} says it is a registered item. Tags and
 *       non-item ids pass through untouched.</li>
 * </ul>
 *
 * Getting a key wrong is safe: the caller decodes the rewritten JSON with the recipe's own codec
 * and keeps the original recipe when that fails.
 */
public final class OutputWalker {
    private OutputWalker() {
    }

    public static boolean isOutputKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("result") || k.contains("output") || k.contains("product");
    }

    static boolean isSkippedKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("fluid") || k.contains("chemical") || k.contains("gas")
                || k.contains("slurry") || k.contains("pigment") || k.contains("infuse");
    }

    private static boolean isFluidStack(JsonObject o, Predicate<String> isFluid) {
        if (!o.has("amount") || o.has("count")) {
            return false;
        }
        for (String k : new String[]{"id", "fluid"}) {
            JsonElement v = o.get(k);
            if (v instanceof JsonPrimitive p && p.isString() && isFluid.test(p.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /** Every output item id in {@code root}, in order of appearance, duplicates included. */
    public static List<String> collect(JsonElement root, Predicate<String> isItem, Predicate<String> isFluid) {
        List<String> found = new ArrayList<>();
        forEachOutput(root, isItem, isFluid, (id, components) -> found.add(id));
        return found;
    }

    /**
     * Calls {@code action} for every output item id with the {@code components} of its
     * ItemStack-shaped object, or null when it has none (or is not an {@code id} stack).
     */
    public static void forEachOutput(JsonElement root, Predicate<String> isItem, Predicate<String> isFluid,
                                     BiConsumer<String, JsonObject> action) {
        walk(root, false, false, isItem, isFluid, (parent, key, value) -> {
            action.accept(value, componentsOf(parent, key));
            return null;
        });
    }

    /**
     * A stable text key for an output stack: the id alone when it has no components, else the id
     * plus its components with object keys sorted, so equal stacks from different codecs match.
     */
    public static String variantKey(String id, JsonObject components) {
        if (components == null || components.isEmpty()) {
            return id;
        }
        return id + sorted(components);
    }

    private static JsonElement sorted(JsonElement e) {
        if (e instanceof JsonObject o) {
            JsonObject out = new JsonObject();
            new TreeMap<>(o.asMap()).forEach((k, v) -> out.add(k, sorted(v)));
            return out;
        }
        if (e instanceof JsonArray a) {
            JsonArray out = new JsonArray();
            a.forEach(x -> out.add(sorted(x)));
            return out;
        }
        return e;
    }

    /**
     * A component patch as a component predicate: drops {@code "!id"} removal entries, which a
     * patch may hold but a components ingredient cannot.
     */
    private static JsonObject withoutRemovals(JsonObject patch) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : patch.entrySet()) {
            if (!e.getKey().startsWith("!")) {
                out.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        return out;
    }

    /** An output stack's identity: item id plus its components (null when it has none). */
    public record Stack(String id, JsonObject components) {
        public Stack {
            if (components != null && components.isEmpty()) {
                components = null;
            }
        }
    }

    /** The components of the stack this output id sits in, or null. */
    private static JsonObject componentsOf(JsonObject parent, String key) {
        if (parent != null && ("id".equals(key) || "items".equals(key))
                && parent.get("components") instanceof JsonObject c && !c.isEmpty()) {
            return c;
        }
        return null;
    }

    /** {@link #rewrite(JsonElement, Predicate, Predicate, BiFunction, ToIntFunction)} by id only, no components. */
    public static int rewrite(JsonElement root, Predicate<String> isItem, Predicate<String> isFluid,
                              Function<String, String> mapping, ToIntFunction<String> maxStackSize) {
        return rewrite(root, isItem, isFluid, mapping, maxStackSize, id -> null);
    }

    /** {@link #rewrite(JsonElement, Predicate, Predicate, BiFunction, ToIntFunction)} by id, components by new id. */
    public static int rewrite(JsonElement root, Predicate<String> isItem, Predicate<String> isFluid,
                              Function<String, String> mapping, ToIntFunction<String> maxStackSize,
                              Function<String, JsonObject> componentsFor) {
        return rewrite(root, isItem, isFluid, (id, components) -> {
            String to = mapping.apply(id);
            return to == null ? null : new Stack(to, componentsFor.apply(to));
        }, maxStackSize);
    }

    /**
     * Replaces output stacks in place. {@code mapping} gets each output's id and components (null
     * when none) and gives the new stack, or null to leave it. Where the output can carry
     * components (an {@code id} ItemStack, or a components ingredient), they are replaced by the
     * new stack's (a copy; dropped when it has none), since the old ones belonged to the old item.
     * An {@code id} stack's {@code count} is clamped to the new item's max stack size, since strict
     * ItemStack codecs reject more. Elsewhere (plain {@code item} ingredients, bare strings) only
     * the id changes.
     *
     * @return how many outputs were replaced
     */
    public static int rewrite(JsonElement root, Predicate<String> isItem, Predicate<String> isFluid,
                              BiFunction<String, JsonObject, Stack> mapping, ToIntFunction<String> maxStackSize) {
        int[] changed = {0};
        walk(root, false, false, isItem, isFluid, (parent, key, value) -> {
            JsonObject own = componentsOf(parent, key);
            Stack target = mapping.apply(value, own);
            if (target == null || (target.id().equals(value) && Objects.equals(own, target.components()))) {
                return null;
            }
            String to = target.id();
            JsonObject components = target.components();
            changed[0]++;
            if (parent != null && "items".equals(key) && parent.has("components")) {
                // NeoForge components ingredient ({"type": "neoforge:components", "items": id,
                // "components": {...}}), used by GregTech for outputs that carry data. The old
                // components belong to the old item: use the new stack's, or turn the
                // ingredient into a plain item ingredient when it has none.
                JsonObject predicate = components == null ? null : withoutRemovals(components);
                if (predicate != null && !predicate.isEmpty()) {
                    parent.add("components", predicate);
                } else {
                    parent.remove("components");
                    parent.remove("strict");
                    parent.remove("items");
                    // neoforge:components, or a mod's own variant (productivebees:component):
                    // either way the plain item ingredient has no type.
                    parent.remove("type");
                    parent.addProperty("item", to);
                }
            } else if (parent != null && "id".equals(key)) {
                parent.remove("components");
                if (components != null) {
                    parent.add("components", components.deepCopy());
                }
                JsonElement count = parent.get("count");
                if (count != null && count.isJsonPrimitive() && count.getAsJsonPrimitive().isNumber()) {
                    int max = Math.max(1, maxStackSize.applyAsInt(to));
                    if (count.getAsInt() > max) {
                        parent.addProperty("count", max);
                    }
                }
            }
            return to;
        });
        return changed[0];
    }

    /**
     * Every JSON path in {@code root} that holds a registered item id, with array indexes written
     * as {@code []} ({@code outputs.item[].content.ingredient.item}). For the report: on a recipe
     * where no output was found, these show where its items sit, i.e. which key the walker missed.
     */
    public static void itemPaths(JsonElement root, Predicate<String> isItem, Consumer<String> sink) {
        itemPaths(root, "", isItem, sink);
    }

    private static void itemPaths(JsonElement e, String path, Predicate<String> isItem, Consumer<String> sink) {
        if (e instanceof JsonObject o) {
            for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
                itemPaths(entry.getValue(), path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), isItem, sink);
            }
        } else if (e instanceof JsonArray a) {
            for (JsonElement el : a) {
                itemPaths(el, path + "[]", isItem, sink);
            }
        } else if (isItemString(e, isItem)) {
            sink.accept(path);
        }
    }

    /** Called for each output item string; returns a replacement or null to keep it. */
    private interface Visitor {
        String visit(JsonObject parent, String key, String value);
    }

    private static boolean isItemString(JsonElement e, Predicate<String> isItem) {
        return e instanceof JsonPrimitive p && p.isString() && isItem.test(p.getAsString());
    }

    /**
     * @param inOutput     somewhere under an output key
     * @param directOutput this element is the value of an output key itself (or an element of
     *                     an array that is), so a bare string here is an item
     */
    private static void walk(JsonElement e, boolean inOutput, boolean directOutput, Predicate<String> isItem,
                             Predicate<String> isFluid, Visitor visitor) {
        if (e instanceof JsonObject o) {
            boolean fluid = inOutput && isFluidStack(o, isFluid);
            for (Map.Entry<String, JsonElement> entry : List.copyOf(o.entrySet())) {
                String key = entry.getKey();
                JsonElement v = entry.getValue();
                if (isSkippedKey(key)) {
                    continue;
                }
                boolean outKey = isOutputKey(key);
                boolean out = inOutput || outKey;
                boolean items = "items".equals(key);
                if (out && !fluid && ("id".equals(key) || "item".equals(key) || items || outKey)
                        && isItemString(v, isItem)) {
                    String to = visitor.visit(o, key, v.getAsString());
                    // The visitor may have rebuilt the object (components ingredient -> plain item).
                    if (to != null && o.has(key)) {
                        o.addProperty(key, to);
                    }
                } else {
                    // An "items" list in an output (NeoForge ingredient HolderSet) holds bare ids.
                    walk(v, out, outKey || (out && !fluid && items), isItem, isFluid, visitor);
                }
            }
        } else if (e instanceof JsonArray a) {
            for (int i = 0; i < a.size(); i++) {
                JsonElement el = a.get(i);
                if (directOutput && isItemString(el, isItem)) {
                    String to = visitor.visit(null, null, el.getAsString());
                    if (to != null) {
                        a.set(i, new JsonPrimitive(to));
                    }
                } else {
                    walk(el, inOutput, directOutput, isItem, isFluid, visitor);
                }
            }
        }
    }
}
