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

## Address limits

The S7 request item encodes the DB/area number in an unsigned 16-bit field
and the start address in an unsigned 24-bit field. Ranges that cannot be
represented are rejected with `IllegalArgumentException` **before any
request is sent** — the library never silently truncates an address, because
a truncated address points at a different, valid-looking location:

- **DB/area number** ≤ 65535 (two-byte field).
- **Byte-addressed areas** (DB, FLAGS, INPUTS, OUTPUTS, …): `offset +
  length` ≤ 2097152, because the item carries `offset × 8` as a 24-bit bit
  address.
- **TIMER/COUNTER reads**: the item carries the address in raw units, so
  `offset + bytes` ≤ 16777216. TIMER/COUNTER **writes** are encoded as bit
  addresses like every other write (inherited encoder asymmetry, locked by
  tests).

These are encodability limits, not device capability limits: whether a
specific PLC accepts a legal address is a device property. Zero-length
accesses are bound by the same offset limits (an empty range at an
unencodable offset is still a caller error and is rejected).

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
- **Transport failure mid-write** (timeout, I/O error, interruption): the
  call fails with `S7PartialWriteException`, an `IOException` subtype that
  carries `confirmedWrittenBytes` (bytes the PLC acknowledged in earlier
  chunks), the failing chunk's offset and length, and the original cause.
  The failing chunk's outcome is **unknown** — no response was received, so
  it may or may not have been written; a timeout must not be read as
  "nothing was written". The library does not replay failed writes.

## Error categories

| Exception | Meaning | Connection state afterwards |
|---|---|---|
| `IOException` | Transport failure: connect failure, timeout, I/O error | Transport closed; connector permanently unusable |
| `S7PartialWriteException` (a checked `IOException` subtype — existing `IOException` handlers keep working) | Transport failure during a write; carries confirmed progress and the failing chunk coordinates | Same as `IOException` |
| `S7Exception` (unchecked) | The PLC rejected the operation (a read or a write), or the response violated the S7 protocol | Item-level rejections keep the connector usable; frame-level violations (truncated frames, lying lengths, mismatched PDU references) also close the transport |
| `IllegalArgumentException` | Invalid arguments (null area/buffer, negative numbers, unmappable class, unencodable address range) | Unchanged |

For a rejected read or write the `S7Exception` message carries these fields:

- `status` — raw PLC result code (decimal and hex) plus `Nodave.strerror` text
- `area`, `db`, `offset`, `length` — the coordinates of the failing chunk
- `confirmedWrittenBytes` — bytes the PLC already acknowledged in earlier
  chunks of this call (not rolled back). After a timeout, the outcome of the
  failing chunk itself is unknown. The same applies to frame-level
  violations on a write (the request was sent, the response was rejected as
  untrustworthy): the failing chunk's outcome is unknown there too.

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
- **Array element strides** are computed once at parse time and shared by
  the covered block size and the per-element read/write offsets:
  - `STRING`: `size + 2` (capacity plus max/current length header); the
    capacity must be 0–254 (encodable in the unsigned max-length header
    byte), otherwise parsing fails with `S7Exception`.
  - `STRUCT`: the nested bean's block size; every element of a struct array
    is counted in the coverage.
  - fixed-width types: `max(byteSize, size)`.
  - `BOOL`: bit-addressed, element `i` at bit `bitOffset + i` — arrays
    cross byte boundaries.
- **Array component types**: every S7 type maps element by element onto one
  natural Java type, and an array field may declare it as the primitive or
  the matching wrapper: `BOOL`→`boolean`/`Boolean`, `BYTE`→`byte`/`Byte`,
  `INT`→`short`/`Short`, `WORD`→`int`/`Integer`,
  `DINT`/`DWORD`/`TIME`→`long`/`Long`, `REAL`→`float`/`Float` or
  `double`/`Double`, `STRING`→`String`, `DATE`/`DATE_AND_TIME`→`Date`;
  `STRUCT` arrays take the nested bean class as component type. An array
  field declaring any other component type is rejected at parse time with
  `S7Exception`.
- Negative `byteOffset`/`bitOffset`/`size`/`arraySize` values are rejected
  at parse time with `S7Exception`.
- A layout whose overall end offset (`byteOffset` + covered bytes) exceeds
  `Integer.MAX_VALUE` is rejected at parse time with `S7Exception` instead
  of wrapping around to a negative block size.
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
