# Consumer verification

Two deliberately separate checks live here. Neither uses a PLC.

## 1. Current-commit contract (development CI)

`Consumer contract and verifier` builds the current library into a new isolated
Maven repository and runs this independent Java consumer's four public-API
success-path tests. It does not download old rc.1 and does not claim anything
about published bytes. The main Java 8/17/21 CI remains unchanged.

Offline verifier regressions (Python 3.9+, GnuPG, no third-party Python packages):

```bash
python3 -m unittest discover -s consumer-smoke/tests -v
```

These generate disposable test keys, including a real signing subkey, and
exercise wrong-key, tampering, revoked/expired-key and fail-closed behavior.
They never require a maintainer private key and never execute a downloaded JAR.

## 2. Published-byte verification (release gate)

Requires Python 3.9+, GnuPG and Maven (`gpg` and `mvn` on PATH), plus JDK 8+.
The Python dependency is a development-tool requirement, not a Java library
runtime dependency. `verify-release.sh` remains a Bash/Git Bash entry point;
on Windows with Python installed as `python`, set `PYTHON=python`.

```bash
S7_VERSION=1.0.0-rc.2 bash consumer-smoke/verify-release.sh
# Explicit local PUBLIC key (not a secret/private key), with the same pinned fingerprint:
PUBKEY_FILE=/trusted/release-signing-key.asc S7_VERSION=1.0.0-rc.2 \
  bash consumer-smoke/verify-release.sh
# Direct Python entry:
python3 consumer-smoke/verify_release.py --version 1.0.0-rc.2
```

A version must be explicitly selected; these examples do not assert that rc.2
has been published. Optional environment settings are `REPO`, `KEY_FPR`,
`PUBKEY_FILE`, `GPG` and `MVN` (the last two name executable paths, not shell
command strings). `TAG` overrides are no longer used: version X always targets
`vX`. `--work-dir` must point to a new/empty evidence directory.

### Required release assets

For version X, upload these four artifacts, each with its matching `.asc`:

- `smart-s7-connector-X.jar`
- `smart-s7-connector-X.pom`
- `smart-s7-connector-X-sources.jar`
- `smart-s7-connector-X-javadoc.jar`

`SHA256SUMS` must contain exactly one matching entry for each artifact. Attach
`release-signing-key.asc` (an armored PUBLIC key), or publish the public key on
one of the configured keyservers. The complete primary fingerprint is pinned
in the verifier. Obtaining a key from the same Release does not establish a
new trust anchor: the pin must match, and key rotation requires independent
verification and an intentional reviewed pin change.

There is **no unsigned raw-tag POM fallback**. Metadata controls dependency
resolution and must be authenticated just like the binary. The old rc.1 assets
lack this signed POM, so they intentionally fail this stricter gate. The older
rc.1 consumer report remains historical evidence of its array defect, not a
claim that rc.1 passes this new verifier.

### Trust gate and execution order

1. Download all four artifacts, signatures and manifest over HTTPS.
2. Check literal, non-duplicated checksum entries (no manifest paths executed).
3. Import only public key material into a temporary isolated keyring.
4. Check GPG exit status, exactly one `GOODSIG`/`VALIDSIG`, complete pinned
   primary fingerprint and its cryptographically bound signing subkey. Reject
   error, revoked and expired status; SHA-1/MD5 signatures are not accepted.
5. Check direct project GAV in the signed POM, main/sources license resources
   and the Javadoc index. A nested dependency version is not a project version.
6. Only now install into a fresh isolated repository and run consumer tests.

Any failed prerequisite prevents installation and consumer execution. An
installation failure prevents the consumer step. Logs and downloaded public
artifacts are preserved under `downloads/verify-*`; the normal `~/.m2` and
`~/.gnupg` are not used for this verification. Temporary GPG homes are removed.
The final exit code is nonzero on failure. This validates the key material
available to the run; an offline key file cannot prove that no newer revocation
exists, so maintainers must keep published public keys current.

`Verify published release` runs on a published release or via a manual workflow
input. It does not sign, upload, publish, or use private signing credentials.
This is not Maven Central verification and not real-PLC compatibility evidence.

## 中文说明

开发 CI 验证当前提交构建的包；发布验证则重新下载指定版本的真实附件，两者不能混为一谈。
发布脚本现在需要 Python 3.9+（仅开发工具依赖）、GPG 和 Maven，必须明确指定版本。
主 JAR、POM、sources、Javadoc 都须附带签名和摘要，公钥可以随 Release 提供，但必须匹配
预先固定的完整主钥指纹；不读取或上传维护者私钥。不再接受未签名的 tag POM 回退。
摘要、签名、坐标或许可资源验证失败时，不会安装或执行下载包。现有 rc.1 缺少这些新要求的
附件，验证失败是预期行为；请保留旧版本，为后续候选构建新附件后再完整复验。
