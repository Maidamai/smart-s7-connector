"""Offline security regression suite; never downloads or executes a release.

Canned machine-status tests cover rejection policy, temporary real GPG keys
cover crypto behavior, and command mocks prove failed trust gates never call
Maven. These tests do not require the maintainer's private key or a PLC.
"""
import contextlib
import hashlib
import importlib.util
import io
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

MODULE_PATH = Path(__file__).resolve().parents[1] / "verify_release.py"
spec = importlib.util.spec_from_file_location("verify_release", MODULE_PATH)
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
PRIMARY = "A" * 40
SUBKEY = "B" * 40
VERSION = "1.0.0-test"


def status(signing=PRIMARY, primary=None):
    suffix = " " + primary if primary else ""
    return ("[GNUPG:] NEWSIG\n[GNUPG:] GOODSIG 0123456789ABCDEF Test\n"
            "[GNUPG:] VALIDSIG %s 2026-01-01 1767225600 0 4 0 22 8 00%s\n" % (signing, suffix))


class SignaturePolicyTest(unittest.TestCase):
    def test_primary_signature(self):
        self.assertEqual((PRIMARY, PRIMARY), v.check_signature_status(0, status(), PRIMARY))

    def test_bound_signing_subkey(self):
        self.assertEqual((SUBKEY, PRIMARY), v.check_signature_status(0, status(SUBKEY, PRIMARY), PRIMARY))

    def test_wrong_primary_rejected(self):
        with self.assertRaises(v.VerificationError):
            v.check_signature_status(0, status(SUBKEY, "C" * 40), PRIMARY)

    def test_nonzero_exit_rejected_even_with_validsig(self):
        with self.assertRaises(v.VerificationError):
            v.check_signature_status(1, status(), PRIMARY)

    def test_every_negative_status_overrides_validsig(self):
        for code in sorted(v.BAD_STATUS):
            with self.subTest(code=code), self.assertRaises(v.VerificationError):
                v.check_signature_status(0, status() + "[GNUPG:] " + code + "\n", PRIMARY)

    def test_missing_or_multiple_signature_records(self):
        for value in ("", status() * 2, status().replace("GOODSIG", "IGNORED"),
                      "[GNUPG:] GOODSIG key user\n[GNUPG:] VALIDSIG incomplete\n"):
            with self.subTest(value=value), self.assertRaises(v.VerificationError):
                v.check_signature_status(0, value, PRIMARY)

    def test_weak_hash_rejected(self):
        with self.assertRaises(v.VerificationError):
            v.check_signature_status(0, status().replace("22 8 00", "22 2 00"), PRIMARY)

    def test_short_fingerprint_rejected(self):
        with self.assertRaises(v.VerificationError):
            v.check_signature_status(0, status(), "0123456789ABCDEF")


class MetadataTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="s7meta-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def test_checksum_exact_name_and_both_markers(self):
        path = self.root / "example.jar"
        path.write_bytes(b"test")
        digest = hashlib.sha256(b"test").hexdigest()
        manifest = self.root / "SHA256SUMS"
        for marker in (" *", "  "):
            manifest.write_text(digest + marker + path.name + "\n", encoding="utf-8")
            v.check_checksums(manifest, [path])

    def test_bad_missing_or_duplicate_checksum_rejected(self):
        path = self.root / "example.jar"
        path.write_bytes(b"test")
        digest = hashlib.sha256(b"test").hexdigest()
        manifest = self.root / "SHA256SUMS"
        for text in ("0" * 64 + " *example.jar\n", digest + " *exampleXjar\n",
                     (digest + " *example.jar\n") * 2, digest + " *../example.jar\n"):
            manifest.write_text(text, encoding="utf-8")
            with self.subTest(text=text), self.assertRaises(v.VerificationError):
                v.check_checksums(manifest, [path])

    def test_pom_must_match_direct_project_coordinates(self):
        path = self.root / "candidate.pom"
        good = pom_bytes()
        path.write_bytes(good)
        v.check_pom(path, VERSION)
        for raw in (good.replace(b"io.github.maidamai", b"wrong.owner"),
                    good.replace(b"<version>" + VERSION.encode() + b"</version>",
                                 b"<dependencies><dependency><version>" + VERSION.encode()
                                 + b"</version></dependency></dependencies>"),
                    b'<!DOCTYPE project [<!ENTITY x "no">]>' + good):
            path.write_bytes(raw)
            with self.subTest(raw=raw), self.assertRaises(v.VerificationError):
                v.check_pom(path, VERSION)


def pom_bytes():
    return ("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
            "<modelVersion>4.0.0</modelVersion><groupId>io.github.maidamai</groupId>"
            "<artifactId>smart-s7-connector</artifactId><version>%s</version></project>" % VERSION).encode()


