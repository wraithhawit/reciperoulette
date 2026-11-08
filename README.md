# Recipe Roulette

A NeoForge 1.21.1 mod that scrambles the output of every recipe in the pack: crafting, smelting,
and modded machines (GregTech, Mekanism, Create, Modern Industrialization, ...). Four planks still
take the crafting-table recipe, but the result is something else entirely.

- **One item, one swap.** Each item is swapped for exactly one other item, everywhere. If crafting
  tables become cyan sofas, every recipe that made a crafting table now makes a cyan sofa.
  Variants of one item that differ only by data, like Sophisticated Storage chests in each
  wood type, count as separate items, so each one keeps a recipe.
- **Seeded.** The seed is in `config/reciperoulette-common.toml`. The same pack and the same seed
  always give the same scramble. Seed `0` picks a random one on the next load and saves it.
- **Any pack.** It needs no per-pack setup. Recipes are read after every mod and KubeJS script has
  added theirs, and before GregTech builds its machine recipe cache.
- **Safe failure.** Recipes that can't be scrambled are kept as the pack made them, and the report
  counts them.

## Commands (op level 2)

| command | does |
| --- | --- |
| `/reciperoulette seed` | show the current seed (click to copy) |
| `/reciperoulette seed <value>` | use that seed and re-scramble now |
| `/reciperoulette reroll` | pick a new random seed and re-scramble now |
| `/reciperoulette enable` / `disable` | turn the scramble on or off |

Each one saves to the config first, so the result survives restarts, then reloads data packs
like `/reload` does. On a big pack that reload takes a minute or more.

The scramble stays put between launches. Mod updates or mods that regenerate recipes only
move the few items whose neighbours in the cycle changed, not the whole pack.

## Config (`config/reciperoulette-common.toml`, applied on `/reload`)

| key | default | meaning |
| --- | --- | --- |
| `enabled` | `true` | `false` leaves every recipe as the pack made it |
| `seed` | `0` | the scramble seed; `0` = pick one and save it |
| `pool` | `OUTPUTS` | `OUTPUTS`: items only swap with other craftable items, so everything craftable stays craftable. `ALL_ITEMS`: any item can come out of a recipe, creative items included |
| `excludedItems` | `[]` | item ids that never move and never become a result |
| `excludedRecipeTypes` | `[]` | recipe types left untouched, e.g. `"minecraft:stonecutting"` |
| `samplesPerType` | `1` | before/after JSON samples per recipe type written to `samples.txt` |

## Output files (`logs/reciperoulette/`, rewritten on every load)

- `report.txt`: per recipe type, how many recipes were scrambled, had no output item, or failed,
  with the most common error messages.
- `mapping.tsv`: the full swap table (`from`, `to`). Spoilers.
- `samples.txt`: before/after JSON of one recipe per type.

## How it works

`RecipeManagerMixin` hooks `RecipeManager.apply` at its "Loaded N recipes" log line, after the
recipes are parsed and before any TAIL hooks (GregTech builds its lookup and copies furnace
recipes into its machines at TAIL). `RouletteEngine` encodes each recipe to JSON with its own
codec, `OutputWalker` rewrites the item ids under output-looking keys (`result`, `output`,
`product`), and the JSON is decoded back with the same codec. Two passes: the first collects the
output pool, the second rewrites.

A swapped-in result gets the components the item's own recipes most often give it (a
Sophisticated Storage chest keeps a wood type), so it is the same stack EMI and JEI list.

Compat: Immersive Engineering's Arc Furnace recycling skips items whose scrambled recipe is worth
less than a nugget, instead of crashing the world on load.

Limits: recipes whose result is computed in code (fireworks, tool repair, Botany Pots crops that
drop their block's loot) are not touched. Fluid outputs are not scrambled.

## Build

`./gradlew build` builds the jar and runs the unit tests. The jar lands in `build/libs/`.
