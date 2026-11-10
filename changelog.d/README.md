# changelog.d/ — per-PR changelog fragments

Every PR that touches a gated path (by default `src/`, `schema/`, or the
version file — see this repo's README for the exact set your project
configured) must add exactly one new fragment here: `changelog.d/<TICKET>.md`.
One file per PR means two concurrent PRs never touch the same line of the
same file — nothing to rebase away, no discarded approvals. Do **not** append
to a shared `## [Unreleased]` section instead; that reintroduces the exact
conflict this scheme exists to remove.

The PR-time `release-gate` CI check only validates that a gated PR added a
fragment and that its `bump:`/`### BREAKING` rules are consistent — it does
not write anything. Separately, `.github/workflows/release.yml` runs on
every push to `main`: it looks for fragments added since the latest
`vX.Y.Z` git tag reachable from that commit, computes the next version from
their highest `bump:` level, and tags + publishes a GitHub release. It does
**not** bump `version=` in `gradle.properties`, does **not** add a dated
heading to `CHANGELOG.md`, and does **not** delete the fragments —
fragments simply accumulate in git history; the tag is the record of what
has already shipped, not the fragment set. **Do not** bump the version file
or add a dated CHANGELOG heading yourself — nothing in this workflow reads
either, and the release's actual notes come from the fragments themselves.

## Format

```
bump: minor

### Added
- a thing this PR adds

### Fixed
- a bug this PR fixes
```

- First line: `bump: major` / `bump: minor` / `bump: patch` — see
  `CHANGELOG.md`'s `## Versioning` section for what each level means.
- Followed by `### <Section>` blocks (`BREAKING`, `Added`, `Changed`,
  `Fixed`, `Removed`) with `- ` bullets.
- A `### BREAKING` section requires `bump: major`, and `bump: major` requires
  a `### BREAKING` section — the gate checks both directions.
- If your project configured `surface-patterns` and your PR adds or removes
  a match, the gate requires at least `bump: minor` (added) or `bump: major`
  (removed).
- If a file under `schema/` changed, declare at least `bump: minor`.

A PR with no gated changes needs no fragment.
