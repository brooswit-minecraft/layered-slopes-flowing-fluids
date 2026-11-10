bump: minor

### Added
- Documented and tested compatibility with the optional Flowing Fluids mod (Traben-0/flowing_fluids,
  pinned v1.0.8-1.21-neoforge, sha512-verified): no dependency and no compile-time reference to its
  classes, since it builds its fluid states through the same public vanilla
  `FlowingFluid.getSource`/`getFlowing` methods this mod already reads. New standalone regression
  evidence (`FlowingFluidsCompatibilityGameTests`) and a separate CI job re-running the full
  GameTest suite with the real, pinned Flowing Fluids jar also installed.
- Erosion eligibility now distinguishes a genuinely advancing flowing-fluid contact from a
  stationary, already-equalized pool (`FlowingFluidErosion.isAdvancing`): a settled pool no longer
  erodes, matching how a still source already behaved. Applies identically to vanilla and to the
  optional Flowing Fluids mod, which can deliberately let a partial cell settle into such a pool.
  A full, non-source falling column always still erodes.
- A source contact that was genuinely observed to just change (`RecentFlowActivity`, a bounded,
  time-windowed record populated by the same `BlockEvent.NeighborNotifyEvent` any real fluid
  update already fires) also erodes, independent of `isSource()`/fullness -- Flowing Fluids
  represents every full cell as a source, including one it is actively feeding or draining, so
  `isSource()` alone cannot tell that apart from a permanently static ocean or lake.
