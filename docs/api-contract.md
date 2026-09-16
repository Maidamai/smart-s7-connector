# API Contract

This document is the normative contract of the public API
(`io.github.maidamai.s7connector.api` and `api.factory`). It is kept in sync
with the implementation; if code and this document disagree, file an issue.

## Connection lifecycle (single-use resource)

- A connector built by `S7ConnectorFactory` holds exactly one TCP connection.
- A connector is **single-use**: after `close()` — or after any failure that
  invalidated the transport, see below — the instance is permanently unusable.
  Create a new connector instead of reusing or "reconnecting" the old one.
- Connectors are `Closeable` and must be closed (try-with-resources).

## Thread model

- Every request/response exchange on a connector is serialized on an internal
  **connection monitor**. Multiple threads may share one connector; requests
  are executed one at a time, with no protocol-level multiplexing.
- Point-write (`S7Serializer.store(Object, PlcS7PointVariable)`) performs its
  read-modify-write as one critical section on the same monitor, so
  concurrent serializers sharing a connector cannot lose updates against each
  other. This is not an end-to-end guarantee: the PLC program or other
  clients may change the same memory outside this process.

## Read/write windows

Windows are derived from the negotiated S7 PDU length, and differ because a
write request carries the payload in the outgoing frame:

- **Read window** = negotiatedPduLength − 18 (12-byte header + 2-byte
  parameter + 4-byte data head).
- **Write window** = negotiatedPduLength − 28 (10-byte header + 14-byte
  parameter + 4-byte data head + payload).

Larger reads/writes are transparently split into consecutive chunks and
reassembled. Without PDU negotiation the default window for both is 96 bytes
(`S7BaseConnection.DEFAULT_MAX_READ_BYTES` / `DEFAULT_MAX_WRITE_BYTES`).

## Write semantics

- **Full-block overwrite** (`S7Connector.write`,
  `S7Serializer.store(bean, db, offset)`): every byte of the buffer/block is
  written. Memory not covered by a mapped field (gaps, unset fields, other
  bits in partially used bytes) is written as zero. There is **no rollback**:
  if the PLC rejects a chunk, earlier acknowledged chunks stay written.
- **Point read-modify-write** (`S7Serializer.store(bean, PlcS7PointVariable)`):
  the point's memory range is read, the value is merged, and the range is
  written back, so other bits in the same byte are preserved (within this
  process; see Thread model).

## Error categories

| Exception | Meaning | Connection state afterwards |
|---|---|---|
| `IOException` | Transport failure: connect failure, timeout, I/O error, or an S7-protocol-violating response | Transport closed; connector permanently unusable |
| `S7Exception` | The PLC rejected the operation (e.g. a write), or a parsed response is invalid | Connector remains usable unless the failure also closed the transport |
| `IllegalArgumentException` | Invalid arguments (null area/buffer, negative numbers, unmappable class) | Unchanged |

For a rejected write the `S7Exception` message carries these fields:

- `status` — raw PLC result code (decimal and hex) plus `Nodave.strerror` text
- `area`, `db`, `offset`, `length` — the coordinates of the failing chunk
- `confirmedWrittenBytes` — bytes the PLC already acknowledged in earlier
  chunks of this call (not rolled back). After a timeout, the outcome of the
  failing chunk itself is unknown.

## Response validation rules

Every response is checked before its payload is trusted. Any of the following
makes the request fail **and closes the transport** (the connector becomes
unusable):

1. Truncated frame: fewer bytes than the PDU header requires
   (`RESULT_SHORT_PACKET`).
2. Non-DT COTP header / undecodable PDU (`RESULT_CANNOT_EVALUATE_PDU`).
3. PDU reference number does not match the request's sequence number
   (`RESULT_UNEXPECTED_REFERENCE`).
4. Data length declared in the response does not match what was actually
   received (a lying or truncated frame cannot over-report).

A proper PLC error answer (type 2/3 header error) is reported as an error
result **without** closing the connection.

## Bean mapping contract

- Mapped bean fields must be **public**; private fields do not participate.
  A class with no mappable public fields fails fast with `S7Exception`.
- Field order in the class does not matter; layout comes from
  `@S7Variable(byteOffset=..., bitOffset=...)`.
- `STRING` values are ASCII; non-ASCII characters throw an exception instead
  of being silently mangled.
- BOOL arrays may span byte boundaries.
- `PointReadPlanner` rejects illegal point sizes with
  `IllegalArgumentException`.
- `dispense(List<PlcS7PointVariable>)` / `dispensePoints(List)` return one
  value per input point **in input order**, regardless of how the points were
  merged into batch reads.

## Live testing

Claims about real-PLC behavior must come from the three-tier test model in
[docs/testing.md](testing.md) (Tier 1 local deterministic, Tier 2 live
read-only, Tier 3 live write with explicit authorization). Loopback-only
coverage is not evidence of real-PLC compatibility — see
[docs/compatibility.md](compatibility.md).
