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
representation (`LiquidBlock`'s own `LEVEL=8` cache entry, `getFlowing(8, true)`). That means
`isSource()` alone cannot tell a genuinely still-transferring full cell apart from a permanently
static ocean or lake once Flowing Fluids is involved -- treating every source as non-erosive
would silently miss real Flowing Fluids transfers. `RecentFlowActivity` is the fix (see below):
real, mod-agnostic change evidence, not a state-representation assumption.

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

**Known approximation** (`isAdvancing`): this check reads neighbouring block/fluid state
directly rather than replicating every precondition `canFitIntoFluid` itself depends on
(`canPassThroughWall`, `canHoldFluid` -- waterlogging and block-specific exceptions); a neighbour
blocked by one of those edge cases could be misread as open. Bounded, read-only, two-hop, no
persistent state.

### RecentFlowActivity: real, verified transfer evidence from Flowing Fluids' own tick

A source contact is ordinarily non-erosive -- but it also counts, at the maximum amount, if
`RecentFlowActivity` recorded that exact position as having been touched by Flowing Fluids' own
tick within the recent window. Two earlier versions were rejected. First (review 53041): gating
on a bare `BlockEvent.NeighborNotifyEvent` plus a nonempty current fluid is not proof of a
transfer (a manual placement or an unrelated generic notification also qualifies), and it ran
unconditionally, making even plain vanilla static-source placement erosive. Second (reviews
53049/53050/53051): gating on "Flowing Fluids is loaded" plus "the amount/source-ness genuinely
changed from what was last observed" still cannot tell a manual edit (a GameTest fixture, a
player, another mod) apart from Flowing Fluids' own engine -- an amount changing is not evidence
of WHO changed it.

Both gaps close the same way, via `FlowingFluidsCompat` (the only door to Flowing Fluids' real
classes, compileOnly like `AtmosphereCompat` in dynamic-vehicles): `isCurrentlyTicking()` reads
Flowing Fluids' own public `FlowingFluids.isManeuveringFluids` flag (confirmed by decompiling the
pinned jar), which is `true` for the duration of its real fluid-moving tick method and its
displacement path, and `false` everywhere else, including during any manual `Level.setBlock`
call. `isGoverning(fluid)` reads its own `FFConfig.enableMod`/`isFluidAllowed(fluid)` (its
`ff$tickMixin` only cancels and replaces vanilla's tick when both are true -- vanilla's own tick
runs otherwise, which is why gating on this is not redundant busywork: a disabled mod or an
ignored fluid must fall back to the plain vanilla rule, not Flowing Fluids' representation of
it). `RecentFlowActivity` only records a position when BOTH are true at the moment of the
notification -- real, verified evidence of an actual Flowing Fluids transfer, never a manual
edit, with or without an amount/source-ness change.

Review 53094 found, with bytecode-level evidence, that `isManeuveringFluids` is NOT set during
Flowing Fluids' periodic random-leveling smoothing tick (active by default) -- a real transfer
path `isCurrentlyTicking()` was therefore missing entirely. That path calls the exact same
low-level setter (`FFFluidUtils.setFluidStateAtPosToNewAmount`) every other fluid-moving path
ultimately calls, so `LevelingMixin` (`mixin` sub-package; Mixin's own "owned package"
declaration in `layeredslopesflowingfluids.mixins.json` cannot coexist with this mod's ordinary
classes, confirmed locally by a startup crash before moving it there) brackets exactly that one
method with its own reentrancy counter, covering every path at its single lowest common point --
no vanilla or NeoForge event fires around a fluid's random tick to hook instead, the usual
plain-event approach this project otherwise uses throughout. `@Pseudo` (targeting by class
reference, since this mod already has a compileOnly dependency on the pinned jar) is exactly the
pattern Flowing Fluids itself uses for its own optional Create-compatibility mixins (confirmed by
decompiling `MixinHosePulley` et al.): Mixin silently skips applying it at all when the target
class cannot be found, which is the case for the entire standalone build and runtime -- no
crash, no dependency, nothing on the runtime classpath, matching every other door into Flowing
Fluids this project uses (confirmed locally: the standalone GameTest server starts and runs
cleanly with the mixin configured, and the coinstalled one logs it actually applying to
`traben.flowing_fluids.FFFluidUtils`). A counter, not a boolean, because nested calls (a
leveling transfer calling the setter twice, inside a scheduled tick that may already be inside
it once) must not have an inner call's completion incorrectly clear the signal while an outer
call is still in progress.

