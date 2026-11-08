package com.wraithhawit.reciperoulette;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import static com.wraithhawit.reciperoulette.RecipeRoulette.LOGGER;

/**
 * The scramble. Each recipe is encoded to JSON with its own codec, its output item ids are
 * swapped through the seeded mapping ({@link OutputWalker}, {@link Shuffle}), and the JSON is
 * decoded back with the same codec. Any recipe that fails to encode or decode is kept as it was
 * and counted in the report, so a mod with an odd format loses its scramble, never its recipes.
 *
 * <p>Two passes over the recipes: the first collects every output item (the OUTPUTS pool), the
 * second rewrites. JSON is re-encoded in the second pass instead of being kept, because holding
 * a JSON tree for every recipe in a GregTech pack costs gigabytes.
 */
public final class RouletteEngine {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** Full JSON samples per type in unscrambled.txt. */
    private static final int UNSEEN_SAMPLES = 2;

    private RouletteEngine() {
    }

    private static final class TypeStats {
        int total;
        int excluded;
        int encodeFailed;
        int noOutputs;
        int changed;
        int decodeFailed;
        int samples;
        /** On recipes with no outputs found: JSON path of each item id -> how many recipes had it. */
        final Map<String, Integer> unseenPaths = new HashMap<>();
        /** Up to UNSEEN_SAMPLES full JSONs of recipes with no outputs found that do hold items. */
        final List<String[]> unseenSamples = new ArrayList<>();
        /** Recipes with no outputs found that hold no item id anywhere (fluid-only, dynamic). */
        int noItemsAtAll;
        /** Error message, trimmed -> how often it happened and the first recipe it hit. */
        final Map<String, ErrorCount> errors = new LinkedHashMap<>();

        void error(ResourceLocation id, String message) {
            String key = message.length() > 200 ? message.substring(0, 200) + "..." : message;
            errors.computeIfAbsent(key, k -> new ErrorCount(id)).count++;
        }
    }

    private static final class ErrorCount {
        final ResourceLocation firstRecipe;
        int count;

        ErrorCount(ResourceLocation firstRecipe) {
            this.firstRecipe = firstRecipe;
        }
    }

