<!-- Sensitive information warning: this is an industrial-control library.
Do not paste real plant tag tables (点表), intranet addresses or topology,
credentials, or production PLC hostnames/IPs into this PR, its test data, or
its logs. Use placeholder hosts (e.g. 192.0.2.x) and invented tag addresses.
Do not commit live plc.* parameters (plc.host, plc.allowWrites,
plc.allow.ranges) into any file or workflow; they belong on your own command
line only. -->

## Summary

<!-- One or two sentences: what does this PR do? -->

## Motivation

<!-- Link the issue this addresses ("Fixes #123"). If there is no issue yet,
open one first unless this is a trivial change. -->

## Changes

<!-- Bullet list of the concrete changes. -->

## Test evidence

<!-- Paste the commands you ran and their results. At minimum:

    mvn test

Tier 2/3 live verification (if any):

    mvn -Pplc-live-it verify ...

If you ran live (Tier 2/3) tests, state the environment and authorization
status explicitly, e.g.: "Verified on an isolated test PLC (S7-1500),
read-only only" or "Verified on an isolated test PLC with writes restricted
to plc.allow.ranges=DB1:0-63; not connected to any production system."
Loopback-only verification is acceptable; say so plainly if that is all you
ran. -->

## Compatibility impact

<!-- Address each of the following:

- Java 8 compatibility: confirm the change builds with
  maven.compiler.release=8 and uses no APIs beyond Java 8.
- Public API / behavior changes: list any change to public types or runtime
  behavior visible to users.
- Documentation sync: if user-facing, confirm README.md (简体中文) and
  README_EN.md were both updated. -->

## Checklist

- [ ] `mvn test` passes locally.
- [ ] A reproduction/coverage test is included for bug fixes or new behavior.
- [ ] Java 8 compatibility is preserved.
- [ ] README.md and README_EN.md are both updated if this is user-facing.
- [ ] No real tag tables, intranet addresses, credentials, or production PLC
      hosts appear in code, tests, logs, or this PR description.
- [ ] No live `plc.*` parameters are committed in any file or workflow.
- [ ] Write-path changes (if any) include failure-path tests showing that
      unauthorized or out-of-whitelist writes are rejected.
