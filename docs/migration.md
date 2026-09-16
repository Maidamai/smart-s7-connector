# Migration Guide (hardening cycle)

This cycle hardened error propagation, response validation, and write
semantics. If you upgrade from the pre-hardening behavior, review the changes
below. Each item links to [docs/api-contract.md](api-contract.md) for the
normative contract.

## 1. Write failures now throw `S7Exception` instead of failing silently

- **Before**: a rejected write could surface as `IllegalArgumentException`
  ("Result: ...") or be swallowed; failure details were lost.
- **Now**: `S7Connector.write` throws `S7Exception` whose message carries
  `status`, `area`, `db`, `offset`, `length`, and `confirmedWrittenBytes`.
- **Migration**: catch `S7Exception` for PLC rejections; treat
  `IOException` as a dead connection (see item 6). `confirmedWrittenBytes`
  tells you how much of the write actually landed — there is no rollback.

## 2. Write chunks are smaller

- **Before**: writes reused the read window.
- **Now**: the write window is `pduLength − 28` (the write request carries
  the payload), smaller than the read window `pduLength − 18`. Large writes
  are split into more chunks. No action needed unless you asserted on the
  number of PLC write requests.

## 3. Response validation is stricter

- **Now**: truncated frames, forged length fields, non-DT COTP headers, and
  PDU reference-number mismatches fail the request **and close the
  transport**. A peer that does not echo the request's sequence number will
  be disconnected. If you integrate with a non-standard device/soft-PLC that
  violates these rules, it will now fail loudly instead of feeding the parser
  garbage data. Report such devices as compatibility issues
  ([docs/compatibility.md](compatibility.md)).

## 4. STRING values must be ASCII

- **Before**: non-ASCII STRING content was mangled silently.
- **Now**: writing or reading a STRING containing non-ASCII characters throws
  an exception. Store non-ASCII payloads as BYTE arrays instead.

## 5. Bean mapping: public-field contract and fast failure

- Mapped fields must be **public** (documented before, now enforced and
  tested): private fields never participate.
- A class with **no mappable fields** now fails fast with `S7Exception`
  instead of producing a silently-empty bean.
- BOOL arrays may span byte boundaries; `blockSize` in
  `dispense(..., blockSize)` is independent of argument order.

## 6. Connections are terminal after any failure

- **Now**: after `close()`, an `IOException`, or a protocol-violating
  response, the connector is permanently unusable. Build a new connector; do
  not retry on the same instance.

## 7. `PointReadPlanner` rejects illegal sizes

- Point sizes that cannot be planned (illegal byte/bit sizes) now throw
  `IllegalArgumentException` at planning time instead of producing corrupt
  reads.

## 8. Point writes are serialized per connector

- `store(bean, PlcS7PointVariable)` performs read-modify-write as one
  critical section on the connector monitor. Concurrent serializers sharing
  a connector can no longer lose updates against each other — but the PLC
  program or other clients can still change the same memory.
