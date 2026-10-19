"""Offline publication checks. Failure output reports paths, never credential values."""

from pathlib import Path
import plistlib
import re
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]


def source_files():
    names = subprocess.check_output(
        ["git", "-C", str(ROOT), "ls-files", "--cached", "--others", "--exclude-standard", "-z"]
    ).decode("utf-8").split("\0")
    return [ROOT / name for name in sorted(set(names) - {""}) if (ROOT / name).is_file()]


def text_files():
    result = {}
    for path in source_files():
        try:
            result[path.relative_to(ROOT).as_posix()] = path.read_text(encoding="utf-8")
        except UnicodeError:
            continue
    return result


class PublicSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = text_files()

    def test_no_private_service_addresses_or_embedded_access_tokens(self):
        patterns = (
            re.compile(r"https?://[^\s/\"']*\.monogatari\.top\b", re.I),
            re.compile(r"\bgh[pousr]_[A-Za-z0-9]{25,}\b"),
            re.compile(r"\bgithub_pat_[A-Za-z0-9_]{30,}\b"),
            re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        )
        violations = [name for name, content in self.text.items() if any(pattern.search(content) for pattern in patterns)]
        self.assertEqual(violations, [], "private address or credential marker found; values intentionally omitted")

    def test_production_trackers_have_no_literal_client_secrets(self):
        pattern = re.compile(r'\bclientSecret\s*[:=]\s*"[^"\r\n]+"')
        violations = [name for name, content in self.text.items()
                      if name.startswith(("Monogatari/", "android/app/src/main/")) and pattern.search(content)]
        self.assertEqual(violations, [], "literal OAuth secret found; values intentionally omitted")

    def test_both_default_update_feeds_are_github_only(self):
        expected = "https://github.com/Teikaraku/monogatari-Mannga/releases/latest/download/latest.json"
        for name in ("Monogatari/Core/Update/AppUpdate.swift",
                     "android/app/src/main/java/app/monogatari/android/update/AppUpdate.kt"):
            content = self.text[name]
            block = content.split("static let addresses = [", 1)[1].split("]", 1)[0] if name.endswith(".swift") else content.split("val addresses = listOf(", 1)[1].split(")", 1)[0]
            self.assertEqual(re.findall(r'https://[^"\s]+', block), [expected], name)

    def test_project_and_build_asset_links_use_the_current_repository_name(self):
        old_link = "https://github.com/Teikaraku/" + "monogatari-reader"
        violations = [name for name, content in self.text.items() if old_link in content]
        self.assertEqual(violations, [], "old repository links are allowed only as bare-name download aliases")

    def test_private_oauth_settings_are_empty_and_mapped_into_plist(self):
        settings = self.text["Monogatari/Monogatari.xcconfig"]
        info = plistlib.loads((ROOT / "Monogatari/Info.plist").read_bytes())
        for service in ("Bangumi", "Shikimori"):
            for suffix, variable in (("ID", "ID"), ("Secret", "SECRET")):
                key = f"{service.upper()}_CLIENT_{variable}"
                lines = re.findall(rf"^{key}\s*=([^\r\n]*)$", settings, re.M)
                self.assertEqual(lines, [""], f"{key} must have an empty public default")
                self.assertEqual(info[f"{service}Client{suffix}"], f"$({key})")

    def test_private_configuration_and_signing_files_are_ignored(self):
        paths = ("Monogatari/Private.xcconfig", "android/keystore.properties", "release.jks",
                 "private.p12", "private.p8", "private.pfx", "profile.mobileprovision", ".env")
        result = subprocess.run(["git", "-C", str(ROOT), "check-ignore", "--stdin", "-z"],
                                input="\0".join(paths) + "\0", text=True, capture_output=True, check=True)
        self.assertEqual(set(result.stdout.split("\0")[:-1]), set(paths))

    def test_private_build_settings_are_excluded_from_xcode_target_membership(self):
        project = self.text["Monogatari.xcodeproj/project.pbxproj"]
        exceptions = re.findall(r"membershipExceptions\s*=\s*\((.*?)\);", project, re.S)
        self.assertTrue(any("Private.xcconfig," in block and "Monogatari.xcconfig," in block for block in exceptions))

    def test_no_runtime_private_data_or_signing_material_in_source_snapshot(self):
        allowed_certificate_fixture = "android/app/src/androidTest/assets/browser-test.p12"
        forbidden = {".sqlite", ".sqlite3", ".db", ".jks", ".keystore", ".p12", ".p8", ".pfx", ".mobileprovision", ".provisionprofile", ".aib", ".apk", ".ipa"}
        violations = [str(path.relative_to(ROOT)) for path in source_files()
                      if path.suffix.lower() in forbidden and path.relative_to(ROOT).as_posix() != allowed_certificate_fixture]
        self.assertEqual(violations, [])


if __name__ == "__main__":
    unittest.main()
