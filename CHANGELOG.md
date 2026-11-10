# Changelog

## Versioning

Semantic versioning, assigned at release time from the `bump:` level declared
in each `changelog.d/` fragment (see `changelog.d/README.md`).

- `major`: a breaking change to the public API or to existing world data.
- `minor`: a new feature, block, or API surface.
- `patch`: a fix or internal change with no new surface.

The shipped version lives in git tags (`vX.Y.Z`) and GitHub Releases, not in
this file or in `gradle.properties`'s `version=` (which stays `0.0.0` and is
only overridden at build time via `-Pmod_version=...`). Do not add dated
`## [x.y.z]` headings here or edit `version=` by hand — the release workflow
does neither; see the repo's GitHub Releases page for what actually shipped.
