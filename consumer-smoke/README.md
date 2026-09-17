# Consumer smoke project

An independent, self-contained consumer that verifies an **actually published
GitHub Release artifact** of `io.github.maidamai:smart-s7-connector` — it never
builds the library from source. It exists to close the gap between "the sources
on the tag look right" and "what a consumer downloads from the Release page
actually resolves, installs, and works".

## What is verified

1. The Release attachments download from `github.com` (main JAR, `.asc`,
   `SHA256SUMS`).
2. The downloaded JAR's SHA-256 matches the published `SHA256SUMS`.
3. The `.asc` detached signature verifies against the maintainer's public key,
   fetched from a public keyserver and checked against the pinned expected
   fingerprint (default `AF570E87...97DBB3DB`, override with `KEY_FPR`). If the
   key is not published anywhere verifiable, this step **fails** — a signature
   file that exists but cannot be checked is not a pass.
4. A release POM is obtained (Release POM asset if attached, otherwise the raw
   `pom.xml` of the release tag) so transitive dependency metadata survives.
   Releases should attach the POM as an asset; the script reports the fallback.
5. JAR + POM are installed into an **empty isolated Maven repository**
   (`consumer-smoke/isolated-repo/`), never `~/.m2`.
6. The tests in `src/test/` run against that isolated repository and exercise
   the **public API only**: `S7SerializerFactory` → `S7Serializer`
   bean/point/batch round trips against an in-memory `S7Connector` (no PLC, no
   network), including a single-point read-modify-write.

## Not verified here

Maven Central publication, the sources/javadoc attachments, the S7 wire
protocol, and real PLC behavior. Those need their own evidence.

## Run

Requires bash (Git Bash on Windows), `curl`, `sha256sum`, `gpg`, `mvn` on PATH.

```bash
cd consumer-smoke
./verify-release.sh                       # verifies v1.0.0-rc.1 by default
S7_VERSION=1.0.0-rc.2 ./verify-release.sh # the next candidate
```

Every step records `[PASS]`/`[FAIL]` with its exit code and the run ends with a
summary; the script exits non-zero if any step failed. Raw logs land in
`downloads/` (gitignored).

## Expected outcomes per version

- **v1.0.0-rc.1**: `primitiveArraysRoundTrip` fails — the published rc.1 maps
  primitive component arrays (`boolean[]`, `short[]`, ...) through wrapper
  arrays and the read path throws `S7Exception` (round-3 audit finding C1,
  fixed on master after rc.1). Seeing this failure against the downloaded JAR
  is the harness working as intended; see
  `docs/verification/consumer-smoke-rc1-2026-09-17.md` for the recorded run.
- **rc.2 and later** (after C1/C2 fixes): all smoke tests must pass. Signature
  verification additionally requires the maintainer's public key to be
  published on a keyserver (or the expected fingerprint overridden after
  obtaining the key through a trusted channel).
