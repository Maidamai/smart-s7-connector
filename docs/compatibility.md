# PLC Compatibility Matrix

**Enumerated support ≠ verified support.** The `SiemensPLCS` enum and the
README tables list PLC families the protocol implementation targets; listing
a model there is not evidence that the library has been tested against real
hardware of that model. A cell only leaves "未验证 / Not verified" when there
is a reproducible test run (Tier 2/3, see [docs/testing.md](testing.md)) with
a linked evidence artifact (CI log, test report, or issue).

| PLC model / family | Connection | Read | Write | Test method | Evidence |
|---|---|---|---|---|---|
| S7-1500 behavior, local loopback (`LocalS1500Server`) | Verified (Tier 1, loopback emulation) | Verified (Tier 1, loopback emulation) | Not verified (loopback tests assert no write is issued) | `LocalS1500Server` on 127.0.0.1, e.g. `S1500LoopbackPerformanceTest` | This repository, default `mvn test` |
| S7-1500 (real hardware) | 未验证 / Not verified | 未验证 / Not verified | 未验证 / Not verified | — | — |
| S7-1200 (real hardware) | 未验证 / Not verified | 未验证 / Not verified | 未验证 / Not verified | — | — |
| S7-300 (real hardware) | 未验证 / Not verified | 未验证 / Not verified | 未验证 / Not verified | — | — |
| S7-400 (real hardware) | 未验证 / Not verified | 未验证 / Not verified | 未验证 / Not verified | — | — |
| S7-200 Smart (real hardware) | 未验证 / Not verified | 未验证 / Not verified | 未验证 / Not verified | — | — |
| Other / third-party soft-PLC | 未验证 / Not verified | 未验证 / Not verified | 未验证 / Not verified | — | — |

Notes:

- `LocalS1500Server` emulates S7-1500 protocol behavior (PDU negotiation,
  read responses) in-process; it is **not** a Siemens product and does not
  prove anything about real firmware quirks (e.g. put/get enforcement,
  DB access protection settings).
- Stricter response validation (reference-number echo, frame-length checks,
  see [docs/api-contract.md](api-contract.md)) may disconnect non-standard
  peers. Devices verified before this cycle must be re-verified.
- To add a row: run the Tier 2 (read-only) or Tier 3 (write, authorized)
  suites against the device, attach the log/report, and fill in the evidence
  link. Do not fill cells from vendor documentation or memory.