Bounded by design, per the ticket's "no persistent blocks, no forced chunk loads" requirement:
a hard-capped (4096 entries, oldest evicted first), time-windowed (40 ticks) map populated purely
by reacting to notifications the game already sends for other reasons. It also clears outright on
every server start AND requires the recorded age to be nonnegative -- review 53041 found that a
bare `currentTick - changedTick <= window` check could otherwise read as "recent" forever after a
tick-count reset (a fresh server/world session starting its tick counter back near zero, if a
stale entry with a larger recorded tick survived); either fix alone already closes that gap. This
is also why it is not built on Flowing Fluids' own `IFFFlowListener`/`FFFlowListenerLevel`
push-notification API (the mechanism Create's water wheel uses): that API requires a persistent
registered `BlockEntity` at a known position to receive callbacks, which this mod has none of and
would have to add purely to ask "did flow just happen here."

Review 53074 found a further staleness gap: a position could keep reading as "recently changed"
for up to the rest of the window after the fluid there became empty, manually replaced, or
otherwise no longer Flowing-Fluids-governed, since only the write path checked those conditions.
Fixed two ways, either of which alone would close it: any notification that does not qualify
(empty, not governed, or not actually happening during Flowing Fluids' own tick) now DISCARDS any
existing record for that exact position rather than merely skipping a new one; and the read path
itself re-checks that the fluid CURRENTLY at that position is still nonempty and governed before
even consulting the stored timestamp, rather than trusting a record made against a possibly
different fluid that was there before.

Test evidence: `stationarySettledPartialWaterReportsNoErosionAmount` /
`anAdvancingPartialWaterReportsAPositiveErosionAmount` construct the geometric cases
deterministically with hand-placed states; `aManualSourceCellChangeNeverErodesRegardlessOfFlowingFluids`
proves a manual amount/source-ness change (the exact kind a cruder heuristic could mistake for a
real transfer) never erodes, with or without Flowing Fluids loaded;
`anUnrelatedNeighborNotificationDoesNotMarkAnotherPositionAsRecentlyChanged` proves the record is
scoped strictly per-position; `realFlowingWaterAdvancesAndErodesWhatItTouches` places a genuine
water source and polls the production decision against whatever the server's *own* scheduled
fluid ticks actually produce next to the target, for real, under whichever engine (vanilla, or
the real Flowing Fluids engine when that mod is coinstalled) is actually running;
`aRealFlowingFluidsDrivenFullCellErodesThenSettlesToNoOp` feeds a confined cell to full with no
manual edit of the fed position itself, directly asserting the exact fed position actually
reached a full `isSource()` state verified by `RecentFlowActivity` when Flowing Fluids is
expected to be governing water, or the always-advancing full non-source falling column
otherwise -- not merely a nonzero aggregate reading a partial cell could also satisfy, and not
accepting whichever outcome happens to show up (review 53081: a real Flowing Fluids integration
failure under the coinstalled job could otherwise silently pass via the vanilla fallback
alternative) -- then removes the feed and proves erosion genuinely stops: under Flowing Fluids
the source is retained, stationary, with `RecentFlowActivity` no longer verifying it; under
vanilla's own non-source falling column, with nothing left feeding it, draining away to empty is
its own correct "settled" outcome, confirmed locally under both engines -- distinguishing
"stopped because it settled" from "stopped because it merely drained away" matters because
`amount == 0` alone cannot tell those apart. `aDisabledFlowingFluidsNeverGovernsWater` /
`anIgnoredFluidIsNeverGoverned` toggle Flowing Fluids' own `enableMod`/fluid blacklist directly
(via `FlowingFluidsCompat`'s test-only helpers) and assert `isGoverning` reads false -- a
synchronous, single-tick check rather than a multi-tick physics fixture, deliberately: toggling
either of those GLOBAL config knobs for several real ticks would risk interfering with any other
test's own Flowing Fluids physics running concurrently in the same batch. And
`aManualReplaceAfterARealTransferImmediatelyClearsTheStaleRecord` lets a real transfer verify a
confined position, then manually replaces it and asserts `recentlyChanged` reads false on the
very next check -- proving the stale record is discarded immediately, not merely left to age out.
`aRandomLevelingTransferIsVerifiedByRecentFlowActivity` walls off every direction except one
between two different-amount cells and calls the genuine public `FluidState.randomTick` directly
(retrying up to a generous bound against the one remaining real randomness -- Flowing Fluids' own
choice of scan direction -- rather than waiting on vanilla's probabilistic per-chunk scheduler)
until a real random-leveling transfer happens, then asserts `RecentFlowActivity` verifies it --
proving `LevelingMixin` actually closes the gap review 53094 found, not merely that the idea
should work.

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
