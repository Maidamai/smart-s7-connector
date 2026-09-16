# Testing

This project separates tests into three safety tiers. Only the first tier runs
by default; the other two are opt-in and never run in CI or on a plain
`mvn test`.

## Tier 1: Local deterministic tests (default)

```bash
mvn test
```

Runs every `*Test` class using in-memory connectors and local loopback
servers (`LocalS1500Server` binds to `127.0.0.1` only). No PLC, no network
access beyond loopback, no write requests leave the process.

## Tier 2: Live read-only verification (opt-in)

```bash
mvn -Pplc-live-it verify -Dplc.host=192.168.0.10 -Dplc.port=102 -Dplc.rack=0 -Dplc.slot=2
```

Runs the `*IT` classes through the Failsafe plugin. Read-only tests only need
`plc.host`; they never issue a write request.

## Tier 3: Live write verification (explicit authorization required)

Tier 3 additionally writes PLC memory, so every write test verifies its
authorization **before opening a connection**:

```bash
mvn -Pplc-live-it verify \
    -Dplc.host=192.168.0.10 \
    -Dplc.allowWrites=true \
    -Dplc.allow.ranges=DB1:0-63
```

Rules enforced by `LivePlcTestGuard`:

- `plc.host` alone never authorizes writes.
- Writes require the exact value `plc.allowWrites=true`.
- Writes also require `plc.allow.ranges`, an explicit inclusive byte-range
  whitelist. Every range a test writes must be fully covered, otherwise the
  test fails before any connection is opened.
- Whitelist syntax: comma-separated `AREA:first-last` entries where `AREA` is
  `DB<number>` or one of `I`, `Q`, `M`. Example: `DB1:0-63,M:0-1023`.
- Setting `plc.allowWrites=true` with a missing or malformed whitelist fails
  the test instead of falling back to a permissive default.

## Properties

| Property | Default | Used by | Meaning |
|---|---|---|---|
| `plc.host` | unset | Tier 2/3 | PLC address; unset skips live tests |
| `plc.port` | `102` | Tier 2/3 | ISOTCP port |
| `plc.rack` | `0` | Tier 2/3 | Rack number |
| `plc.slot` | `2` | Tier 2/3 | Slot number |
| `plc.timeoutMillis` | `3000` | Tier 2/3 | Request timeout |
| `plc.allowWrites` | unset | Tier 3 | Must be exactly `true` to enable live writes |
| `plc.allow.ranges` | unset | Tier 3 | Write whitelist, e.g. `DB1:0-63,M:0-1023` |
| `plc.tags.file` | unset | `S1500PlcTagScaleIT` | Tag workbook (`.xlsx` or CSV) |
| `plc.scale.cycles` | `3` | `S1500PlcTagScaleIT` | Write/read cycles per scale step |

## Safety notes for live runs

- Run Tier 2/3 only against an isolated test PLC or a simulator, never against
  a production control chain.
- Restoring the original memory snapshot after a write test does not undo
  actions the device may already have performed; assume written values reach
  the process image.
- The default GITHUB-triggered build (once CI exists) must not configure
  `plc.host` or write properties, so external PRs cannot make it touch a PLC.
