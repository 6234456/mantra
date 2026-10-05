"""Synthetic offline tests only. No real artifacts/keys/build/upload are used."""
import hashlib
import importlib.util
import io
from contextlib import redirect_stderr
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from dataclasses import replace
from zipfile import ZipFile, ZipInfo

SCRIPT = Path(__file__).parents[1] / "central-bundle.py"
SPEC = importlib.util.spec_from_file_location("mantra_central_bundle", SCRIPT)
bundle = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = bundle
SPEC.loader.exec_module(bundle)

VERSION = "1.0.0"
FINGERPRINT = "A" * 40
RUNTIME_DEPENDENCIES = {
    "mantra-render": [("org.apache.pdfbox", "pdfbox", "3.0.8")],
    "mantra-workbench": [("com.xqiou.mantra", "mantra-excel", VERSION),
                         ("org.apache.poi", "poi-ooxml", "5.5.1"),
                         ("com.fasterxml.jackson.core", "jackson-databind", "2.18.3")],
    "mantra-server": [("com.fasterxml.jackson.core", "jackson-databind", "2.18.3")],
    "mantra-packages": [("com.fasterxml.jackson.core", "jackson-databind", "2.18.3")],
}
DEPENDENCIES = {
    "mantra-core": [("com.xqiou", "normein-dsl", "0.3.0"), ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20")],
    "mantra-render": [("com.xqiou.mantra", "mantra-core", VERSION), ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20")],
    "mantra-excel": [("com.xqiou.mantra", "mantra-render", VERSION), ("org.apache.poi", "poi-ooxml", "5.5.1"),
                     ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20")],
    "mantra-workbench": [("com.xqiou.mantra", "mantra-core", VERSION), ("com.xqiou.mantra", "mantra-render", VERSION),
                         ("com.xqiou.mantra", "mantra-packages", VERSION),
                         ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20")],
    "mantra-server": [("com.xqiou.mantra", "mantra-workbench", VERSION), ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20")],
    "mantra-packages": [("com.xqiou.mantra", "mantra-core", VERSION), ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20")],
}


def pom(artifact):
    dependencies = "".join(
        f"<dependency><groupId>{group}</groupId><artifactId>{name}</artifactId><version>{version}</version>"
        "<scope>compile</scope></dependency>" for group, name, version in DEPENDENCIES[artifact]
    )
    dependencies += "".join(
        f"<dependency><groupId>{group}</groupId><artifactId>{name}</artifactId><version>{version}</version>"
        "<scope>runtime</scope></dependency>" for group, name, version in RUNTIME_DEPENDENCIES.get(artifact, [])
    )
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>com.xqiou.mantra</groupId>
<artifactId>{artifact}</artifactId><version>{VERSION}</version>
<name>{artifact}</name><description>Synthetic test fixture only</description>
<url>https://github.com/6234456/mantra</url>
<licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses>
<developers><developer><id>6234456</id><name>6234456</name><url>https://github.com/6234456</url></developer></developers>
<scm><connection>scm:git:https://github.com/6234456/mantra.git</connection>
<developerConnection>scm:git:ssh://git@github.com/6234456/mantra.git</developerConnection>
<url>https://github.com/6234456/mantra</url></scm><dependencies>{dependencies}</dependencies>
</project>""".encode()


def jar(member, contents=b"synthetic content; never executed"):
    result = io.BytesIO()
    with ZipFile(result, "w") as archive:
        entry = ZipInfo(member, (1980, 1, 1, 0, 0, 0))
        archive.writestr(entry, contents)
    return result.getvalue()


def test_signature(data):
    # This test-only receipt is deliberately not a valid OpenPGP packet.
    return b"-----BEGIN PGP SIGNATURE-----\n" + hashlib.sha256(data).hexdigest().encode() + b"\n-----END PGP SIGNATURE-----\n"


def test_verifier(data, signature):
    if signature != test_signature(data):
        raise bundle.BundleError("Test-only detached signature mismatch")


def status(fingerprint=FINGERPRINT, primary=None, hash_algorithm="8"):
    extra = " " + primary if primary else ""
    return f"[GNUPG:] NEWSIG\n[GNUPG:] VALIDSIG {fingerprint} 2026-10-04 1791072000 0 4 0 1 {hash_algorithm} 00{extra}\n".encode()


class BundlePreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="mantra-central-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.repository = self.root / "repository"
        for artifact in DEPENDENCIES:
            self.write(artifact, ".pom", pom(artifact))
            self.write(artifact, ".jar", jar(f"com/xqiou/mantra/{artifact}/Synthetic.class"))
            self.write(artifact, "-sources.jar", jar("Synthetic.kt"))
            self.write(artifact, "-javadoc.jar", jar("index.html", b"<html>synthetic docs</html>"))

    def directory(self, artifact="mantra-core"):
        return self.repository / "com/xqiou/mantra" / artifact / VERSION

    def file(self, suffix=".pom", artifact="mantra-core"):
        return self.directory(artifact) / f"{artifact}-{VERSION}{suffix}"

    def write(self, artifact, suffix, data):
        path = self.file(suffix, artifact)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        Path(str(path) + ".asc").write_bytes(test_signature(data))
        for name in ("md5", "sha1"):
            Path(str(path) + "." + name).write_text(hashlib.new(name, data).hexdigest() + "\n", encoding="ascii")

    def prepare(self, **options):
        return bundle.prepare_bundle(self.repository, VERSION, "0.3.0", test_verifier, FINGERPRINT, **options)

    def test_same_captured_bytes_produce_identical_144_entry_zip_with_no_publication_claim(self):
        first = self.prepare()
        second = self.prepare()
        self.assertEqual(first.data, second.data)
        self.assertFalse(first.receipt["published"])
        self.assertEqual(first.receipt["status"], "LOCAL_VERIFIED_BUNDLE_NOT_UPLOADED")
        self.assertEqual(first.receipt["publicNormeinAvailability"], "NOT_VERIFIED")
        with ZipFile(io.BytesIO(first.data)) as archive:
            names = archive.namelist()
            self.assertEqual(names, sorted(names))
            self.assertEqual(len(names), 144)
            self.assertEqual({name.split("/")[3] for name in names}, set(DEPENDENCIES))
            self.assertTrue(all(name.split("/")[4] == VERSION for name in names))
            self.assertTrue(all(entry.date_time == (1980, 1, 1, 0, 0, 0) for entry in archive.infolist()))
            self.assertFalse(any(name.endswith((".module", "maven-metadata.xml")) for name in names))
            for artifact in DEPENDENCIES:
                path = f"com/xqiou/mantra/{artifact}/{VERSION}/{artifact}-{VERSION}.pom"
                self.assertEqual(archive.read(path + ".sha256").decode().strip(), hashlib.sha256(archive.read(path)).hexdigest())

    def test_missing_signer_is_hard_failure_before_any_output(self):
        with self.assertRaisesRegex(bundle.BundleError, "No signature verifier"):
            bundle.prepare_bundle(self.repository, VERSION, "0.3.0", None, FINGERPRINT)
        self.assertFalse((self.root / "release.zip").exists())

    def test_snapshot_and_noncanonical_versions_are_refused(self):
        for value in ("0.5.0-SNAPSHOT", "1.0.0-rc.1", "01.0.0", "../1.0.0", "1.0", "1.0.0+local"):
            with self.subTest(value=value), self.assertRaises(bundle.BundleError):
                bundle.stable_version(value)

    def test_missing_signature_is_not_confused_with_unsigned_local_success(self):
        Path(str(self.file()) + ".asc").unlink()
        with self.assertRaisesRegex(bundle.BundleError, "file missing"):
            self.prepare()

    def test_signature_must_match_captured_primary_bytes(self):
        self.file().write_bytes(self.file().read_bytes().replace(b"Synthetic test fixture only", b"Changed test fixture only"))
        with self.assertRaisesRegex(bundle.BundleError, "signature mismatch"):
            self.prepare()

    def test_md5_sha1_are_required_and_wrong_checksum_is_not_silently_regenerated(self):
        checksum = Path(str(self.file()) + ".sha1")
        checksum.write_text("0" * 40)
        with self.assertRaisesRegex(bundle.BundleError, "Checksum mismatch"):
            self.prepare()
        checksum.unlink()
        with self.assertRaisesRegex(bundle.BundleError, "file missing"):
            self.prepare()

    def test_existing_optional_sha2_is_verified(self):
        Path(str(self.file()) + ".sha512").write_text("0" * 128)
        with self.assertRaisesRegex(bundle.BundleError, "Checksum mismatch"):
            self.prepare()

    def test_id_only_developer_and_wrong_gav_are_rejected(self):
        self.write("mantra-core", ".pom", pom("mantra-core").replace(b"<name>6234456</name>", b""))
        with self.assertRaisesRegex(bundle.BundleError, "name"):
            self.prepare()
        self.write("mantra-core", ".pom", pom("mantra-core").replace(b"<version>1.0.0</version>", b"<version>2.0.0</version>", 1))
        with self.assertRaisesRegex(bundle.BundleError, "Wrong POM GAV"):
            self.prepare()

    def test_pinned_kernel_and_internal_version_cannot_be_replaced_by_local_substitution(self):
        self.write("mantra-core", ".pom", pom("mantra-core").replace(b"<version>0.3.0</version>", b"<version>0.3.1</version>"))
        with self.assertRaisesRegex(bundle.BundleError, "Normein dependency"):
            self.prepare()
        self.write("mantra-core", ".pom", pom("mantra-core"))
        source = pom("mantra-render").replace(b"<artifactId>mantra-core</artifactId><version>1.0.0</version>",
                                               b"<artifactId>mantra-core</artifactId><version>0.9.0</version>")
        self.write("mantra-render", ".pom", source)
        with self.assertRaisesRegex(bundle.BundleError, "internal library"):
            self.prepare()

    def test_public_compile_scope_and_repository_overrides_are_checked(self):
        self.write("mantra-core", ".pom", pom("mantra-core").replace(b"<scope>compile</scope>", b"<scope>runtime</scope>", 1))
        with self.assertRaisesRegex(bundle.BundleError, "compile dependencies"):
            self.prepare()
        self.write("mantra-core", ".pom", pom("mantra-core").replace(b"</project>", b"<repositories></repositories></project>"))
        with self.assertRaisesRegex(bundle.BundleError, "repository overrides"):
            self.prepare()

    def test_actual_kotlin_local_dependency_management_resolves_version_without_remote_bom(self):
        stdlib = b"<groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>2.2.20</version>"
        raw = pom("mantra-core").replace(stdlib, b"<groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId>")
        local = b"<dependencyManagement><dependencies><dependency>" + stdlib + b"</dependency></dependencies></dependencyManagement>"
        raw = raw.replace(b"</project>", local + b"</project>")
        self.write("mantra-core", ".pom", raw)
        self.assertEqual(self.prepare().receipt["entryCount"], 144)
        self.write("mantra-core", ".pom", raw.replace(b"</dependencyManagement>", b"</dependencyManagement><dependencyManagement/>"))
        with self.assertRaisesRegex(bundle.BundleError, "Duplicate dependency management"):
            self.prepare()
        imported = raw.replace(stdlib + b"</dependency>", stdlib + b"<scope>import</scope><type>pom</type></dependency>")
        self.write("mantra-core", ".pom", imported)
        with self.assertRaisesRegex(bundle.BundleError, "BOM"):
            self.prepare()

    def test_workbench_public_package_types_require_compile_transitive_dependency(self):
        dependency = (b"<dependency><groupId>com.xqiou.mantra</groupId><artifactId>mantra-packages</artifactId>"
                      b"<version>1.0.0</version><scope>compile</scope></dependency>")
        for replacement in (b"", dependency.replace(b"<scope>compile</scope>", b"<scope>runtime</scope>")):
            with self.subTest(replacement=replacement):
                self.write("mantra-workbench", ".pom", pom("mantra-workbench").replace(dependency, replacement))
                with self.assertRaisesRegex(bundle.BundleError, "compile dependencies incomplete for mantra-workbench"):
                    self.prepare()

    def test_runtime_transitives_cannot_be_omitted_or_replaced_by_test_scope(self):
        for artifact, dependencies in RUNTIME_DEPENDENCIES.items():
            for group, name, version in dependencies:
                dependency = (f"<dependency><groupId>{group}</groupId><artifactId>{name}</artifactId>"
                              f"<version>{version}</version><scope>runtime</scope></dependency>").encode()
                for replacement in (b"", dependency.replace(b"<scope>runtime</scope>", b"<scope>test</scope>")):
                    with self.subTest(artifact=artifact, dependency=name, replacement=replacement):
                        self.write(artifact, ".pom", pom(artifact).replace(dependency, replacement))
                        with self.assertRaisesRegex(bundle.BundleError, "Runtime dependencies incomplete"):
                            self.prepare()
                        self.write(artifact, ".pom", pom(artifact))

    def test_active_profile_cannot_override_checked_versions_or_add_repositories(self):
        profile = (b"<profiles><profile><id>hidden-model</id>"
                   b"<activation><activeByDefault>true</activeByDefault></activation>"
                   b"<repositories><repository><id>hidden</id><url>https://example.invalid/maven</url>"
                   b"</repository></repositories><dependencies><dependency><groupId>com.xqiou</groupId>"
                   b"<artifactId>normein-dsl</artifactId><version>0.1.0</version>"
                   b"<scope>compile</scope></dependency></dependencies></profile></profiles>")
        self.write("mantra-core", ".pom", pom("mantra-core").replace(b"</project>", profile + b"</project>"))
        with self.assertRaisesRegex(bundle.BundleError, "POM profiles"):
            self.prepare()

    def test_exclusion_cannot_remove_core_kernel_transitive(self):
        dependency = b"<artifactId>mantra-core</artifactId><version>1.0.0</version><scope>compile</scope>"
        exclusion = (b"<exclusions><exclusion><groupId>com.xqiou</groupId>"
                     b"<artifactId>normein-dsl</artifactId></exclusion></exclusions>")
        self.write("mantra-render", ".pom", pom("mantra-render").replace(dependency, dependency + exclusion))
        with self.assertRaisesRegex(bundle.BundleError, "dependency exclusions"):
            self.prepare()

    def test_local_system_path_cannot_introduce_a_host_dependency(self):
        dependency = (b"<dependency><groupId>local</groupId><artifactId>host-file</artifactId><version>1.0.0</version>"
                      b"<scope>system</scope><systemPath>/private/local.jar</systemPath></dependency>")
        self.write("mantra-core", ".pom", pom("mantra-core").replace(b"</dependencies>", dependency + b"</dependencies>"))
        with self.assertRaisesRegex(bundle.BundleError, "local systemPath"):
            self.prepare()

    def test_dtd_and_utf16_do_not_bypass_xml_declaration_rejection(self):
        raw = pom("mantra-core").decode().replace("<project", '<!DOCTYPE project [<!ENTITY sample "expanded">]>\n<project', 1)
        self.write("mantra-core", ".pom", raw.encode())
        with self.assertRaisesRegex(bundle.BundleError, "external declarations"):
            self.prepare()
        self.write("mantra-core", ".pom", raw.encode("utf-16"))
        with self.assertRaisesRegex(bundle.BundleError, "UTF-8"):
            self.prepare()

    def test_seventh_library_or_extra_version_directory_artifact_is_not_bundled(self):
        (self.repository / "com/xqiou/mantra/mantra-cli").mkdir()
        with self.assertRaisesRegex(bundle.BundleError, "exactly the six"):
            self.prepare()
        (self.repository / "com/xqiou/mantra/mantra-cli").rmdir()
        (self.directory() / "unreviewed.jar").write_bytes(b"extra")
        with self.assertRaisesRegex(bundle.BundleError, "Unexpected release-directory"):
            self.prepare()

    def test_module_metadata_is_explicitly_excluded_not_silently_uploaded(self):
        (self.directory() / "mantra-core-1.0.0.module").write_bytes(b"unselected metadata")
        prepared = self.prepare()
        self.assertEqual(prepared.receipt["excludedGradleModuleMetadata"],
                         ["com/xqiou/mantra/mantra-core/1.0.0/mantra-core-1.0.0.module"])
        self.assertEqual(prepared.receipt["entryCount"], 144)

    def test_symlink_primary_is_refused(self):
        path = self.file()
        data = path.read_bytes()
        path.unlink()
        alternate = self.root / "external.pom"
        alternate.write_bytes(data)
        path.symlink_to(alternate)
        with self.assertRaisesRegex(bundle.BundleError, "Symlink"):
            self.prepare()

    def test_archive_traversal_empty_docs_and_crc_damage_are_refused(self):
        self.write("mantra-core", "-sources.jar", jar("../../escape.kt"))
        with self.assertRaisesRegex(bundle.BundleError, "Unsafe JAR"):
            self.prepare()
        self.write("mantra-core", "-sources.jar", jar("Synthetic.kt"))
        self.write("mantra-core", "-javadoc.jar", jar("README.md"))
        with self.assertRaisesRegex(bundle.BundleError, "API-HTML"):
            self.prepare()
        self.write("mantra-core", "-javadoc.jar", jar("index.html"))
        damaged = bytearray(jar("com/xqiou/mantra/Synthetic.class"))
        offset = 30 + struct.unpack_from("<H", damaged, 26)[0] + struct.unpack_from("<H", damaged, 28)[0]
        damaged[offset] ^= 1
        self.write("mantra-core", ".jar", bytes(damaged))
        with self.assertRaisesRegex(bundle.BundleError, "corrupt JAR"):
            self.prepare()

    def test_retained_decompressed_and_primary_bounds_fail_closed(self):
        for bounds, text in ((replace(bundle.Bounds(), total_input_bytes=100), "byte bound"),
                             (replace(bundle.Bounds(), primary_bytes=10), "byte bound"),
                             (replace(bundle.Bounds(), total_inflated_bytes=5), "inflated byte bound")):
            with self.subTest(bounds=bounds), self.assertRaisesRegex(bundle.BundleError, text):
                self.prepare(bounds=bounds)

    def test_old_snapshot_siblings_do_not_select_latest_file(self):
        sibling = self.directory().parent / "0.5.0-SNAPSHOT"
        sibling.mkdir()
        (sibling / "mantra-core-0.5.0-20261004.000000-1.pom").write_bytes(b"wrong")
        self.assertEqual(self.prepare().receipt["version"], VERSION)

    def test_existing_output_is_never_overwritten(self):
        output = self.root / "release.zip"
        output.write_bytes(b"reviewed old artifact")
        with self.assertRaisesRegex(bundle.BundleError, "never replaced"):
            bundle.write_new_bundle(output, self.prepare().data)
        self.assertEqual(output.read_bytes(), b"reviewed old artifact")


class SignatureStatusTest(unittest.TestCase):
    def test_expected_primary_or_subkey_relation_is_preserved(self):
        bundle.validate_status(status(), FINGERPRINT)
        bundle.validate_status(status("B" * 40, FINGERPRINT), FINGERPRINT)

    def test_wrong_key_missing_validsig_and_multiple_signatures_fail(self):
        for value in (status("B" * 40), b"[GNUPG:] GOODSIG A valid\n", status() + status()):
            with self.subTest(value=value), self.assertRaises(bundle.BundleError):
                bundle.validate_status(value, FINGERPRINT)

    def test_bad_revoked_expired_or_weak_signatures_fail_despite_validsig(self):
        for tag in ("BADSIG", "REVKEYSIG", "EXPKEYSIG", "EXPSIG", "ERRSIG", "NO_PUBKEY"):
            with self.subTest(tag=tag), self.assertRaises(bundle.BundleError):
                bundle.validate_status(status() + f"[GNUPG:] {tag} A\n".encode(), FINGERPRINT)
        with self.assertRaisesRegex(bundle.BundleError, "SHA-2"):
            bundle.validate_status(status(hash_algorithm="2"), FINGERPRINT)

    def test_cli_has_no_remote_or_skip_verification_capability(self):
        required = ["--repository", "/unused", "--version", VERSION, "--normein-version", "0.3.0",
                    "--gpgv", "/unused/gpgv", "--public-keyring", "/unused/public.gpg",
                    "--signer-fingerprint", FINGERPRINT, "--output", "/unused/release.zip"]
        error = io.StringIO()
        with redirect_stderr(error), self.assertRaises(SystemExit) as outcome:
            bundle.main(required + ["--endpoint", "https://central.sonatype.com", "--skip-verification"])
        self.assertEqual(outcome.exception.code, 2)
        self.assertIn("unrecognized arguments", error.getvalue())


if __name__ == "__main__":
    unittest.main()
