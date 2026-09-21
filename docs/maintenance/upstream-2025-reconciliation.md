# Upstream 2025 reconciliation

Date: 2026-09-21

This note reconciles the three substantive changes merged into
`s7connector/s7connector` in August 2025 with the current
`Maidamai/smart-s7-connector` implementation. The goal is to keep useful
upstream fixes while preserving smart-s7-connector's independently hardened
transport, protocol-validation and serializer contracts.

## Summary

| Upstream change | Upstream commit | smart-s7-connector status | Decision |
| --- | --- | --- | --- |
| #160 STRUCT-array serialization | `acb782d` | Covered and hardened independently | No direct cherry-pick |
| #164 DATE_AND_TIME milliseconds / validation | `fcc7662` | Missing before this reconciliation | Ported with regression tests |
| #165 semaphore acquire/release fix | `3505732` | Covered and hardened independently | No direct cherry-pick |

## #160 — STRUCT arrays

Upstream #160 taught `BeanParser` to parse an array's component type for
`S7Type.STRUCT`, use the nested block size for repeated elements, and added a
STRUCT-array round-trip test.

smart-s7-connector already covers the same capability through a stricter shared
layout model:

- `BeanParser.entryCoverageInBytes(...)` derives STRUCT element stride from
  the nested bean's parsed block size.
- `BeanEntry.elementStride` is reused by both coverage calculation and
  element positioning, avoiding separate size/offset rules.
- Recursive STRUCT layouts are rejected explicitly.
- `S7ArrayLayoutTest.structArrayAdvancesByTheNestedBlockSize()` locks the
  three-element round trip and byte positions.

Result: **covered**, with broader layout validation than the upstream change.
No direct cherry-pick is needed.

## #164 — DATE_AND_TIME milliseconds and BCD handling

Before this reconciliation, smart-s7-connector still carried the older
`DateAndTimeConverter` implementation: millisecond read/write code was left as
TODO comments, BCD conversion used string parsing, and the documented S7
`DATE_AND_TIME` year range was not enforced.

The upstream change is therefore relevant and has been ported, adapted to the
smart-s7-connector package and exception model:

- decode the three millisecond BCD digits from bytes 6 and 7;
- encode milliseconds back into bytes 6 and 7 while preserving the weekday
  nibble;
- use direct BCD arithmetic for the date/time fields;
- reject insert years outside 1990..2089;
- use a non-lenient `Calendar` while decoding;
- add deterministic JUnit 5 regression coverage for BCD values, millisecond
  extraction, round trip, year bounds and short buffers.

Result: **ported in this maintenance branch**.

## #165 — semaphore correctness

Upstream #165 corrected an erroneous semaphore operation and moved release
paths into `finally` blocks so failures cannot leave the connection lock
unbalanced.

smart-s7-connector already implements the same invariant and extends it:

- read, write and exec-read paths acquire before the exchange and release in
  `finally`;
- interruption restores the thread interrupt flag and is surfaced as
  `IOException` where the public transport contract requires it;
- response parsing is bounded by the actual received frame length;
- write responses use explicit result validation rather than treating an
  ambiguous response as success.

Result: **covered and hardened independently**. No direct cherry-pick is needed.

## Maintenance rule going forward

The two projects have diverged in package coordinates, transport and public API,
so upstream commits should not be blindly cherry-picked. New upstream changes
should be classified as one of:

1. already covered by an equivalent smart-s7-connector invariant;
2. portable bug fix to adapt with regression tests;
3. behavior specific to the legacy compatibility line;
4. conflicting change that needs an explicit design decision.

This keeps smart-s7-connector synchronized with useful community fixes without
pretending the two codebases are still drop-in-identical.