    /** @return the scrambled recipe list, or null to leave the recipe manager untouched */
    public static List<RecipeHolder<?>> scramble(Collection<RecipeHolder<?>> recipes, HolderLookup.Provider registries) {
        if (!RouletteConfig.SPEC.isLoaded()) {
            LOGGER.error("[reciperoulette] config not loaded when recipes loaded; leaving {} recipes unscrambled",
                    recipes.size());
            return null;
        }
        if (!RouletteConfig.ENABLED.get()) {
            LOGGER.info("[reciperoulette] disabled in config; {} recipes left as they are", recipes.size());
            return null;
        }
        long start = System.nanoTime();
        long seed = seed();
        RouletteConfig.Pool poolMode = RouletteConfig.POOL.get();
        Set<String> excludedItems = new HashSet<>();
        for (String s : RouletteConfig.EXCLUDED_ITEMS.get()) {
            ResourceLocation rl = ResourceLocation.tryParse(s);
            if (rl != null) {
                excludedItems.add(rl.toString());
            }
        }
        Set<String> excludedTypes = new HashSet<>(RouletteConfig.EXCLUDED_RECIPE_TYPES.get());
        int sampleLimit = RouletteConfig.SAMPLES_PER_TYPE.get();

        RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        Predicate<String> isFluid = s -> {
            ResourceLocation rl = ResourceLocation.tryParse(s);
            return rl != null && BuiltInRegistries.FLUID.containsKey(rl);
        };
        Predicate<String> isItem = s -> {
            ResourceLocation rl = ResourceLocation.tryParse(s);
            return rl != null && BuiltInRegistries.ITEM.containsKey(rl)
                    && !excludedItems.contains(rl.toString())
                    && BuiltInRegistries.ITEM.get(rl) != Items.AIR;
        };

        List<RecipeHolder<?>> list = new ArrayList<>(recipes);
        Map<String, TypeStats> stats = new TreeMap<>();
        BitSet hasOutputs = new BitSet(list.size());
        // Variant key (id, or id + sorted components) -> the stack. Variants, not items, are
        // shuffled: Sophisticated Storage's spruce and oak chests are one item with different
        // wood_type components, and each needs its own place in the cycle to stay craftable.
        Map<String, OutputWalker.Stack> variants = new HashMap<>();

        // Pass 1: find every output item.
        for (int i = 0; i < list.size(); i++) {
            RecipeHolder<?> holder = list.get(i);
            String type = typeId(holder.value());
            TypeStats st = stats.computeIfAbsent(type, k -> new TypeStats());
            st.total++;
            if (excludedTypes.contains(type)) {
                st.excluded++;
                continue;
            }
            JsonElement json = encode(holder, ops, st);
            if (json == null) {
                continue;
            }
            int[] outs = {0};
            OutputWalker.forEachOutput(json, isItem, isFluid, (s, components) -> {
                String id = ResourceLocation.parse(s).toString();
                variants.computeIfAbsent(OutputWalker.variantKey(id, components),
                        k -> new OutputWalker.Stack(id, components == null ? null : components.deepCopy()));
                outs[0]++;
            });
            if (outs[0] == 0) {
                st.noOutputs++;
                Set<String> paths = new java.util.TreeSet<>();
                OutputWalker.itemPaths(json, isItem, paths::add);
                if (paths.isEmpty()) {
                    st.noItemsAtAll++;
                } else {
                    for (String p : paths) {
                        st.unseenPaths.merge(p, 1, Integer::sum);
                    }
                    if (st.unseenSamples.size() < UNSEEN_SAMPLES) {
                        st.unseenSamples.add(new String[]{holder.id().toString(), GSON.toJson(json)});
                    }
                }
                continue;
            }
            hasOutputs.set(i);
        }
        long pass1 = System.nanoTime();

        if (poolMode == RouletteConfig.Pool.ALL_ITEMS) {
            for (ResourceLocation rl : BuiltInRegistries.ITEM.keySet()) {
                if (isItem.test(rl.toString())) {
                    variants.computeIfAbsent(rl.toString(), k -> new OutputWalker.Stack(k, null));
                }
            }
        }
        Set<String> pool = variants.keySet();
        int withComponents = 0;
        for (OutputWalker.Stack v : variants.values()) {
            if (v.components() != null) {
                withComponents++;
            }
        }
        Map<String, String> mapping = Shuffle.derangement(pool, seed);

        // Pass 2: rewrite and decode.
        List<RecipeHolder<?>> out = new ArrayList<>(list.size());
        Map<String, List<String[]>> samples = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            RecipeHolder<?> holder = list.get(i);
            if (!hasOutputs.get(i)) {
                out.add(holder);
                continue;
            }
            String type = typeId(holder.value());
            TypeStats st = stats.get(type);
            JsonElement json = encode(holder, ops, st);
            if (json == null) {
                out.add(holder);
                continue;
            }
            String before = st.samples < sampleLimit ? GSON.toJson(json) : null;
            int n = OutputWalker.rewrite(json, isItem, isFluid, (s, components) -> {
                String to = mapping.get(OutputWalker.variantKey(ResourceLocation.parse(s).toString(), components));
                if (to == null) {
                    return null;
                }
                OutputWalker.Stack target = variants.get(to);
                return target.components() != null ? target
                        : new OutputWalker.Stack(target.id(), ItemDefaults.fallback(target.id()));
            }, RouletteEngine::maxStackSize);
            if (n == 0) {
                out.add(holder);
                continue;
            }
            // Some codecs validate in a constructor and throw instead of returning an error
            // (FramedBlocks' framing saw: "Block of result items must be IFramedBlocks").
            Recipe<?> recipe;
            String error;
            try {
                DataResult<Recipe<?>> decoded = Recipe.CODEC.parse(ops, json);
                recipe = decoded.result().orElse(null);
                error = recipe == null ? "decode: " + decoded.error().map(e -> e.message()).orElse("?") : null;
            } catch (RuntimeException e) {
                recipe = null;
                error = "decode threw " + e;
            }
            if (recipe == null) {
                st.decodeFailed++;
                st.error(holder.id(), error);
                out.add(holder);
                continue;
            }
            st.changed++;
            out.add(new RecipeHolder<>(holder.id(), recipe));
            if (before != null) {
                st.samples++;
                samples.computeIfAbsent(type, k -> new ArrayList<>())
                        .add(new String[]{holder.id().toString(), before, GSON.toJson(json)});
            }
        }
        long end = System.nanoTime();

