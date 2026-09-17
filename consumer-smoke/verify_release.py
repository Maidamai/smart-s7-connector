#!/usr/bin/env python3
"""Fail-closed verification of published artifacts. Python 3.9+, stdlib only.

No Maven command runs until EVERY artifact and its POM pass integrity,
signer, coordinate and resource checks. A pinned primary fingerprint also
accepts its valid signing subkeys (GnuPG doc/DETAILS, VALIDSIG).
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

DEFAULT_FPR = "AF570E8790461C3809463B247E4B165E97DBB3DB"
LICENSE_ENTRIES = (
    "META-INF/LICENSE", "META-INF/NOTICE", "META-INF/LICENSE_LIBNODAVE.txt",
    "META-INF/THIRD_PARTY_NOTICES.md", "META-INF/licenses/LGPL-2.0.txt",
)
BAD_STATUS = {
    "BADSIG", "ERRSIG", "NO_PUBKEY", "NODATA", "FAILURE", "ERROR",
    "EXPSIG", "EXPKEYSIG", "REVKEYSIG", "KEYEXPIRED", "SIGEXPIRED", "KEYREVOKED",
}


class VerificationError(RuntimeError):
    pass


def fingerprint(value):
    value = value.upper()
    if not re.fullmatch(r"(?:[0-9A-F]{40}|[0-9A-F]{64})", value):
        raise VerificationError("Expected a complete primary-key fingerprint (40 or 64 hex digits)")
    return value


def check_signature_status(returncode, status, expected):
    """Only machine-status output belongs here; stderr is NOT parsed."""
    expected = fingerprint(expected)
    records = [line.split()[1:] for line in status.splitlines()
               if line.startswith("[GNUPG:] ")]
    if returncode != 0:
        raise VerificationError("GPG verification exited with status %s" % returncode)
    if any(row and row[0] in BAD_STATUS for row in records):
        raise VerificationError("GPG reported a failed, expired or revoked signature/key")
    good = [row for row in records if row and row[0] == "GOODSIG"]
    valid = [row for row in records if row and row[0] == "VALIDSIG"]
    if len(good) != 1 or len(valid) != 1 or len(valid[0]) not in (10, 11):
        raise VerificationError("Expected exactly one GOODSIG and one complete VALIDSIG")
    row = valid[0]
    signing = fingerprint(row[1])
    primary = fingerprint(row[10]) if len(row) == 11 else signing
    if primary != expected:
        raise VerificationError("Signature is not bound to the pinned primary fingerprint")
    # OpenPGP hash IDs: SHA-256/384/512/224. Do not accept SHA-1 or MD5.
    if row[8] not in {"8", "9", "10", "11"} or row[9] not in {"00", "01"}:
        raise VerificationError("Unsupported signature hash or signature class")
    return signing, primary


def check_checksums(manifest, paths):
    """Match literal basenames; never execute paths supplied by a checksum file."""
    expected = {path.name: path for path in paths}
    found = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-fA-F]{64}) [ *](.+)", line)
        if match and match[2] in expected:
            name = match[2]
            if name in found:
                raise VerificationError("Duplicate checksum entry: " + name)
            found[name] = match[1].lower()
    for name, path in expected.items():
        if name not in found:
            raise VerificationError("Missing checksum entry: " + name)
        with path.open("rb") as stream:
            digest = hashlib.sha256()
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != found[name]:
            raise VerificationError("SHA256 mismatch: " + name)


def check_pom(path, version):
    raw = path.read_bytes()
    if len(raw) > 1024 * 1024 or b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
        raise VerificationError("POM is oversized or contains a DTD/entity declaration")
    root = ET.fromstring(raw)
    ns = "{http://maven.apache.org/POM/4.0.0}"
    if root.tag != ns + "project":
        raise VerificationError("Unexpected Maven POM root/namespace")
    for field, wanted in (("groupId", "io.github.maidamai"),
                          ("artifactId", "smart-s7-connector"), ("version", version)):
        nodes = root.findall(ns + field)
        if len(nodes) != 1 or (nodes[0].text or "").strip() != wanted:
            raise VerificationError("Release POM has missing/incorrect project " + field)
    if root.findtext(ns + "packaging", "jar").strip() != "jar":
        raise VerificationError("Release POM is not a JAR project")


def check_resources(main, sources, javadoc):
    for path in (main, sources):
        with zipfile.ZipFile(path) as archive:
            for name in LICENSE_ENTRIES:
                if archive.namelist().count(name) != 1:
                    raise VerificationError("Missing/duplicate license resource in " + path.name + ": " + name)
                info = archive.getinfo(name)
                if not 0 < info.file_size <= 1024 * 1024 or not archive.read(name).strip():
                    raise VerificationError("Empty/oversized license resource: " + name)
    with zipfile.ZipFile(javadoc) as archive:
        if archive.namelist().count("index.html") != 1:
            raise VerificationError("Javadoc JAR lacks a unique index.html")


class HttpsOnlyRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not newurl.startswith("https://"):
            raise VerificationError("Refusing non-HTTPS download redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(url, path, limit=100 * 1024 * 1024):
    if not url.startswith("https://"):
        raise VerificationError("Release downloads must use HTTPS")
    request = urllib.request.Request(url, headers={"User-Agent": "smart-s7-consumer-verifier"})
    opener = urllib.request.build_opener(HttpsOnlyRedirect())
    temporary = path.with_name(path.name + ".part")
    try:
        with opener.open(request, timeout=30) as response, temporary.open("wb") as output:
            size = 0
            while True:
                chunk = response.read(65536)
                if not chunk:
                    break
                size += len(chunk)
                if size > limit:
                    raise VerificationError("Oversized download: " + path.name)
                output.write(chunk)
        if not size:
            raise VerificationError("Empty download: " + path.name)
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


class ReleaseVerifier:
    def __init__(self, workspace, consumer, gpg="gpg", maven="mvn"):
        self.workspace = Path(workspace)
        self.consumer = Path(consumer).resolve()
        self.gpg, self.maven = gpg, maven
        self._home = None

    def gpg_run(self, args, timeout=45):
        return subprocess.run(
            [self.gpg, "--no-options", "--homedir", str(self._home), "--batch", "--no-tty"] + args,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout,
        )

    def import_key(self, expected, key_file, base):
        if not key_file:
            asset = self.workspace / "release-signing-key.asc"
            try:
                download(base + "/release-signing-key.asc", asset, 1024 * 1024)
                key_file = asset
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
                # A public keyserver is a fallback, never a different trust anchor.
        if key_file:
            data = Path(key_file).read_bytes()
            if (b"-----BEGIN PGP PUBLIC KEY BLOCK-----" not in data
                    or b"PRIVATE KEY BLOCK" in data or len(data) > 1024 * 1024):
                raise VerificationError("PUBKEY_FILE must contain an ASCII-armored PUBLIC key only")
            result = self.gpg_run(["--import", str(Path(key_file).resolve())])
            if result.returncode != 0:
                raise VerificationError("Public-key import failed")
        else:
            for server in ("hkps://keys.openpgp.org", "hkps://keyserver.ubuntu.com"):
                try:
                    result = self.gpg_run(["--keyserver", server, "--keyserver-options", "timeout=20",
                                           "--recv-keys", expected])
                    if result.returncode == 0:
                        break
                except subprocess.TimeoutExpired:
                    continue
            else:
                raise VerificationError("Pinned public key unavailable; supply trusted PUBKEY_FILE (never a private key)")
        listing = self.gpg_run(["--with-colons", "--fingerprint", "--list-keys", expected])
        if listing.returncode != 0:
            raise VerificationError("Imported key does not contain the pinned fingerprint")
        primary_fprs, next_primary = [], False
        for line in listing.stdout.splitlines():
            fields = line.split(":")
            if fields[0] == "pub":
                next_primary = True
            elif fields[0] == "sub":
                next_primary = False
            elif fields[0] == "fpr" and next_primary:
                primary_fprs.append(fields[9].upper())
                next_primary = False
        if expected not in primary_fprs:
            raise VerificationError("KEY_FPR must pin the primary key, not an unrelated key/subkey")

    def verify_signature(self, path, expected):
        result = self.gpg_run(["--no-auto-key-retrieve", "--status-fd", "1", "--verify",
                               str(path) + ".asc", str(path)])
        (self.workspace / (path.name + ".gpg-status.log")).write_text(result.stdout, encoding="utf-8")
        (self.workspace / (path.name + ".gpg-diagnostics.log")).write_text(result.stderr, encoding="utf-8")
        signing, primary = check_signature_status(result.returncode, result.stdout, expected)
        print("[PASS] signature %s; signing=%s primary=%s" % (path.name, signing, primary))

    def maven_run(self, args, log_name):
        with (self.workspace / log_name).open("w", encoding="utf-8") as output:
            result = subprocess.run([self.maven, "-B", "-ntp"] + args,
                                    cwd=str(self.workspace), stdout=output,
                                    stderr=subprocess.STDOUT, timeout=1200)
        if result.returncode != 0:
            raise VerificationError("Maven failed (exit %d); see %s" % (result.returncode, log_name))

    def run(self, repo, version, expected, key_file=None):
        if not re.fullmatch(r"[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+", repo):
            raise VerificationError("Invalid owner/repository")
        if not re.fullmatch(r"[0-9][A-Za-z0-9._-]{0,79}", version):
            raise VerificationError("Invalid version; use a release version without the v prefix")
        expected = fingerprint(expected)
        self.workspace.mkdir(parents=True, exist_ok=True)
        if any(self.workspace.iterdir()):
            raise VerificationError("Workspace must be empty; refusing stale artifacts or repository reuse")
        base = "https://github.com/%s/releases/download/v%s" % (repo, version)
        stem = "smart-s7-connector-" + version
        main, pom, sources, javadoc = [self.workspace / (stem + suffix)
                                       for suffix in (".jar", ".pom", "-sources.jar", "-javadoc.jar")]
        artifacts = (main, pom, sources, javadoc)
        print("[INFO] repository=%s tag=v%s expected-primary=%s" % (repo, version, expected))
        print("[INFO] evidence directory: " + str(self.workspace))
        for path in artifacts:
            download(base + "/" + path.name, path)
            download(base + "/" + path.name + ".asc", Path(str(path) + ".asc"), 1024 * 1024)
        manifest = self.workspace / "SHA256SUMS"
        download(base + "/SHA256SUMS", manifest, 1024 * 1024)
        check_checksums(manifest, artifacts)
        print("[PASS] all four artifact checksums (including the POM)")
        # Short isolated homedir avoids GPG socket path limits. Never touch ~/.gnupg.
        home = tempfile.mkdtemp(prefix="s7gpg-")
        self._home = Path(home)
        os.chmod(home, 0o700)
        try:
            self.import_key(expected, key_file, base)
            for path in artifacts:
                self.verify_signature(path, expected)
        finally:
            # Cleanup is best effort: a stuck agent or files locked by
            # Windows must not flip an already-decided verification into a
            # failure, and a gate failure must still propagate.
            if shutil.which("gpgconf"):
                try:
                    subprocess.run(["gpgconf", "--homedir", home, "--kill", "all"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
                except subprocess.SubprocessError:
                    print("[WARN] gpg-agent shutdown did not finish; continuing")
            shutil.rmtree(home, ignore_errors=True)
        check_pom(pom, version)
        check_resources(main, sources, javadoc)
        print("[PASS] exact POM GAV, main/sources license resources and Javadoc index")
        # Trust gate: no Maven invocation is permitted above this point.
        local_repo = self.workspace / "isolated-repo"
        common = ["-Dmaven.repo.local=" + str(local_repo)]
        self.maven_run(common + ["org.apache.maven.plugins:maven-install-plugin:3.1.1:install-file",
                                 "-Dfile=" + str(main), "-DpomFile=" + str(pom),
                                 "-Dsources=" + str(sources), "-Djavadoc=" + str(javadoc)], "install.log")
        print("[PASS] verified JAR and POM installed into a fresh isolated repository")
        self.maven_run(common + ["-f", str(self.consumer / "pom.xml"), "-Ds7.version=" + version,
                                 "clean", "test"], "consumer.log")
        print("[PASS] consumer success-path tests; RESULT: ALL STEPS PASSED")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default=os.getenv("S7_VERSION"), help="Release version (or S7_VERSION)")
    parser.add_argument("--repo", default=os.getenv("REPO", "Maidamai/smart-s7-connector"))
    parser.add_argument("--key-fpr", default=os.getenv("KEY_FPR", DEFAULT_FPR))
    parser.add_argument("--public-key", default=os.getenv("PUBKEY_FILE"), help="Trusted armored PUBLIC key file")
    parser.add_argument("--work-dir", help="New/empty directory for evidence and isolated repository")
    args = parser.parse_args()
    if not args.version:
        parser.error("Pass --version or set S7_VERSION; no implicit old candidate is selected")
    for command in (os.getenv("GPG", "gpg"), os.getenv("MVN", "mvn")):
        if not shutil.which(command):
            parser.error("Missing executable: " + command)
    consumer = Path(__file__).resolve().parent
    root = consumer / "downloads"
    root.mkdir(exist_ok=True)
    workspace = Path(args.work_dir).resolve() if args.work_dir else Path(tempfile.mkdtemp(prefix="verify-", dir=root))
    try:
        ReleaseVerifier(workspace, consumer, os.getenv("GPG", "gpg"), os.getenv("MVN", "mvn")).run(
            args.repo, args.version, args.key_fpr, args.public_key)
    except (VerificationError, OSError, ValueError, ET.ParseError, zipfile.BadZipFile,
            subprocess.SubprocessError) as error:
        print("[FAIL] %s\n[SKIP] No later stages executed. RESULT: FAILED" % error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
