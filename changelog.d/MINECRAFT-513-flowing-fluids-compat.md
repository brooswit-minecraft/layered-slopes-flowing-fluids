bump: patch

### Added
- Documented and tested compatibility with the optional Flowing Fluids mod (Traben-0/flowing_fluids,
  pinned v1.0.8-1.21-neoforge, sha512-verified): no dependency, no compile-time reference to its
  classes, and no behavior change needed, since it builds its fluid states through the same public
  vanilla `FlowingFluid.getSource`/`getFlowing` methods this mod already reads. New standalone
  regression evidence (`FlowingFluidsCompatibilityGameTests`) and a separate CI job re-running the
  full GameTest suite with the real, pinned Flowing Fluids jar also installed.
