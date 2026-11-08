# Changelog

## 0.3.0 (2026-11-08)

- GregTech outputs that carry item data are now scrambled too (bee combs from the Apiary, lamps,
  research data, ...). They are written as NeoForge "components" ingredients, which hold the item
  under `items` instead of `item`, so they were skipped before. On ATM10 + GT this adds about
  8,400 recipes: 186,780 of 195,717 are scrambled now (95%, was 91%). When the new item has no
  data of its own, the result becomes a plain item, so it never carries the old item's data.
- New `logs/reciperoulette/unscrambled.txt`: for every recipe type with recipes left unscrambled,
  where the item ids in those recipes sit (JSON paths and counts) and two full samples. It shows
  which output key the scramble does not know yet; send it along with report.txt.
- Checked on a server: an LV macerator runs the scrambled recipe (iron ingot came out as the
  item mapping.tsv lists).

## 0.2.1 (2026-11-07)

- Fixed a world-load crash with Immersive Engineering ("Index 0 out of bounds for length 0" in
  `ArcFurnaceRecipe.<init>`). IE works out Arc Furnace recycling from crafting recipes, and a
  scrambled recipe can make an item worth less than a nugget of metal; IE then builds a recycling
  recipe with no outputs and throws. That item's recycling recipe is now skipped and logged.
  Whether a world hit this depended on the seed.
- A swapped-in result now carries the components its own recipes give it, instead of none.
  Sophisticated Storage chests and barrels come with a wood type (oak for the tiered ones);
  before, they came out without one, which EMI and JEI list nowhere, so every chest looked
  uncraftable.

## 0.2.0 (2026-11-07)

- `/reciperoulette` command (op level 2): `seed` shows the seed, `seed <value>` sets it,
  `reroll` picks a new random one, `enable` / `disable` turn the scramble on or off. Each change
  is saved to the config and applied right away with a data pack reload.
- The scramble no longer reshuffles everything when the set of craftable items changes (a mod
  update, or a mod regenerating its recipes between launches). Each item's place in the cycle
  now comes from a hash of the seed and its own id, so one added or removed item changes one
  other item's result. This changes every existing seed's scramble once.

## 0.1.0 (2026-11-07)

- First version. Scrambles every recipe's item outputs through a seeded one-to-one swap, applied
  in `RecipeManager.apply` before GregTech builds its machine lookup.
- Config: enabled, seed (0 = random, saved), pool (OUTPUTS / ALL_ITEMS), excluded items, excluded
  recipe types, samples per type.
- Writes `logs/reciperoulette/report.txt`, `mapping.tsv` and `samples.txt` on every load.
