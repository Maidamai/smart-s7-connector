# Changelog

All notable changes to this project are documented in this file. The format
is based on [Keep a Changelog](https://keepachangelog.com/).

## [1.0.0-rc.2] - 2026-09-17

Second pre-release candidate: the round-3 bean-mapping fixes plus the
fail-closed published-artifact verification flow. Published as signed
artifacts — main/sources/Javadoc JARs, the POM, one `.asc` per artifact, a
four-entry `SHA256SUMS`, and the armored public signing key — attached to
the GitHub Release for tag `v1.0.0-rc.2`; **not yet on Maven Central**
(publication pending maintainer Central Portal credentials — see
`docs/releasing.md`). `v1.0.0-rc.1` remains published and unchanged: it
contains the primitive-array defect fixed here and carries no signed POM,
so it fails the new verification gates by design.

Scope statements kept honest by design:

- Real-PLC verification has **not** been performed (unchanged since rc.1);
  see `docs/verification/live-plc-attempt-2026-09-17.md`.
- rc.1 was re-verified consumer-side against its actually downloaded
  attachments, which reproduced the primitive-array defect on the published
  JAR (`docs/verification/consumer-smoke-rc1-2026-09-17.md`). rc.2 is
  verified through the fail-closed gate: checksums, signatures against the
  pinned primary fingerprint, signed POM coordinates, license resources,
  isolated-repository install and public-API success paths —
  `docs/verification/consumer-smoke-rc2-2026-09-17.md`.

### Fixed

- **Primitive component arrays are extracted into the declared component
  type** (3rd audit round, C1). `boolean[]`, `short[]`, `int[]`, `long[]`,
  `byte[]`, ... fields used to receive a `Boolean[]`/`Short[]`/... wrapper
  array and every `dispense` failed with a wrapped
  `IllegalArgumentException` (present in the published `v1.0.0-rc.1`;
  reproduced consumer-side against the downloaded Release JAR). Arrays are
  now allocated with the field's declared component type and filled element
  by element (`Array.set` unboxes); wrapper component arrays keep their
  behavior. Array fields whose declared component type is not one the S7
  type maps onto (e.g. `int[]` with `DINT`, which maps to `long`/`Long`)
  are rejected at parse time instead of failing on the first
  extract/insert; the accepted component types are part of
  `docs/api-contract.md`.
- **A layout's overall end offset can no longer wrap to a negative block
  size** (3rd audit round, C2). `byteOffset + coveredBytes` above
  `Integer.MAX_VALUE` (e.g. `byteOffset = 2147483647`, `coverage = 1`) is
  rejected at parse time with `S7Exception`; previously the `long`→`int`
  cast silently produced a negative block size. Bit addressing uses `long`
  intermediates so extreme `bitOffset` values cannot wrap into negative
  buffer indexes.

### Added

- **Fail-closed published-artifact verification** (`consumer-smoke/`): the
  verifier downloads the actual Release assets and gates Maven execution
  behind download, SHA256SUMS, GPG signature (exit status plus structured
  `VALIDSIG` checked against the pinned full primary fingerprint, valid
  bound signing subkeys accepted, wrong/expired/revoked/weak-hash
  signatures rejected), signed-POM project coordinates and license
  resources. The armored public key is taken from a trusted file, a
  Release asset or a keyserver, always re-checked against the pinned
  fingerprint. Offline regressions cover the trust bootstrap with real
  disposable keys.
- **Independent consumer CI**: a current-commit consumer-contract job, the
  verifier regression suite, and a `Verify published release` workflow
  that runs the fail-closed gate against the uploaded bytes of every
  published Release.

### Changed

- The live-PLC attempt report no longer publishes private network details
  (placeholders replace host addresses) and no longer over-infers from the
  failed handshakes; the historical "no artifact published yet" note in the
  development section below is now explicitly framed as pre-rc.1 status.

## [1.0.0-rc.1] - 2026-09-17

First public **pre-release candidate**. All changes below (the full hardening
cycle) are included. Published as signed artifacts attached to the GitHub
Release for tag `v1.0.0-rc.1`; **not yet on Maven Central** (publication
pending maintainer Central Portal credentials — see `docs/releasing.md`).

Scope statements kept honest by design:

- Real-PLC verification has **not** been performed: the local network scan
  on 2026-09-17 found two TCP/102 candidates that both failed the
  iso-on-tcp handshake, so every real-hardware row in
  `docs/compatibility.md` remains "not verified" (attempt record:
  `docs/verification/live-plc-attempt-2026-09-17.md`).
- The rc.1 candidate passed clean-consumer verification (artifact
  checksums, license resources, public API smoke): see
  `docs/verification/consumer-rc1-2026-09-17.md`.

## [1.0.0-SNAPSHOT] - unreleased

Historical status as of the time this section was written (before the
`1.0.0-rc.1` release above): no artifact had been published yet. This version
reflects the security and correctness hardening cycle driven by an external
static audit of commit `00ecbbe`.

### Fixed

- **Write failures are no longer reported as success.** A PLC item rejection
  (non-`0xFF` status), a PDU header error, an unexpected function code, a
  mismatching item count, or a missing item status now fail the call. The
  public write failure carries the raw status code, area, DB, offset, length,
  and the number of bytes the PLC has already acknowledged (`S7Exception`).
- **Read and write PDU windows are separated.** Writes are chunked by the
  request-side window (`negotiatedPdu - 28`) instead of the response-side
  read window (`negotiatedPdu - 18`), so a single write request can no longer
  exceed the negotiated PDU length. Chunking is iterative; nonsense
  negotiation values fail explicitly instead of falling back silently.
- **Responses are validated against the actually received frame length.**
  Truncated frames, lying length fields, wrong protocol/ack types, and
  responses that do not echo the request's PDU sequence number are rejected;
  stream-poisoning violations close the transport. Short read answers fail
  instead of returning stale buffer bytes.
- **Point writes are atomic per connector.** The read-modify-write of
  `S7Serializer.store(value, point)` runs as one critical section on the
  connector monitor, so two serializers sharing a connector cannot lose
  updates on the same byte.
- **BOOL arrays advance across byte boundaries** (7/8/9/16 elements and
  bit offset 7 covered by tests).
- **Bean mapping contract enforced:** public fields only (documented),
  full primitive wrapper mapping (including `short`), order-independent
  block size, and a fast failure for classes without mapped fields.
- **STRING handling pinned to the ASCII device charset:** encoded byte length
  is used for the length header, malformed S7 string headers are rejected,
  non-ASCII input throws instead of silently truncating.
- **Transport lifecycle:** interruption during a response wait now clears the
  pending future and closes the transport; a Netty sneaky-thrown connect
  failure no longer leaks the event loop; every failure mode leaves the
  transport in a defined terminal state (see `docs/api-contract.md`).
- **Point read planner** rejects invalid point sizes instead of masking them
  with `max(1, size)`.
- **Unencodable addresses are rejected instead of silently truncated** (2nd
  audit round, R1). The S7 request item carries the DB number in two bytes
  and the start address in three bytes; a DB number above 65535 or a byte
  range above 2097152 used to wrap around onto a *different, valid-looking*
  target (DB 65537 became DB 1, byte offset 2097152 became 0). Both the
  public read/write calls (before any request is sent) and the PDU encoders
  now validate with `long` math; TIMER/COUNTER read raw-unit addressing is
  locked by tests. See `S7AddressBoundsTest`, `docs/api-contract.md`.
- **Array element layout is computed once and shared by coverage and
  offsets** (R2). STRING array elements advance by `size + 2` (capacity plus
  header) — previously the second element's header landed inside the first
  element's payload; STRUCT arrays advance by the nested block size and are
  fully counted in the block size; fixed types use `max(byteSize, size)` on
  both sides. BOOL bit-crossing behavior is unchanged and regression-tested.
  STRING capacities above 254 (unencodable max-length byte) and negative
  annotation values are rejected at parse time, as are layouts whose covered
  byte count overflows `int` (which would silently shrink the block size)
  and recursive STRUCT nesting (which used to risk a `StackOverflowError`).
- **Transport failures during chunked writes keep the confirmed progress**
  (R3). A timeout or I/O error mid-write now throws
  `S7PartialWriteException` (an `IOException` subtype) carrying
  `confirmedWrittenBytes`, the failing chunk's offset/length and the cause,
  instead of a bare `IOException` with no context. The failing chunk's
  outcome is explicitly documented as unknown.

### Added

- Live PLC integration tests are gated behind explicit write authorization
  (`plc.allowWrites=true` plus a byte-range whitelist `plc.allow.ranges`),
  verified before any connection is opened; Failsafe `plc-live-it` profile;
  three-tier test documentation (`docs/testing.md`).
- Local S7-1500 loopback server supports writes, scripted item-status
  rejection, raw frame injection, and per-request PDU length recording.
- File-level license provenance: full LGPL-2.0 text, third-party notices,
  and per-file provenance analysis (`LICENSES/`, `THIRD_PARTY_NOTICES.md`,
  `docs/provenance.md`, updated `NOTICE`).
- Maven Wrapper (3.9.11, checksum-pinned), GitHub Actions CI
  (JDK 8/17/21, SHA-pinned actions, read-only permissions), Dependabot.
- Community files (`CONTRIBUTING.md`, `SECURITY.md`, issue/PR templates),
  adoption evidence framework (`docs/adoption.md`), API contract and
  migration docs (`docs/api-contract.md`, `docs/migration.md`,
  `docs/compatibility.md`, `docs/performance.md`).
- `S7Serializer.dispensePoints(List)` convenience overload avoiding the
  forced cast on `dispense(List)`.
- Release preparation: project metadata (URL, developers, SCM, issues) and a
  `release` profile (sources, javadoc, GPG) — see `docs/releasing.md`.
- **Release-readiness CI (2nd audit round, stage 2):** a `release-dry-run`
  job builds with `-Prelease -Dgpg.skip=true` (no keys in CI) and asserts
  the five `META-INF` license resources in both the main and the sources
  JAR plus the presence of the sources/javadoc JARs; failing matrix legs
  upload their surefire reports as short-lived artifacts.
- **Clean-consumer candidate verification:** a `1.0.0-rc.1` candidate built
  from commit `21abe17` (worktree, release profile, full test run) was
  consumed by a fresh project resolving only the artifact coordinates from
  an isolated local repository — public API loads, unreachable-host failure
  contract holds, 6/6 consumer tests green, 10/10 license-resource
  assertions. Real-PLC verification remains explicitly unverified; see
  `docs/verification/consumer-rc1-2026-09-17.md`.

### Security

- Writing to an external PLC from tests now requires explicit, whitelisted
  authorization; `plc.host` alone never authorizes a write.
- Protocol responses are attributed to their request via PDU sequence
  numbers; ambiguous streams are closed.

### Changed

- **License provenance closed (2026-09-17) as split per-file licensing.**
  `impl/nodave/**` (7 libnodave-derived files) stay LGPL-2.0-or-later with
  their headers retained; every other source file stays Apache-2.0. The pom
  now declares both licenses with scope comments, and the main and sources
  JARs ship `META-INF/LICENSE`, `META-INF/NOTICE`,
  `META-INF/LICENSE_LIBNODAVE.txt`, `META-INF/THIRD_PARTY_NOTICES.md`, and
  `META-INF/licenses/LGPL-2.0.txt`. Licensing no longer blocks the first
  official release. Decision record: `docs/provenance.md` §5. The 2nd audit
  round (R4) tightened the report wording: upstream comparisons are pinned
  to `s7connector/s7connector@fcc7662` instead of a rolling master, the
  actual distribution route (repository source, tagged JARs with source
  correspondence) is spelled out, and the statements "publishing this
  repository satisfies the source-offer duty" and "consumers may pick the
  overall license" were corrected — final distribution obligations should
  still be confirmed by an experienced open-source compliance reviewer.

### Known limitations

- Live-PLC behavior is verified against local loopback fixtures only; real
  device compatibility is documented as "unverified" in
  `docs/compatibility.md`.
- `DWORD`/`DINT` remain signed-long interpretations (documented contract).
- No automatic reconnection or write replay by design; connections are
  single-use after any failure (documented in `docs/api-contract.md`).
- The whole artifact may not be used under Apache-2.0 alone (7 nodave files
  are LGPL-2.0-or-later); see the License section of the README.
