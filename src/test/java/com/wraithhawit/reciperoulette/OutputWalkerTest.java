package com.wraithhawit.reciperoulette;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputWalkerTest {
    private static final Set<String> ITEMS = Set.of(
            "minecraft:oak_planks", "minecraft:crafting_table", "minecraft:stone", "minecraft:cobblestone",
            "allthetweaks:atm_star", "gtceu:iron_dust", "gtceu:tiny_iron_dust", "minecraft:minecart",
            "minecraft:diamond_sword", "minecraft:ender_pearl");
    private static final Predicate<String> IS_ITEM = ITEMS::contains;
    private static final Predicate<String> IS_FLUID = Set.of("minecraft:water", "minecraft:lava")::contains;

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    @Test
    void shapedResultOnlyNotIngredients() {
        JsonElement r = json("""
                {"type":"minecraft:crafting_shaped","key":{"#":{"item":"minecraft:oak_planks"}},
                 "pattern":["##","##"],"result":{"id":"minecraft:crafting_table","count":1}}""");
        assertEquals(List.of("minecraft:crafting_table"), OutputWalker.collect(r, IS_ITEM, IS_FLUID));
    }

    @Test
    void rewriteClampsCountAndDropsComponents() {
        JsonElement r = json("""
                {"result":{"id":"minecraft:stone","count":64,"components":{"minecraft:custom_name":"x"}}}""");
        int n = OutputWalker.rewrite(r, IS_ITEM, IS_FLUID, Map.of("minecraft:stone", "minecraft:diamond_sword")::get,
                id -> id.equals("minecraft:diamond_sword") ? 1 : 64);
        assertEquals(1, n);
        var res = r.getAsJsonObject().getAsJsonObject("result");
        assertEquals("minecraft:diamond_sword", res.get("id").getAsString());
        assertEquals(1, res.get("count").getAsInt());
        assertFalse(res.has("components"));
    }

    @Test
    void rewriteGivesNewItemItsOwnComponents() {
        JsonElement r = json("""
                {"result":{"id":"minecraft:stone","count":1,"components":{"minecraft:custom_name":"x"}}}""");
        var oak = json("{\"sophisticatedstorage:wood_type\":\"oak\"}").getAsJsonObject();
        OutputWalker.rewrite(r, IS_ITEM, IS_FLUID, Map.of("minecraft:stone", "minecraft:crafting_table")::get,
                id -> 64, id -> id.equals("minecraft:crafting_table") ? oak : null);
        var res = r.getAsJsonObject().getAsJsonObject("result");
        assertEquals(oak, res.getAsJsonObject("components"));
        // A copy, so later edits to one recipe never leak into another.
        assertTrue(res.getAsJsonObject("components") != oak);
    }

    @Test
    void forEachOutputReportsComponents() {
        JsonElement r = json("""
                {"result":{"id":"minecraft:crafting_table","components":{"a":1}},
                 "outputs":["minecraft:stone"]}""");
        List<String> seen = new java.util.ArrayList<>();
        OutputWalker.forEachOutput(r, IS_ITEM, IS_FLUID, (id, c) -> seen.add(id + "=" + c));
        assertEquals(List.of("minecraft:crafting_table={\"a\":1}", "minecraft:stone=null"), seen);
    }

    @Test
    void gtStyleOutputsSkipFluidBranch() {
        JsonElement r = json("""
                {"inputs":{"item":[{"content":{"ingredient":{"item":"gtceu:iron_dust"},"count":1}}]},
                 "outputs":{"item":[{"content":{"ingredient":{"item":"gtceu:tiny_iron_dust"},"count":9},"chance":10000}],
                            "fluid":[{"content":{"id":"minecraft:water","amount":1000}}]}}""");
        assertEquals(List.of("gtceu:tiny_iron_dust"), OutputWalker.collect(r, IS_ITEM, IS_FLUID));
    }

    @Test
    void fluidStackUnderOutputIsSkipped() {
        JsonElement r = json("""
                {"output":{"id":"minecraft:water","amount":250},"results":[{"fluid":"minecraft:lava","amount":5}]}""");
        assertTrue(OutputWalker.collect(r, IS_ITEM, IS_FLUID).isEmpty());
    }

    @Test
    void modernIndustrializationAmountIsAnItemCount() {
        JsonElement r = json("""
                {"type":"modern_industrialization:assembler","item_inputs":[{"amount":1,"item":"minecraft:stone"}],
                 "fluid_inputs":{"fluid":"minecraft:lava","amount":1000},
                 "item_outputs":[{"amount":1,"item":"minecraft:crafting_table"}]}""");
        assertEquals(List.of("minecraft:crafting_table"), OutputWalker.collect(r, IS_ITEM, IS_FLUID));
    }

    @Test
    void bareStringsAndArraysUnderOutputKey() {
        JsonElement r = json("""
                {"result":"minecraft:stone","results":["minecraft:cobblestone",{"id":"minecraft:ender_pearl"}],
                 "ingredients":["minecraft:oak_planks"]}""");
        assertEquals(List.of("minecraft:stone", "minecraft:cobblestone", "minecraft:ender_pearl"),
                OutputWalker.collect(r, IS_ITEM, IS_FLUID));
    }

    @Test
    void tagsAndUnknownIdsUntouched() {
        JsonElement r = json("""
                {"result":{"tag":"c:ingots/iron","count":2},"output":{"id":"minecraft:pig"}}""");
        assertTrue(OutputWalker.collect(r, IS_ITEM, IS_FLUID).isEmpty());
    }

    @Test
    void shuffleSurvivesPoolChanges() {
        Set<String> pool = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            pool.add("mod:item_" + i);
        }
        Map<String, String> before = Shuffle.derangement(pool, 7);
        pool.remove("mod:item_123");
        pool.add("othermod:new_thing");
        Map<String, String> after = Shuffle.derangement(pool, 7);
        long moved = after.keySet().stream()
                .filter(before::containsKey)
                .filter(k -> !before.get(k).equals(after.get(k)))
                .count();
        // One removal re-links one predecessor, one insertion re-links another.
        assertTrue(moved <= 2, "moved " + moved);
        assertFalse(before.equals(Shuffle.derangement(Set.copyOf(before.keySet()), 8)), "seed has no effect");
    }

    @Test
    void gregtechComponentsIngredientOutput() {
        // GTCEu output with a NeoForge components ingredient; inputs use "items" too and must stay.
        JsonElement r = json("""
                {"inputs":{"gtceu:item":[{"content":{"ingredient":{"items":"minecraft:ender_pearl"},"count":1}}]},
                 "outputs":{"gtceu:item":[{"content":{"ingredient":{"type":"neoforge:components",
                   "items":"minecraft:stone","components":{"minecraft:rarity":"common"},"strict":true},"count":880}}]}}""");
        assertEquals(List.of("minecraft:stone"), OutputWalker.collect(r, IS_ITEM, IS_FLUID));
        OutputWalker.rewrite(r, IS_ITEM, IS_FLUID, Map.of("minecraft:stone", "minecraft:cobblestone",
                "minecraft:ender_pearl", "minecraft:minecart")::get, id -> 64);
        var ing = r.getAsJsonObject().getAsJsonObject("outputs").getAsJsonArray("gtceu:item").get(0)
                .getAsJsonObject().getAsJsonObject("content").getAsJsonObject("ingredient");
        // No components of its own: becomes a plain item ingredient, old data dropped.
        assertEquals(json("{\"item\":\"minecraft:cobblestone\"}"), ing);
        assertEquals("minecraft:ender_pearl", r.getAsJsonObject().getAsJsonObject("inputs").getAsJsonArray("gtceu:item")
                .get(0).getAsJsonObject().getAsJsonObject("content").getAsJsonObject("ingredient").get("items").getAsString());
    }

    @Test
    void componentsIngredientKeepsNewItemsComponents() {
        JsonElement r = json("""
                {"outputs":{"ingredient":{"type":"neoforge:components","items":"minecraft:stone",
                  "components":{"minecraft:custom_name":"x"},"strict":true}}}""");
        var oak = json("{\"sophisticatedstorage:wood_type\":\"oak\"}").getAsJsonObject();
        OutputWalker.rewrite(r, IS_ITEM, IS_FLUID, Map.of("minecraft:stone", "minecraft:crafting_table")::get,
                id -> 64, id -> id.equals("minecraft:crafting_table") ? oak : null);
        var ing = r.getAsJsonObject().getAsJsonObject("outputs").getAsJsonObject("ingredient");
        assertEquals("minecraft:crafting_table", ing.get("items").getAsString());
        assertEquals(oak, ing.getAsJsonObject("components"));
        assertEquals("neoforge:components", ing.get("type").getAsString());
    }

    @Test
    void itemsArrayInOutput() {
        JsonElement r = json("""
                {"result":{"items":["minecraft:stone","minecraft:cobblestone"]},"ingredient":{"items":["minecraft:stone"]}}""");
        assertEquals(List.of("minecraft:stone", "minecraft:cobblestone"), OutputWalker.collect(r, IS_ITEM, IS_FLUID));
    }

    @Test
    void itemPathsListsEveryItem() {
        JsonElement r = json("""
                {"input":{"item":"minecraft:stone"},"drops":[{"x":{"item":"minecraft:cobblestone"}},{"tag":"c:ores"}]}""");
        List<String> paths = new java.util.ArrayList<>();
        OutputWalker.itemPaths(r, IS_ITEM, paths::add);
        assertEquals(List.of("input.item", "drops[].x.item"), paths);
    }

    @Test
    void variantKeyIgnoresKeyOrder() {
        var a = json("{\"x:b\":1,\"x:a\":{\"q\":2,\"p\":3}}").getAsJsonObject();
        var b = json("{\"x:a\":{\"p\":3,\"q\":2},\"x:b\":1}").getAsJsonObject();
        assertEquals(OutputWalker.variantKey("minecraft:stone", a), OutputWalker.variantKey("minecraft:stone", b));
        assertEquals("minecraft:stone", OutputWalker.variantKey("minecraft:stone", null));
        assertEquals("minecraft:stone", OutputWalker.variantKey("minecraft:stone", new com.google.gson.JsonObject()));
    }

    @Test
    void variantsOfOneItemSwapSeparately() {
        // Two wood types of one chest item: each must get its own target (Sophisticated Storage).
        JsonElement spruce = json("""
                {"result":{"id":"minecraft:crafting_table","count":1,"components":{"w":"spruce"}}}""");
        JsonElement oak = json("""
                {"result":{"id":"minecraft:crafting_table","count":1,"components":{"w":"oak"}}}""");
        JsonElement stone = json("""
                {"result":{"id":"minecraft:stone","count":1}}""");
        var spruceC = json("{\"w\":\"spruce\"}").getAsJsonObject();
        Map<String, OutputWalker.Stack> map = Map.of(
                OutputWalker.variantKey("minecraft:crafting_table", spruceC), new OutputWalker.Stack("minecraft:stone", null),
                OutputWalker.variantKey("minecraft:crafting_table", json("{\"w\":\"oak\"}").getAsJsonObject()),
                new OutputWalker.Stack("minecraft:crafting_table", spruceC),
                "minecraft:stone", new OutputWalker.Stack("minecraft:cobblestone", null));
        java.util.function.BiFunction<String, com.google.gson.JsonObject, OutputWalker.Stack> f =
                (id, c) -> map.get(OutputWalker.variantKey(id, c));
        OutputWalker.rewrite(spruce, IS_ITEM, IS_FLUID, f, id -> 64);
        OutputWalker.rewrite(oak, IS_ITEM, IS_FLUID, f, id -> 64);
        OutputWalker.rewrite(stone, IS_ITEM, IS_FLUID, f, id -> 64);
        assertEquals(json("{\"result\":{\"id\":\"minecraft:stone\",\"count\":1}}"), spruce);
        assertEquals(json("{\"result\":{\"id\":\"minecraft:crafting_table\",\"count\":1,\"components\":{\"w\":\"spruce\"}}}"), oak);
        assertEquals(json("{\"result\":{\"id\":\"minecraft:cobblestone\",\"count\":1}}"), stone);
    }

    @Test
    void componentsIngredientDropsPatchRemovals() {
        JsonElement r = json("""
                {"outputs":{"ingredient":{"type":"neoforge:components","items":"minecraft:stone","components":{"a":1}}}}""");
        var patch = json("{\"b\":2,\"!c\":{}}").getAsJsonObject();
        OutputWalker.rewrite(r, IS_ITEM, IS_FLUID, (id, c) -> new OutputWalker.Stack("minecraft:cobblestone", patch), id -> 64);
        var ing = r.getAsJsonObject().getAsJsonObject("outputs").getAsJsonObject("ingredient");
        assertEquals(json("{\"b\":2}"), ing.get("components"));
    }

    @Test
    void shuffleIsDerangementAndDeterministic() {
        Set<String> pool = new HashSet<>(ITEMS);
        Map<String, String> a = Shuffle.derangement(pool, 42);
        Map<String, String> b = Shuffle.derangement(List.copyOf(pool), 42);
        assertEquals(a, b);
        assertEquals(pool, a.keySet());
        assertEquals(pool, new HashSet<>(a.values()));
        a.forEach((k, v) -> assertFalse(k.equals(v), k + " maps to itself"));
    }
}