        int total = 0, changed = 0, encodeFailed = 0, decodeFailed = 0, noOutputs = 0, excluded = 0;
        for (TypeStats st : stats.values()) {
            total += st.total;
            changed += st.changed;
            encodeFailed += st.encodeFailed;
            decodeFailed += st.decodeFailed;
            noOutputs += st.noOutputs;
            excluded += st.excluded;
        }
        Path dir = FMLPaths.GAMEDIR.get().resolve("logs").resolve(RecipeRoulette.MOD_ID);
        LOGGER.info("[reciperoulette] scrambled {} of {} recipes; seed {}, pool {} with {} variants of {} items; "
                        + "no outputs found {}, excluded {}, encode failed {}, decode failed {}; "
                        + "{} variants carry components; pass1 {} ms, pass2 {} ms; report in {}",
                changed, total, seed, poolMode, pool.size(),
                variants.values().stream().map(OutputWalker.Stack::id).distinct().count(),
                noOutputs, excluded, encodeFailed, decodeFailed, withComponents,
                (pass1 - start) / 1_000_000, (end - pass1) / 1_000_000, dir);
        writeReport(dir, stats, mapping, samples, seed, poolMode, pool.size(), total, changed,
                (end - start) / 1_000_000);
        return out;
    }

    private static JsonElement encode(RecipeHolder<?> holder, RegistryOps<JsonElement> ops, TypeStats st) {
        try {
            DataResult<JsonElement> r = Recipe.CODEC.encodeStart(ops, holder.value());
            JsonElement json = r.result().orElse(null);
            if (json == null) {
                st.encodeFailed++;
                st.error(holder.id(), "encode: " + r.error().map(e -> e.message()).orElse("?"));
            }
            return json;
        } catch (RuntimeException e) {
            st.encodeFailed++;
            st.error(holder.id(), "encode threw " + e);
            return null;
        }
    }

    private static String typeId(Recipe<?> recipe) {
        ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        return key != null ? key.toString() : String.valueOf(recipe.getType());
    }

    private static int maxStackSize(String id) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        return item.getDefaultMaxStackSize();
    }

    /** The configured seed; 0 means pick one now and save it, so restarts keep the scramble. */
    private static long seed() {
        long seed = RouletteConfig.SEED.get();
        if (seed == 0L) {
            Random random = new Random();
            while (seed == 0L) {
                seed = random.nextLong();
            }
            RouletteConfig.SEED.set(seed);
            RouletteConfig.SPEC.save();
            LOGGER.info("[reciperoulette] no seed configured; picked {} and saved it to the config", seed);
        }
        return seed;
    }

    private static void writeReport(Path dir, Map<String, TypeStats> stats, Map<String, String> mapping,
                                     Map<String, List<String[]>> samples, long seed, RouletteConfig.Pool poolMode,
                                     int poolSize, int total, int changed, long millis) {
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(dir.resolve("report.txt"), StandardCharsets.UTF_8)) {
                w.write("Recipe Roulette report " + LocalDateTime.now() + "\n");
                w.write("seed " + seed + ", pool " + poolMode + " (" + poolSize + " item variants), scrambled "
                        + changed + " of " + total + " recipes in " + millis + " ms\n\n");
                w.write("Per recipe type. 'none' = no output item found: either the type only makes fluids or\n"
                        + "dynamic results, or its format hides outputs under a key the walker does not know.\n\n");
                w.write(String.format("%-60s %7s %7s %7s %7s %7s %7s%n",
                        "type", "total", "changed", "none", "excl", "encFail", "decFail"));
                List<Map.Entry<String, TypeStats>> rows = new ArrayList<>(stats.entrySet());
                rows.sort(Comparator.comparingInt((Map.Entry<String, TypeStats> e) -> -e.getValue().total));
                for (Map.Entry<String, TypeStats> e : rows) {
                    TypeStats st = e.getValue();
                    w.write(String.format("%-60s %7d %7d %7d %7d %7d %7d%n", e.getKey(), st.total, st.changed,
                            st.noOutputs, st.excluded, st.encodeFailed, st.decodeFailed));
                }
                w.write("\nErrors per type, top 5 messages (count, first recipe, message).\n"
                        + "These recipes were kept as the pack made them.\n");
                for (Map.Entry<String, TypeStats> e : rows) {
                    TypeStats st = e.getValue();
                    if (st.errors.isEmpty()) {
                        continue;
                    }
                    w.write(e.getKey() + "\n");
                    List<Map.Entry<String, ErrorCount>> errs = new ArrayList<>(st.errors.entrySet());
                    errs.sort(Comparator.comparingInt((Map.Entry<String, ErrorCount> x) -> -x.getValue().count));
                    for (Map.Entry<String, ErrorCount> x : errs.subList(0, Math.min(5, errs.size()))) {
                        w.write(String.format("  %6d  %s  %s%n", x.getValue().count, x.getValue().firstRecipe, x.getKey()));
                    }
                }
            }
            try (Writer w = Files.newBufferedWriter(dir.resolve("mapping.tsv"), StandardCharsets.UTF_8)) {
                w.write("# seed " + seed + ": a recipe that made <from> now makes <to>\n"
                        + "# a variant is an item id, plus its components when it has any\nfrom\tto\n");
                for (Map.Entry<String, String> e : new TreeMap<>(mapping).entrySet()) {
                    w.write(e.getKey() + "\t" + e.getValue() + "\n");
                }
            }
            try (Writer w = Files.newBufferedWriter(dir.resolve("unscrambled.txt"), StandardCharsets.UTF_8)) {
                w.write("Recipes where no output item was found, per type (most first).\n"
                        + "'no items' = the recipe holds no item id at all (fluid-only or dynamic result).\n"
                        + "Paths = where item ids sit in the rest, and in how many recipes. A path that is\n"
                        + "really an output means the walker misses that key.\n\n");
                List<Map.Entry<String, TypeStats>> rows = new ArrayList<>(stats.entrySet());
                rows.removeIf(e -> e.getValue().noOutputs == 0);
                rows.sort(Comparator.comparingInt((Map.Entry<String, TypeStats> e) -> -e.getValue().noOutputs));
                for (Map.Entry<String, TypeStats> e : rows) {
                    TypeStats st = e.getValue();
                    w.write(String.format("%s  none %d of %d, no items %d%n", e.getKey(), st.noOutputs, st.total,
                            st.noItemsAtAll));
                    List<Map.Entry<String, Integer>> paths = new ArrayList<>(st.unseenPaths.entrySet());
                    paths.sort(Comparator.comparingInt((Map.Entry<String, Integer> x) -> -x.getValue()));
                    for (Map.Entry<String, Integer> p : paths.subList(0, Math.min(12, paths.size()))) {
                        w.write(String.format("  %7d  %s%n", p.getValue(), p.getKey()));
                    }
                }
                w.write("\n\nSamples\n\n");
                for (Map.Entry<String, TypeStats> e : rows) {
                    for (String[] s : e.getValue().unseenSamples) {
                        String body = s[1].length() > 6000 ? s[1].substring(0, 6000) + "\n..." : s[1];
                        w.write("===== " + e.getKey() + "  " + s[0] + "\n" + body + "\n\n");
                    }
                }
            }
            try (Writer w = Files.newBufferedWriter(dir.resolve("samples.txt"), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, List<String[]>> e : samples.entrySet()) {
                    for (String[] s : e.getValue()) {
                        w.write("===== " + e.getKey() + "  " + s[0] + "\n--- before\n" + s[1] + "\n--- after\n" + s[2]
                                + "\n\n");
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[reciperoulette] could not write report to {}", dir, e);
        }
    }
}
