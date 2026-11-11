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
  default. New `PerWorldLegacyMigration`, run once per world (first-write-only, via a per-world
  marker) on `ServerAboutToStartEvent`, persists the correct per-world choice into that world's
  own `serverconfig/layeredslopesflowingfluids-server.toml` override. A fresh world with no
  per-world legacy file at all is left completely untouched.
- `LayerErosion.erode`'s public entrypoint now checks `level.isLoaded(pos)` itself, before any
  block/fluid state read, instead of relying entirely on its one existing caller
  (`FlowingFluidErosion.sampleAround`) to have pre-checked -- any other caller handing it an
  unloaded position could previously force a synchronous chunk load/generation. New `Result.UNLOADED`.
