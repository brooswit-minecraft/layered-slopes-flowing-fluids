# layered-slopes-flowing-fluids

Flowing fluid passing over or beside a layered/layerable block has a chance to erode it by a layer.

NeoForge 1.21.1, Java 21. Mod ID: `layeredslopesflowingfluids`. MIT licensed.

Extracted from Dynamic Terrain's water erosion (MINECRAFT-513). Each second, a
bounded number of random positions around each player are sampled; an
eligible block (a Layered Slopes layered block, or a full vanilla block with
a registered layered variant) with *actively advancing* fluid in the
`minecraft:water` tag over it or beside it has a chance to lose one layer,
scaled by hardness/blast-resistance and how full the flowing cell is. "Actively
advancing" means more than just non-source: a flowing-type cell that has
already equalized with its neighbours (nowhere lower to spread into) is
treated as stationary and does not erode, exactly like a still source --
only a cell with a genuinely open or lower-amount neighbour to flow into
(or a full, non-source falling column, which is always advancing) counts.
A full block's first hit converts it to a 15-layer layered block; a layered
block's last layer erodes to air. Erosion disturbs grass into dirt.
Stationary fluid contact, and fluids outside the water tag (lava/heat
erosion stays Dynamic Terrain's), are no-ops. A fake-player
`BlockEvent.BreakEvent` is posted before any change so claim/protection
mods that listen on it can veto it.

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
`getFlowing(int, boolean)` methods this mod could call directly: `amount >= 8 ?
flowing.getSource(false) : flowing.getFlowing(amount, false)`.

One real difference that formula implies: under Flowing Fluids, EVERY full (amount 8) cell is a
source state (`isSource()==true`), including one still actively being fed/drained by its finite
simulation -- Flowing Fluids has no equivalent of vanilla's distinct non-source "falling column"
representation (`LiquidBlock`'s own `LEVEL=8` cache entry, `getFlowing(8, true)`). Mechanically,
though, neither representation actually transfers mass on its own once formed: vanilla's falling
flag is a rendering distinction (texture/height), not a sign of an ongoing per-tick transfer, and
a full cell under either engine behaves like a stable, self-sustaining feed point going forward.
So treating `isSource()==true` as always non-erosive (this mod's existing rule, matching the
ticket's own "static/source-only contact" no-op requirement) is consistent for both engines --
the "moving full cells" case this mod also handles (an explicit non-source, amount-8 state)
specifically covers vanilla's distinct falling-column representation, which simply does not
arise under Flowing Fluids' model.

Flowing Fluids replaces fluid tick behaviour so a partial-amount cell can settle into a stable,
non-advancing finite pool rather than always re-evaluating every tick the way vanilla does.
`FlowingFluidErosion.isAdvancing` distinguishes exactly that case, for both vanilla and Flowing
Fluids: a flowing-type cell counts as advancing only if at least one horizontal neighbouring
BLOCK is air, or a non-air same-fluid neighbour holds a strictly lower amount; or the downward
neighbour is air, or the same fluid below full -- mirroring Flowing Fluids' own decompiled
`canFitIntoFluid` spread precondition (also vanilla's own, not something Flowing Fluids
introduces). Checking the neighbouring *block* for air, not just the neighbouring *fluid state*
for "empty," matters: an ordinary solid block's `FluidState` is also `isEmpty()` (no fluid there
is not the same as "open to flow into"), so a fluid-state-only check would misclassify a solid
wall as an open spread path. A settled pool (no open or lower neighbour) is treated as
stationary, like a source; a cell with one is erosive. A full (amount 8), non-source cell -- a
falling waterfall column -- is always advancing regardless, per the ticket's explicit "moving
full cells" requirement (vanilla-only, per the full-cell note above).

Test evidence for this specifically: `stationarySettledPartialWaterReportsNoErosionAmount` /
`anAdvancingPartialWaterReportsAPositiveErosionAmount` construct the two cases deterministically
with hand-placed states; `realFlowingWaterAdvancesAndErodesWhatItTouches` places a genuine water
source on open ground and lets the server's *own* scheduled fluid ticks run for real (the real
vanilla engine, or the real Flowing Fluids engine when that mod is coinstalled) before checking
the production decision against whatever state that real simulation produced -- not just a
hand-constructed one.

**Known approximation**: this check reads neighbouring block/fluid state directly rather than
replicating every precondition `canFitIntoFluid` itself depends on (`canPassThroughWall`,
`canHoldFluid` -- waterlogging and block-specific exceptions); a neighbour blocked by one of
those edge cases could be misread as open. This is a bounded, read-only, two-hop check with no
persistent state, world scanning, or forced chunk loads -- it does not use Flowing Fluids'
`IFFFlowListener`/`FFFlowListenerLevel` push-notification API (the mechanism Create's water
wheel uses), which requires a persistent registered `BlockEntity` at a known position and so
does not fit this mod's stateless random-sampling design.

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
