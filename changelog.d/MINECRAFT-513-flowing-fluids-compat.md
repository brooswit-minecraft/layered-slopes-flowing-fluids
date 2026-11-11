bump: minor

### Added
- Documented and tested compatibility with the optional Flowing Fluids mod (Traben-0/flowing_fluids,
  pinned v1.0.8-1.21-neoforge, sha512-verified): no runtime dependency (no `neoforge.mods.toml`
  entry, nothing on the runtime classpath unless `-PcoinstallFlowingFluids=true`), since it builds
  its fluid states through the same public vanilla `FlowingFluid.getSource`/`getFlowing` methods
  this mod already reads. `FlowingFluidsCompat` (compileOnly, like `AtmosphereCompat` in
  dynamic-vehicles) is the one exception, querying Flowing Fluids' own config and tick-state flag
  for real, verified evidence -- see below. New standalone regression evidence
  (`FlowingFluidsCompatibilityGameTests`) and a separate CI job re-running the full GameTest suite
  with the real, pinned Flowing Fluids jar also installed.
- Erosion eligibility now distinguishes a genuinely advancing flowing-fluid contact from a
  stationary, already-equalized pool (`FlowingFluidErosion.isAdvancing`): a settled pool no longer
  erodes, matching how a still source already behaved. Applies identically to vanilla and to the
  optional Flowing Fluids mod, which can deliberately let a partial cell settle into such a pool.
  A full, non-source falling column always still erodes.
- A source contact Flowing Fluids' own tick was verified to actually touch
  (`RecentFlowActivity`, a bounded, time-windowed record populated by the same
  `BlockEvent.NeighborNotifyEvent` any real fluid update already fires, kept only while
  `FlowingFluidsCompat.isCurrentlyTicking()` -- Flowing Fluids' own public
  `isManeuveringFluids` flag -- is actually true) also erodes, independent of
  `isSource()`/fullness -- Flowing Fluids represents every full cell as a source, including one
  it is actively feeding or draining, so `isSource()` alone cannot tell that apart from a
  permanently static ocean or lake. A manual edit (even one Flowing Fluids is loaded and
  governing for) never counts, since it never runs while that flag is true; clears on every
  server start and requires a nonnegative age, closing a gap where a stale entry could misread as
  "recent" after a tick-count reset. Also clears a position's record outright (not merely skips
  recording a new one) on any notification that does not itself qualify, and re-checks that the
  fluid currently at a position is still nonempty and governed before trusting any stored record
  there at all -- closing a gap where a stale record could outlive the fluid it was made about.
- Added regression coverage for Flowing Fluids disabled/ignoring water (falls back to the plain
  vanilla rule, verified by directly toggling its own config), and for a manual replacement
  immediately discarding a stale `RecentFlowActivity` record rather than letting it age out.
- Flowing Fluids' `isManeuveringFluids` flag turned out not to be set during its own periodic
  random-leveling smoothing tick (active by default), a real transfer path that was therefore
  going undetected. Added a narrowly-targeted, optional (`@Pseudo`, skipped entirely when Flowing
  Fluids is absent) mixin on its own low-level fluid-amount setter -- the one point every one of
  its transfer paths, including this one, ultimately goes through -- closing the gap at its root
  rather than patching around it. An initial plain `@Inject` HEAD/RETURN version was not
  exception-safe (a thrown setter would leave it permanently stuck positive); replaced with a
  genuine `try/finally` around the original call via MixinExtras' `@WrapMethod` (jar-in-jar
  embedded), exception-safe by construction, with the per-tick reset kept only as defense in
  depth under that real fix.
