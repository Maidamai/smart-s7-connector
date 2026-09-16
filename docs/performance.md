# Performance Claims Policy

## Status of the historical numbers ("40 ms", "hundred-millisecond level")

The README previously stated, as a project claim: single-read latency within
40 ms and batched reads at the hundred-millisecond level, measured "in the
project PLC environment".

**These figures are maintainer historical self-reports and have not been
reproduced.** No benchmark environment, tag list, hardware, or raw data for
them exists in this repository. They must not be cited as verified
performance of this library. The READMEs describe them accordingly.

## What the current tests actually assert

The only performance-related automated check is
`S1500LoopbackPerformanceTest` (Tier 1, `LocalS1500Server` on 127.0.0.1):

- 1000 continuous BYTE points through `S7Serializer.dispense` are batched
  into **3 PLC reads per cycle** at a 462-byte negotiated read window
  (PDU length 480), 12 cycles.
- Reported throughput (`pointsPerSecond`) is **> 0** — an assertion of
  liveness, not of a performance level.
- Recorded percentiles are **ordered**: p50 ≤ p95 ≤ p99 — an assertion of
  consistency, not of absolute latency.
- The window-splitting test asserts chunk boundaries/counts of a 500-byte
  read (462 + 38) — a correctness assertion.

### Limitations

- Loopback (127.0.0.1) emulation, not a real PLC or network: numbers say
  nothing about field latency, jitter, or PLC load.
- 12 cycles × 1000 points is far too small a sample for stable percentiles.
- No thresholds: the suite cannot detect a performance regression, only
  structural correctness (request counts) and liveness.
- Not a production benchmark; do not extrapolate.

## Required elements for a future formal performance report

Any future performance figures added to the README must come with a
reproducible report containing at least:

1. **Environment**: JVM version, OS, CPU, network path to the PLC
   (switched/hops), PLC model and firmware, PDU length negotiated.
2. **Warm-up**: discard a stated number of warm-up iterations; report how
   many were discarded.
3. **Sample size**: iterations and total points, enough for stable
   percentiles; report raw distributions or histograms, not only averages.
4. **Tag distribution**: the point list layout (offsets, types, DB spread) —
   request merging makes throughput highly tag-layout dependent.
5. **Version comparison**: same environment, before/after versions of the
   library, with the delta.
6. **Regression thresholds**: if figures are cited in CI, define the
   thresholds the suite enforces.
