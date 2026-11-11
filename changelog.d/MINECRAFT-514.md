bump: patch

### Fixed
- `LegacyConfigMigration.readLegacyWaterSamplesPerSecond` no longer throws `NumberFormatException`
  out of `FlowingFluidsConfig`'s static initializer (crashing mod load) when a legacy
  `dynamicterrain-server.toml`'s `waterSamplesPerSecond` is set to a value too large for an
  `int` -- falls back to "never set", like any other unreadable/absent value.
- A world whose own per-world `serverconfig/dynamicterrain-server.toml` explicitly opted out of
  (or into) water erosion now wins over whatever the global legacy config says: the previous
  migration only ever read the single global `config/dynamicterrain-server.toml` once, at class
  load, so a world-specific legacy choice could be silently overridden by an unrelated global
  default. New `PerWorldLegacyMigration`, run on every `ServerAboutToStartEvent`, gates purely on
  whether that world's own `serverconfig/layeredslopesflowingfluids-server.toml` override already
  exists -- for any reason -- and leaves it (and live config) completely alone if so; only when
  it's genuinely absent does it read that world's own legacy file and persist the correct choice.
  A fresh world with no per-world legacy file at all is left completely untouched.
- A valid inline TOML comment after `erosionEnabled`/`waterSamplesPerSecond` in a legacy config
  (`erosionEnabled = true # comment`) is now actually parsed for its real value instead of being
  silently treated as absent.
- A legacy `waterSamplesPerSecond` that fits in an `int` but is still outside this mod's own
  `0..4096` spec (`4097`, `5000`, `Integer.MAX_VALUE`) is now normalized the same way an
  out-of-`int`-range value already was, instead of passing straight through to the static config
  default (which only validates, never clamps) or into a per-world override.
- `LayerErosion.erode`'s public entrypoint now checks `level.isLoaded(pos)` itself, before any
  block/fluid state read, instead of relying entirely on its one existing caller
  (`FlowingFluidErosion.sampleAround`) to have pre-checked -- any other caller handing it an
  unloaded position could previously force a synchronous chunk load/generation. New `Result.UNLOADED`.
