# Release-verifier hardening (2026-09-17)

Base commit: `1637043b0fc171a64d187d904fef80841a3285ce`.
Scope: round-four G1 (verification gates), G3 (independent consumer CI), and
G2 release preparation. No PLC core/API changes, hardware tests, history
rewrites, signing-key changes, version changes or artifact publication.

## Implemented

- Replace `GOODSIG` key-ID suffix comparison with GPG exit-status and
  structured `VALIDSIG` checks against the full pinned primary fingerprint;
  accept a valid bound signing subkey, reject wrong/expired/revoked signatures.
- Fail closed before Maven invocation on download, hash, signer, signed POM
  GAV or artifact-resource failures. Stop before consumer execution if install
  fails. Temporary isolated directories cannot silently reuse old artifacts.
- Authenticate the release POM as well as binary/sources/Javadoc. Require
  signed Release POM assets rather than fetching unauthenticated tag metadata.
- Allow an armored public-key asset/local public-key file as well as keyserver
  retrieval. The fingerprint remains the trust anchor; private keys are never
  required by the verifier or checked into this repository.
- Add offline regression tests and a separate current-commit consumer CI job.
  Add a published-artifact workflow that runs independently after publication.

## Locally verified in this change

Environment: Linux, Python 3.13.5, GnuPG 2.4.7. Command:

```bash
python3 -m unittest discover -s consumer-smoke/tests -v
```

Result: **26 tests passed**. Real temporary keys cover direct-primary and
bound-subkey signatures, wrong primary pin, modified message, revoked key,
and expired key. Other tests use explicit command mocks to assert that Maven
is not called after failed trust checks, and that a failed installation does
not run the consumer. These mocks are not presented as real Maven runs.
Temporary test private keys are generated under disposable directories and
removed; they are not maintainer keys and are not included in any commit.

The editing environment could not resolve github.com and had no Maven
executable, so it did not independently rerun the project's Java suite or
fetch/sign actual Release artifacts. GitHub Actions results for this change
must be read from the PR checks; this note does not predeclare them successful.

## Remaining release actions

After review, build the next candidate from a fixed tag, using the maintainer's
normal local/protected signing setup. Attach main/sources/Javadoc/POM, their
signatures, SHA256SUMS and the public key (or a working public-key retrieval
route). Preserve rc.1's tag and bytes. Run `Verify published release` against
the actual new uploaded assets and inspect the resulting logs. Do not label
that release verified until this gate passes. Maven Central publication,
real-PLC compatibility and adoption remain separate evidence requirements.

Protocol reference for machine output:
`https://github.com/gpg/gnupg/blob/master/doc/DETAILS` (`GOODSIG`, `VALIDSIG`,
`EXPKEYSIG`, `REVKEYSIG`). This change does not decide the library's licensing.
