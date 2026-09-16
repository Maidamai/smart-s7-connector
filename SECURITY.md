# Security Policy

## Scope

This security policy covers vulnerabilities in the smart-s7-connector library
itself, including:

- Defects in the S7 protocol implementation (TPKT / COTP / S7 PDU framing,
  parsing, and encoding errors that could lead to crashes, desynchronization,
  or unintended behavior).
- Memory safety issues in buffer handling (index/bounds errors, incorrect
  length handling in read/write paths).
- Resource leaks (connections, Netty channels, event loops, threads) that can
  be triggered through normal or malformed protocol input.
- Bypasses or weaknesses in the write authorization guard
  (`LivePlcTestGuard`) and the `plc.allow.ranges` whitelist used by the live
  test tiers.
- Serialization defects in `S7Serializer` that can be triggered by crafted
  PLC responses.

## Out of scope

The following are explicitly out of scope for this project:

- Incident response for safety events at production facilities. This library
  is a communication component; it is not a safety system. If you have a
  safety incident at a plant, follow your organization's own emergency and
  safety procedures.
- Penetration testing of, or incident response for, user systems and
  networks.
- General operational security of deployed control systems (network
  segmentation, firewalling port 102, PLC hardening, etc.). Applications
  built on this library must implement their own permission checks, range
  validation, and operation auditing for write operations, as noted in the
  README disclaimer.

## Reporting a vulnerability

Please do **not** report vulnerabilities through public GitHub issues.

Preferred channel:

- Use GitHub's **Private Vulnerability Reporting** on this repository
  (`Security` tab → `Report a vulnerability`).

If private vulnerability reporting is not enabled for this repository, please
reach out to the repository owner via GitHub (**Maidamai**) through the
contact options available on their GitHub profile. No email address is
published for this project; please do not guess or fabricate one.

When reporting, please include:

- Library version (and whether you built it from source).
- A minimal reproduction (code or protocol trace) if possible.
- The affected code path (read, write, serialization, transport, guard).

## Response expectations

Support is **best effort**. This is a small, maintainer-driven project, and
there is **no SLA and no guaranteed response time**. We will look into
credible reports as soon as we reasonably can, but please do not rely on this
project for time-critical security response.

## A note to reporters working with industrial control systems

Please keep sensitive industrial-control information out of any public
channel, including public issues: no real plant tag tables (点表), no intranet
addresses or network topology, no credentials, no production PLC hostnames or
IP addresses. Redact or mask hosts in any screenshots, logs, or traces you
share, even in private reports where practical.
