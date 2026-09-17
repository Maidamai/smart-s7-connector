#!/usr/bin/env bash
# Consumer-side verification of a published smart-s7-connector GitHub Release.
#
# What this script does, in order:
#   1. Downloads the actual Release attachments (main JAR, its .asc, SHA256SUMS)
#      from github.com — it never builds the library from source.
#   2. Verifies the downloaded JAR against SHA256SUMS.
#   3. Tries to verify the .asc signature with the maintainer's public key
#      (pinned expected fingerprint, fetched from a public keyserver). If the
#      key is not published, this step FAILS loudly and is recorded as
#      "signature not verified" — it never silently degrades to "file exists".
#   4. Fetches the release POM: the Release POM asset if attached, otherwise
#      the raw pom.xml of the release tag (so dependency metadata is preserved;
#      no empty default POM).
#   5. Installs JAR + POM into an empty isolated Maven repository (never ~/.m2).
#   6. Runs the consumer smoke tests in this directory against that isolated
#      repository, using only the public API with an in-memory connector.
#
# What it does NOT verify: Maven Central publication, the sources/javadoc
# attachments, the S7 wire protocol, or any real PLC behavior.
#
# Usage (from this directory or anywhere):
#   ./verify-release.sh                      # defaults: v1.0.0-rc.1
#   S7_VERSION=1.0.0-rc.2 ./verify-release.sh
# Environment: REPO, S7_VERSION, TAG, KEY_FPR overridable.
# Prerequisites: bash (Git Bash on Windows), curl, sha256sum, gpg, mvn, JDK 8+.

set -u

REPO="${REPO:-Maidamai/smart-s7-connector}"
S7_VERSION="${S7_VERSION:-1.0.0-rc.1}"
TAG="${TAG:-v${S7_VERSION}}"
KEY_FPR="${KEY_FPR:-AF570E8790461C3809463B247E4B165E97DBB3DB}"

WORKDIR="$(cd "$(dirname "$0")" && pwd)"
DL="${WORKDIR}/downloads"
REPO_LOCAL="${WORKDIR}/isolated-repo"
GNUPGHOME_DIR="${WORKDIR}/gnupg"
JAR="smart-s7-connector-${S7_VERSION}.jar"
ASC="${JAR}.asc"
BASE_URL="https://github.com/${REPO}/releases/download/${TAG}"

PASS_COUNT=0
FAIL_COUNT=0

step() { echo; echo "=== $1 ==="; }

record() { # record PASS|FAIL <description>
  if [ "$1" = "PASS" ]; then
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
  printf '[%s] %s\n' "$1" "$2"
}

run() { # run <description> <command...>; records exit code
  local desc="$1"; shift
  step "$desc"
  "$@"
  local rc=$?
  if [ "$rc" -eq 0 ]; then record PASS "$desc (exit 0)"; else record FAIL "$desc (exit $rc)"; fi
  return $rc
}

echo "smart-s7-connector consumer smoke — $(date -u '+%Y-%m-%d %H:%M:%SZ')"
echo "repo=${REPO} version=${S7_VERSION} tag=${TAG} expected-signer=${KEY_FPR}"
echo "tools: $(mvn -version 2>/dev/null | sed -n 1p), $(gpg --version 2>/dev/null | sed -n 1p)"

step "reset isolated workspace"
rm -rf "${DL}" "${REPO_LOCAL}" "${GNUPGHOME_DIR}"
mkdir -p "${DL}" "${REPO_LOCAL}" "${GNUPGHOME_DIR}"
chmod 700 "${GNUPGHOME_DIR}" 2>/dev/null || true

# ---------------------------------------------------------------- downloads
step "download Release attachments"
cd "${DL}" || exit 1
for f in "${JAR}" "${ASC}" "SHA256SUMS"; do
  if curl -fSL --retry 3 -o "$f" "${BASE_URL}/$f"; then
    record PASS "downloaded $f ($(stat -c%s "$f" 2>/dev/null || wc -c < "$f") bytes)"
  else
    record FAIL "download of $f from ${BASE_URL}/$f"
  fi
done
if [ ! -f "${JAR}" ]; then
  echo "FATAL: main JAR missing; aborting."
  exit 1
fi

# ---------------------------------------------------------------- sha256
step "verify SHA256SUMS for the downloaded main JAR"
# SHA256SUMS entries use the sha256sum binary marker: "<hash> *<filename>"
if grep "${JAR}\$" SHA256SUMS > main.sums 2>/dev/null && [ -s main.sums ]; then
  if sha256sum -c main.sums; then
    record PASS "SHA256 of ${JAR} matches SHA256SUMS"
  else
    record FAIL "SHA256 of ${JAR} does NOT match SHA256SUMS"
  fi
