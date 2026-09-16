---
name: Feature request
about: Suggest an idea for smart-s7-connector
title: "[feature] "
labels: ["enhancement"]

---

<!-- Sensitive information warning: this is an industrial-control library.
Do not paste real plant tag tables (点表), intranet addresses or topology,
credentials, or production PLC hostnames/IPs. Mask hosts and use invented
tag addresses. -->

## Problem scenario

What are you trying to do, and what currently blocks you or forces a
workaround? Describe the use case, not just the desired API.

## Proposed solution

What would you like the library to do? If you have an API sketch, include a
small code example (placeholder hosts and invented DB/tag addresses only).

## Involved PLC models

Which PLC family/model does this concern (e.g. S7-1200, S7-1500, S7-300)?
Leave as "not specific" if it applies generally. Do not include serial
numbers or site information.

## Does this affect the write path?

Yes / No. If yes, explain which areas or operations would be written and how
the change could be bounded and tested safely (e.g. via the
`plc.allow.ranges` whitelist in the live test tiers).