def jar_bytes(javadoc=False, missing_license=False):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        if javadoc:
            archive.writestr("index.html", "Test fixture")
        else:
            for entry in v.LICENSE_ENTRIES:
                if not (missing_license and entry == "META-INF/NOTICE"):
                    archive.writestr(entry, "License test fixture, not a release")
    return output.getvalue()


class TrustGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="s7gate-")
        self.addCleanup(self.tmp.cleanup)
        self.workspace = Path(self.tmp.name) / "run"
        stem = "smart-s7-connector-" + VERSION
        self.main, self.pom = stem + ".jar", stem + ".pom"
        self.artifacts = {
            self.main: jar_bytes(), self.pom: pom_bytes(),
            stem + "-sources.jar": jar_bytes(), stem + "-javadoc.jar": jar_bytes(javadoc=True),
        }
        self.corrupt_checksum = False
        self.download_error = None
        self.verifier = v.ReleaseVerifier(self.workspace, Path(self.tmp.name) / "consumer")
        self.key = mock.patch.object(self.verifier, "import_key").start()
        self.sig = mock.patch.object(self.verifier, "verify_signature").start()
        self.maven = mock.patch.object(self.verifier, "maven_run").start()
        self.addCleanup(mock.patch.stopall)
        mock.patch.object(v, "download", side_effect=self.download).start()

    def download(self, url, path, limit=0):
        if self.download_error:
            raise self.download_error
        name = path.name
        if name == "SHA256SUMS":
            rows = [hashlib.sha256(data).hexdigest() + " *" + key for key, data in self.artifacts.items()]
            data = ("\n".join(rows) + "\n").encode()
        elif name.endswith(".asc"):
            data = b"Dummy signature (crypto mocked only in gate tests)"
        else:
            data = self.artifacts[name]
            if self.corrupt_checksum and name == self.main:
                data += b"tampering"
        path.write_bytes(data)

    def run_verifier(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.verifier.run("Maidamai/smart-s7-connector", VERSION, PRIMARY)

    def test_verified_artifacts_only_then_install_and_consume(self):
        self.run_verifier()
        self.assertEqual(4, self.sig.call_count)
        self.assertEqual(2, self.maven.call_count)
        self.assertIn("install-file", " ".join(self.maven.call_args_list[0].args[0]))
        self.assertIn("test", self.maven.call_args_list[1].args[0])

    def test_checksum_failure_never_calls_maven(self):
        self.corrupt_checksum = True
        with self.assertRaises(v.VerificationError):
            self.run_verifier()
        self.maven.assert_not_called()
        self.key.assert_not_called()

    def test_download_failure_never_calls_maven(self):
        self.download_error = v.VerificationError("missing artifact")
        with self.assertRaises(v.VerificationError):
            self.run_verifier()
        self.maven.assert_not_called()

    def test_missing_key_never_calls_maven(self):
        self.key.side_effect = v.VerificationError("key missing")
        with self.assertRaises(v.VerificationError):
            self.run_verifier()
        self.maven.assert_not_called()

    def test_bad_signature_never_calls_maven(self):
        self.sig.side_effect = v.VerificationError("wrong signature")
        with self.assertRaises(v.VerificationError):
            self.run_verifier()
        self.maven.assert_not_called()

    def test_wrong_signed_pom_never_calls_maven(self):
        self.artifacts[self.pom] = pom_bytes().replace(b"io.github.maidamai", b"other.owner")
        with self.assertRaises(v.VerificationError):
            self.run_verifier()
        self.maven.assert_not_called()

    def test_missing_license_never_calls_maven(self):
        self.artifacts[self.main] = jar_bytes(missing_license=True)
        with self.assertRaises(v.VerificationError):
            self.run_verifier()
        self.maven.assert_not_called()

    def test_failed_install_never_runs_consumer(self):
        self.maven.side_effect = v.VerificationError("install failed")
        with self.assertRaises(v.VerificationError):
            self.run_verifier()
        self.assertEqual(1, self.maven.call_count)

    def test_stale_workspace_rejected_before_any_execution(self):
        self.workspace.mkdir()
        (self.workspace / "old.jar").write_bytes(b"stale")
        with self.assertRaises(v.VerificationError):
            self.run_verifier()
        self.maven.assert_not_called()


@unittest.skipUnless(shutil.which("gpg"), "GnuPG is required for real offline signature fixtures")
class RealGpgTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="s7keys-")
        cls.root = Path(cls.tmp.name)
        cls.home = cls.root / "signer"
        cls.home.mkdir(mode=0o700)
        cls.cmd(["--pinentry-mode", "loopback", "--passphrase", "", "--quick-generate-key",
                 "Temporary Test <test@example.invalid>", "ed25519", "cert,sign", "0"])
        listing = cls.cmd(["--with-colons", "--fingerprint", "--list-keys"]).stdout
        cls.primary = next(line.split(":")[9] for line in listing.splitlines() if line.startswith("fpr:"))
        cls.cmd(["--pinentry-mode", "loopback", "--passphrase", "", "--quick-add-key",
                 cls.primary, "ed25519", "sign", "0"])
        listing = cls.cmd(["--with-colons", "--fingerprint", "--list-keys"]).stdout
        cls.subkey = [line.split(":")[9] for line in listing.splitlines() if line.startswith("fpr:")][1]
        cls.public = cls.root / "public.asc"
        cls.public.write_text(cls.cmd(["--armor", "--export", cls.primary]).stdout, encoding="utf-8")
        for name, key in (("primary", cls.primary), ("subkey", cls.subkey)):
            path = cls.root / (name + ".txt")
            path.write_bytes(b"Harmless test fixture, not a JAR\n")
            cls.cmd(["--pinentry-mode", "loopback", "--passphrase", "", "--armor", "--local-user", key + "!",
                     "--output", str(path) + ".asc", "--detach-sign", str(path)])

    @classmethod
    def cmd(cls, args, home=None, check=True):
        return subprocess.run(["gpg", "--no-options", "--homedir", str(home or cls.home), "--batch", "--yes"] + args,
                              capture_output=True, text=True, check=check, timeout=30)

    @classmethod
    def tearDownClass(cls):
        if shutil.which("gpgconf"):
            subprocess.run(["gpgconf", "--homedir", str(cls.home), "--kill", "all"], capture_output=True)
        cls.tmp.cleanup()

    def verify(self, name, expected=None, tamper=False, revoke=False):
        with tempfile.TemporaryDirectory(prefix="s7pub-") as directory:
            home = Path(directory)
            try:
                self.cmd(["--import", str(self.public)], home=home)
                if revoke:
                    certificate = (self.home / "openpgp-revocs.d" / (self.primary + ".rev")).read_text()
                    certificate = certificate[certificate.index(":-----BEGIN PGP PUBLIC KEY BLOCK-----") + 1:]
                    rev = home / "revocation.asc"
                    rev.write_text(certificate)
                    self.cmd(["--import", str(rev)], home=home)
                path = home / "message.txt"
                path.write_bytes((self.root / (name + ".txt")).read_bytes() + (b"tampered" if tamper else b""))
                result = self.cmd(["--status-fd", "1", "--verify", str(self.root / (name + ".txt.asc")), str(path)],
                                  home=home, check=False)
                return v.check_signature_status(result.returncode, result.stdout, expected or self.primary)
            finally:
                if shutil.which("gpgconf"):
                    subprocess.run(["gpgconf", "--homedir", str(home), "--kill", "all"], capture_output=True)

    def test_real_primary_signature(self):
        self.assertEqual((self.primary, self.primary), self.verify("primary"))

    def test_real_bound_signing_subkey(self):
        self.assertEqual((self.subkey, self.primary), self.verify("subkey"))

    def test_real_wrong_primary_pin(self):
        with self.assertRaises(v.VerificationError):
            self.verify("subkey", expected="C" * 40)

    def test_real_tampering(self):
        with self.assertRaises(v.VerificationError):
            self.verify("subkey", tamper=True)

    def test_real_revoked_key(self):
        with self.assertRaises(v.VerificationError):
            self.verify("subkey", revoke=True)

    def test_real_expired_key(self):
        with tempfile.TemporaryDirectory(prefix="s7exp-") as directory:
            home = Path(directory)
            try:
                fake_time = ["--faked-system-time", "1577836800"]
                self.cmd(fake_time + ["--pinentry-mode", "loopback", "--passphrase", "",
                         "--quick-generate-key", "Expired Fixture <expired@example.invalid>",
                         "ed25519", "sign", "1d"], home=home)
                listing = self.cmd(["--with-colons", "--fingerprint", "--list-keys"], home=home).stdout
                expected = next(line.split(":")[9] for line in listing.splitlines() if line.startswith("fpr:"))
                path = home / "message.txt"
                path.write_bytes(b"Harmless historically signed fixture")
                self.cmd(fake_time + ["--pinentry-mode", "loopback", "--passphrase", "", "--armor",
                         "--local-user", expected + "!", "--detach-sign", str(path)], home=home)
                result = self.cmd(["--status-fd", "1", "--verify", str(path) + ".asc", str(path)],
                                  home=home, check=False)
                with self.assertRaises(v.VerificationError):
                    v.check_signature_status(result.returncode, result.stdout, expected)
            finally:
                if shutil.which("gpgconf"):
                    subprocess.run(["gpgconf", "--homedir", str(home), "--kill", "all"], capture_output=True)


if __name__ == "__main__":
    unittest.main()
