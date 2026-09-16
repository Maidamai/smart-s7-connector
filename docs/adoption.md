# Adoption

This file is the project's framework for recording evidence of real-world
adoption. Its purpose is honest accounting, not marketing: anything without
verifiable evidence is recorded as unknown.

## Evidence definitions

### User types

| User type | Meaning |
| --- | --- |
| Self-use | The maintainer's own usage of the library in their own work. |
| Same-maintainer projects | Other projects owned by the same maintainer that depend on this library. These are evidence of usage, not of independent external adoption. |
| External independent users | Individuals or organizations with no affiliation to the maintainer who use the library in their own projects. |

### Evidence types

| Evidence type | Description | Notes on reliability |
| --- | --- | --- |
| Public example | A publicly accessible repository, article, or project that uses the library. | Verifiable by link; strength depends on whether the usage is real and non-trivial. |
| Permitted anonymous statement | A user describes their usage in an issue and explicitly allows it to be published in anonymized form (industry/domain only, no site details). | Only recorded with the user's explicit permission; never de-anonymized. |
| User feedback | Reports, bug reports, or discussions that demonstrate real usage (e.g. a bug report that could only come from real-world use). | Indirect evidence; weight depends on how clearly it demonstrates actual deployment. |
| Download statistics | Maven Central or GitHub download numbers. | Weak evidence: always record data source, exact time window, and whether the count may include CI/automated builds. Never presented as user counts. |

## Current status

All entries are as honest as possible. As of **2026-09**, no verifiable
adoption data has been collected, so every field is recorded as unknown:

| User type | Evidence type | Status | Evidence / link |
| --- | --- | --- | --- |
| Self-use | — | Unknown / no verifiable data | — |
| Same-maintainer projects | — | Unknown / no verifiable data | — |
| External independent users | Public example | Unknown / no verifiable data | — |
| External independent users | Permitted anonymous statement | Unknown / no verifiable data | — |
| External independent users | User feedback | Unknown / no verifiable data | — |
| External independent users | Download statistics | Unknown / no verifiable data | — |

This table must only be updated when concrete evidence exists. Each update
should include the evidence link (or the anonymized statement text), the
evidence type, and the date it was verified.

## How external users can submit proof of use

If you use this library in an independent project, you can help by opening a
regular GitHub issue on this repository with the `adopt` label, describing:

- What kind of project uses the library (domain/industry, project type).
- Which library version and which PLC family you use (no site details, no
  real tag tables, no hosts, no credentials).
- Optionally, whether you allow an anonymized public statement to be
  recorded here.

Please keep all industrial-control details out of the issue, per the
repository's sensitive-information rules: no point lists (点表), no intranet
topology, no credentials, no production addresses.

## Policy: no star-chasing, no metric inflation

This project does not chase stars, downloads, or user counts, and does not
inflate metrics in any way:

- No evidence means the entry stays at **Unknown / no verifiable data**.
- Download numbers are never equated with user counts and are only recorded
  with their data source, time window, and a note on CI/automation inclusion.
- Usage statements are never fabricated, extrapolated, or "rounded up".
