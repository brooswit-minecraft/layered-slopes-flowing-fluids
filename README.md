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