else
  record FAIL "SHA256SUMS has no entry matching '${JAR}' (content:)"
  cat SHA256SUMS || true
fi

# ---------------------------------------------------------------- gpg
export GNUPGHOME="${GNUPGHOME_DIR}"
step "fetch signer public key ${KEY_FPR}"
KEY_IMPORTED=0
for ks in hkps://keys.openpgp.org hkps://keyserver.ubuntu.com; do
  echo "trying keyserver ${ks}"
  if gpg --batch --keyserver "$ks" --recv-keys "${KEY_FPR}" 2>&1; then
    if gpg --list-keys "${KEY_FPR}" >/dev/null 2>&1; then
      KEY_IMPORTED=1
      record PASS "public key imported from ${ks}"
      break
    fi
  fi
done
if [ "${KEY_IMPORTED}" -ne 1 ]; then
  record FAIL "signer public key ${KEY_FPR} not retrievable from public keyservers — .asc CANNOT be verified"
fi

step "verify detached signature ${ASC}"
if [ "${KEY_IMPORTED}" -eq 1 ]; then
  STATUS="$(gpg --batch --status-fd 1 --verify "${ASC}" "${JAR}" 2>&1)"
  echo "${STATUS}"
  SIG_KEY="$(printf '%s\n' "${STATUS}" | awk '/^\[GNUPG:\] GOODSIG/ {print toupper($3)}')"
  FPR_UP="$(printf '%s' "${KEY_FPR}" | tr 'a-f' 'A-F')"
  if [ -n "${SIG_KEY}" ] && [ "${FPR_UP%${SIG_KEY}}" != "${FPR_UP}" ]; then
    record PASS "GOOD signature by expected key ending ...${SIG_KEY}"
  else
    record FAIL "signature check did not produce a GOODSIG from the expected key (got: ${SIG_KEY:-none})"
  fi
else
  record FAIL "signature verification skipped: key unavailable (this is a failure, not a pass)"
fi
unset GNUPGHOME

# ---------------------------------------------------------------- pom
step "fetch release POM (Release asset preferred, tag pom.xml fallback)"
POM="smart-s7-connector-${S7_VERSION}.pom"
if curl -fSL --retry 3 -o "${DL}/${POM}" "${BASE_URL}/${POM}"; then
  record PASS "POM taken from Release asset ${POM}"
else
  echo "no POM asset on the Release; falling back to the release tag pom.xml"
  if curl -fSL --retry 3 -o "${DL}/${POM}" \
      "https://raw.githubusercontent.com/${REPO}/${TAG}/pom.xml"; then
    record PASS "POM taken from raw ${REPO}/${TAG}/pom.xml (note: attach the POM as a Release asset)"
  else
    record FAIL "could not fetch a POM for ${TAG}"
  fi
fi
grep -m1 -A1 "<artifactId>smart-s7-connector" "${DL}/${POM}" || true
if ! grep -q "<version>${S7_VERSION}</version>" "${DL}/${POM}"; then
  record FAIL "fetched POM does not declare version ${S7_VERSION} — refusing to install under wrong coordinates"
  exit 1
fi

# ---------------------------------------------------------------- install
run "install JAR+POM into isolated repository ${REPO_LOCAL}" \
  mvn -B -ntp -q org.apache.maven.plugins:maven-install-plugin:3.1.1:install-file \
    -Dfile="${DL}/${JAR}" -DpomFile="${DL}/${POM}" -Dmaven.repo.local="${REPO_LOCAL}"

# ---------------------------------------------------------------- smoke tests
step "run consumer smoke tests against the isolated repository"
cd "${WORKDIR}" || exit 1
mvn -B -ntp test -Ds7.version="${S7_VERSION}" -Dmaven.repo.local="${REPO_LOCAL}" 2>&1 | tee "${DL}/smoke-run.log"
SMOKE_RC=${PIPESTATUS[0]}
if [ "${SMOKE_RC}" -eq 0 ]; then
  record PASS "consumer smoke tests (exit 0)"
else
  record FAIL "consumer smoke tests (exit ${SMOKE_RC}) — see downloads/smoke-run.log"
fi

# ---------------------------------------------------------------- summary
step "SUMMARY"
echo "passed: ${PASS_COUNT}   failed: ${FAIL_COUNT}"
if [ "${FAIL_COUNT}" -eq 0 ]; then
  echo "RESULT: ALL STEPS PASSED for ${TAG}"
  exit 0
else
  echo "RESULT: ${FAIL_COUNT} STEP(S) FAILED for ${TAG} (see [FAIL] lines above)"
  exit 1
fi
