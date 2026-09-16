# Contributing to smart-s7-connector

Thank you for considering a contribution. This is a Java library for Siemens
S7 PLC communication, and some parts of it can affect field equipment if used
carelessly, so a few rules below exist to keep both the codebase and
contributors' environments safe.

## Requirements

- JDK 8 or later.
- Maven 3.6 or later.
- No PLC is required to contribute. All default tests run locally.

## Building and testing

You can build and run the full default test suite without any PLC:

```bash
mvn test
```

To install a local snapshot for trying it out in your own project:

```bash
mvn test
mvn install
```

### The three test tiers

Tests are separated into three safety tiers. Only the first tier runs by
default; the other two are opt-in and never run in CI or on a plain
`mvn test`. Full details, properties, and safety rules are in
[docs/testing.md](docs/testing.md).

1. **Tier 1 — local deterministic tests (default).** `mvn test`. Runs all
   `*Test` classes against in-memory connectors and loopback servers
   (`127.0.0.1` only). No PLC, no writes, no network beyond loopback.
2. **Tier 2 — live read-only verification (opt-in).**
   `mvn -Pplc-live-it verify -Dplc.host=<host> -Dplc.port=102 -Dplc.rack=0 -Dplc.slot=2`.
   Runs the `*IT` classes via Failsafe. Read-only tests never issue a write
   request.
3. **Tier 3 — live write verification (explicit authorization).** Tier 2 plus
   `-Dplc.allowWrites=true -Dplc.allow.ranges=DB1:0-63`. The
   `LivePlcTestGuard` enforces the write whitelist before any connection is
   opened.

Run Tier 2/3 only against an isolated test PLC or a simulator, never against
a production control chain.

## Pull request expectations

- **Reproduction test first.** Bug fixes should come with a test that fails
  before the fix and passes after it. New features should come with tests
  covering the new behavior.
- **Keep Java 8 compatibility.** The build targets
  `maven.compiler.release=8`. Do not use language features or JDK APIs beyond
  Java 8, and do not raise dependency requirements casually.
- **Keep both READMEs in sync.** User-facing changes require updating both
  `README.md` (简体中文) and `README_EN.md` (English) with equivalent content.
- **Declare live-PLC verification honestly.** If you validated against real
  hardware, state it in the PR and include the exact command you ran, the PLC
  environment (model/firmware is helpful but optional), and an explicit
  statement of the form: *"Verified only on an isolated test PLC, not
  connected to any production system."* If you could not test on hardware,
  say so — loopback-only verification is acceptable and will be reviewed
  accordingly.
- **Write-path changes need failure-path tests.** Changes to write behavior
  (including the write authorization guard and range whitelist) must include
  tests that demonstrate the rejection path, e.g. writes without
  `plc.allowWrites=true` or outside `plc.allow.ranges` fail before any
  connection is opened.

## Security red lines

This is an industrial-control (PLC) communication library. Never include the
following in issues, PRs, commits, test data, or logs:

- Real plant tag tables / point lists (点表).
- Intranet addresses, topologies, credentials, or any real PLC host
  information. Use placeholder hosts such as `192.0.2.x` (documentation
  range) in examples and tests.
- Do not configure live `plc.*` parameters (host, `plc.allowWrites`,
  `plc.allow.ranges`) in any committed configuration, CI workflow, or test
  default. Live parameters belong on your own command line only; the
  committed defaults are intentionally empty so that external PRs can never
  make CI touch a PLC.

For vulnerability reports, do not open a public issue — see
[SECURITY.md](SECURITY.md).

## Code style

Follow the style already used in the codebase; when reviewing, we mostly look
for consistency with the existing files:

- 4-space indentation, UTF-8, no tabs.
- Classes and fields are `final` where possible; local variables and
  parameters are declared `final` where practical.
- Instance fields are referenced with the explicit `this.` prefix inside
  classes.
- Use try-with-resources for connectors (they implement `Closeable`).
- Public API types carry Javadoc.
- Netty must stay out of the public API. Application code depends only on
  `S7Connector`, `S7Serializer`, and related API types.
- Unit tests are named `*Test` (run by Surefire); live-PLC integration tests
  are named `*IT` (run by Failsafe only under the `plc-live-it` profile).
- New source files go under the existing package root
  `io.github.maidamai.s7connector`.

## License

By contributing, you agree that your contributions are licensed under the
Apache License 2.0, the license of this project.
