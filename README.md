# layered-slopes-flowing-fluids

Flowing fluid passing over or beside a layered/layerable block has a chance to erode it by a layer.

NeoForge 1.21.1, Java 21. Mod ID: `layeredslopesflowingfluids`. MIT licensed.

Extracted from Dynamic Terrain's water erosion (MINECRAFT-513). Each second, a
bounded number of random positions around each player are sampled; an
eligible block (a Layered Slopes layered block, or a full vanilla block with
a registered layered variant) with *flowing* (non-source) fluid in the
`minecraft:water` tag over it or beside it has a chance to lose one layer,
scaled by hardness/blast-resistance and how full the flowing cell is. A full
block's first hit converts it to a 15-layer layered block; a layered block's
last layer erodes to air. Erosion disturbs grass into dirt. Still/source
fluid contact, and fluids outside the water tag (lava/heat erosion stays
Dynamic Terrain's), are no-ops. A fake-player `BlockEvent.BreakEvent` is
posted before any change so claim/protection mods that listen on it can
veto it.

Required dependency: [Layered Slopes](https://github.com/brooswit-minecraft/layered-slopes)
only (pinned by version + sha512 in `gradle.properties`), the same pattern
Dynamic Terrain itself uses for that dependency. No Dynamic Terrain runtime
or build dependency. This mod owns its own vanilla-block-to-layered-block
mapping (`LayeredMaterials`) independently of Dynamic Terrain or the
sibling [Layered Slope Grading](https://github.com/brooswit-minecraft/layered-slope-grading)
extraction, so it works whether or not either is installed.

Server config (`erosionEnabled`, `samplesPerSecond`, `chanceScale`) defaults erosion to
**off**, so existing worlds are unaffected until explicitly enabled. `samplesPerSecond`
configures cadence (how often positions are sampled); `chanceScale` (0.0-1.0, default 1.0)
independently configures the erosion-probability curve itself, down to fully off.

## Vanilla minecraft, or Flowing Fluids if installed

The goal (per the ticket) is to cover plain vanilla water, and also work unchanged if the
[Flowing Fluids](https://modrinth.com/mod/flowing-fluids) mod (Traben-0/flowing_fluids) is
additionally installed -- Flowing Fluids stays entirely **optional**: this mod has no
dependency on it in `neoforge.mods.toml` and no compile-time reference to any of its classes.
That's possible because Flowing Fluids mixes directly into vanilla `Fluids.WATER`/`Fluids.LAVA`
(the same `FlowingFluid` subclasses this mod already reads through plain
`FluidState.isSource()`/`getAmount()`) rather than registering new fluid types -- confirmed by
decompiling its pinned jar: its own `FFFluidUtils.getStateForFluidByAmount` builds every state
through the exact same public vanilla `FlowingFluid.getSource(boolean)` /
`getFlowing(int, boolean)` methods this mod could call directly. A "full" contact is therefore
always a source state and a "partial" contact is always a flowing state, installed or not --
there is no Flowing-Fluids-specific state representation to special-case.

**Remaining limit, not silently assumed away**: Flowing Fluids replaces fluid tick behaviour so
a partial-amount cell can settle into a stable, non-advancing finite pool rather than always
re-evaluating every tick the way vanilla does. This mod reads a `FluidState`'s
`isSource()`/`getAmount()` at the instant it samples a position regardless of which mod produced
that state, and Flowing Fluids changes neither query's meaning -- so this mod's erosion decision
is unaffected by that simulation difference, but it also means this mod cannot distinguish "a
cell still actively advancing" from "a cell Flowing Fluids has let settle" -- both read as an
ordinary flowing state and both erode under the same rules a vanilla flowing edge would.

Exact version tested coinstalled: Flowing Fluids `1.0.8-1.21-neoforge` (NeoForge, supports MC
1.21/1.21.1), pinned by sha512 in `gradle.properties` against the real
[Modrinth-hosted artifact](https://modrinth.com/mod/flowing-fluids/version/1.0.8-neoforge-1.21)
(verified: downloaded and hashed, not installed into a live server). See
`FlowingFluidsCompatibilityGameTests` for the standalone regression evidence, and CI's separate
"Run GameTests with Flowing Fluids coinstalled" job (`-PcoinstallFlowingFluids=true`) for the
real-artifact coinstalled acceptance evidence -- the same GameTest suite, re-run with that real,
verified jar also on the GameTestServer's runtime classpath.

## Building

Requires a JDK 21 with `javac` on `JAVA_HOME`.

```sh
./gradlew build
```

## Releasing

Every PR that touches `src/` must add exactly one fragment under
`changelog.d/` (see `changelog.d/README.md`). Pushing to `main` with new
fragments tags a GitHub release automatically; nothing is committed back by
hand.
