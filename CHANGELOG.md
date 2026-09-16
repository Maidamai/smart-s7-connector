# Changelog

All notable changes to this project are documented in this file. The format
is based on [Keep a Changelog](https://keepachangelog.com/).

## [1.0.0-SNAPSHOT] - unreleased

No artifact has been published yet. This version reflects the security and
correctness hardening cycle driven by an external static audit of commit
`00ecbbe`.

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

### Added

- Live PLC integration tests are gated behind explicit write authorization
  (`plc.allowWrites=true` plus a byte-range whitelist `plc.allow.ranges`),
  verified before any connection is opened; Failsafe `plc-live-it` profile;
  three-tier test documentation (`docs/testing.md`).
- Local S7-1500 loopback server supports writes, scripted item-status
  rejection, raw frame injection, and per-request PDU length recording.
- File-level license provenance: full LGPL-2.0 text, third-party notices,
  and per-file provenance analysis (`LICENSES/`, `THIRD_PARTY_NOTICES.md`,
  `docs/provenance.md`, updated `NOTICE`). No license decision is changed;
  official publication stays paused until the maintainer closes it.
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

### Security

- Writing to an external PLC from tests now requires explicit, whitelisted
  authorization; `plc.host` alone never authorizes a write.
- Protocol responses are attributed to their request via PDU sequence
  numbers; ambiguous streams are closed.

### Known limitations

- Live-PLC behavior is verified against local loopback fixtures only; real
  device compatibility is documented as "unverified" in
  `docs/compatibility.md`.
- `DWORD`/`DINT` remain signed-long interpretations (documented contract).
- No automatic reconnection or write replay by design; connections are
  single-use after any failure (documented in `docs/api-contract.md`).
- The license provenance decision is open; until closed, no official
  artifacts are published.
