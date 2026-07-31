# ADR-0015: What the APK carries is published as licence lists, not as an SBOM

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Consequences](#consequences)
- [References](#references)

## Status

Accepted (2026-07-31).

## Context

A release publishes an APK that carries far more than the sources of this
repository: 154 crates and vendored projects on the native side, 114 Maven
artifacts on the Kotlin one. Two questions follow from that, and they are
usually answered by the same document. Who wrote what is in here, and under
what terms? And is any of it known to be vulnerable?

The first is already answered. `tools/licenses.sh` reads the crate graph and
the vendored projects, writes `NOTICE` and `app/src/main/assets/licenses-core.json`,
and CI fails when either falls out of step with the graph. The licensee plugin
does the same for the Gradle graph and packages `assets/licenses.json` into the
APK. Both files are JSON, both name every component with its version, its
licence and where it came from, and both travel inside the artifact they
describe.

The second is answered on this side rather than the reader's: `rustsec/audit-check`
runs weekly against the crate graph, and Dependabot watches cargo, Gradle,
GitHub Actions and the container images. An advisory against the vendored TLS
stack reaches a phone only through a release here (ADR-0005), so it is this
project that has to notice it.

What an SBOM in CycloneDX or SPDX would add is a format other people's tools
read: a dependency catalogue, a scanner such as OSV, an inventory a company
keeps of what it installs. That reader does not exist for an agenda application
someone installs on their own phone, and the regulations that make one exist —
the EU Cyber Resilience Act, US Executive Order 14028 — address commercial
suppliers rather than a personal open-source release.

## Decision

No SBOM is generated or attached to a release. The composition of the APK is
published as the two licence lists it already carries, and the advisories are
watched here.

This is revisited when a reader appears: publication through a catalogue such
as F-Droid, an organisation that installs the APK and asks for one, or a
regulation that applies. The lists hold everything an SBOM needs — name,
version, licence, origin — so the work is a format conversion, not a new
collection of data.

## Consequences

- A release carries an APK and its notes, and nothing has to be kept in step
  with a specification that moves on its own.
- Somebody who wants the composition in a machine-readable form has it, but in
  a shape of this project's own: `licenses-core.json` and `licenses.json` inside
  the APK, `NOTICE` in the repository.
- A tool that expects CycloneDX or SPDX cannot read that shape. Anyone who
  needs it converts the two lists, or asks for this decision to be revisited.
- The decision rests on there being no external consumer. That is a fact about
  today, not a property of the project, and it is the thing to re-check rather
  than the reasoning above it.

## References

- `tools/licenses.sh` — collects the native half and writes `NOTICE`.
- `app/build.gradle.kts` — the licensee configuration that packages the Gradle
  half as `assets/licenses.json`.
- `.github/workflows/audit.yml`, `.github/dependabot.yml` — how advisories are
  noticed.
- ADR-0005 — why the TLS stack inside the APK is this project's to update.
