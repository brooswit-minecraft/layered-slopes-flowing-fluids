bump: minor

### Added
- Flowing-fluid layer erosion, extracted from Dynamic Terrain's water erosion: flowing fluid
  passing over or beside a layered or layerable block has a configurable, bounded chance to
  erode it by one layer. First contact converts a full block to a 15-layer layered block;
  eroding the last layer leaves air. Erosion disturbs grass into dirt. Still/source fluid
  contact is a no-op. Required dependency on Layered Slopes only, no Dynamic Terrain runtime
  dependency. Sampling is deduplicated globally per second across all players so overlapping
  sample radii cannot double-attempt the same position.
