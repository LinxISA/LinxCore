import hashlib
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "tools" / "spec" / "check_ndf_profile.py"


VALID_REQUIREMENT = """
## Stable packet under backpressure {#IFC-IFU-CTU-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 -->

The sender MUST retain the complete packet while the receiver is not ready.
"""

VALID_VERIFICATION = """
## Backpressure retention check {#VER-IFU-CTU-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=IFC-IFU-CTU-001 -->

The executable check MUST hold ready low for three cycles and compare every
payload field on each cycle.
"""


class NdfProfileCheckerTests(unittest.TestCase):
    def make_spec(self, files: dict[str, str]) -> tempfile.TemporaryDirectory:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        (root / "ndf.yaml").write_text(
            textwrap.dedent(
                """\
                project: linxcore
                version: 0.1
                layers: L0,L1,L2,L3
                """
            ),
            encoding="utf-8",
        )
        for relative, content in files.items():
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(textwrap.dedent(content), encoding="utf-8")
        return temporary

    def run_checker(
        self, spec_root: Path, *, verify_local_references: bool = False
    ) -> subprocess.CompletedProcess[str]:
        self.assertTrue(CHECKER.is_file(), "NDF checker must exist before it can run")
        command = ["python3", str(CHECKER), str(spec_root)]
        if verify_local_references:
            command.append("--verify-local-references")
        return subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_accepts_linked_l1_contract_and_verification(self):
        temporary = self.make_spec(
            {
                "20-behavior/packet.md": VALID_REQUIREMENT,
                "50-verification/packet.md": VALID_VERIFICATION,
            }
        )
        self.addCleanup(temporary.cleanup)

        result = self.run_checker(Path(temporary.name))

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("clauses=2", result.stdout)
        self.assertIn("l1_must=1", result.stdout)
        self.assertIn("verified=1", result.stdout)
        self.assertIn("open_questions=0", result.stdout)

    def test_rejects_duplicate_clause_ids(self):
        duplicate = """
        ## Duplicate packet rule {#IFC-IFU-CTU-001}
        <!-- ndf: kind=req level=should layer=L2 status=draft since=0.1 -->

        The implementation SHOULD retain a packet.
        """
        temporary = self.make_spec(
            {
                "20-behavior/packet.md": VALID_REQUIREMENT,
                "20-behavior/duplicate.md": duplicate,
                "50-verification/packet.md": VALID_VERIFICATION,
            }
        )
        self.addCleanup(temporary.cleanup)

        result = self.run_checker(Path(temporary.name))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("duplicate clause id IFC-IFU-CTU-001", result.stderr)

    def test_rejects_dangling_cross_reference(self):
        dangling = """
        ## Packet dependency {#ARC-TOP-001}
        <!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 depends-on=IFC-MISSING-001 -->

        TOP MUST route the packet described by [[IFC-MISSING-001]].
        """
        temporary = self.make_spec({"10-architecture/top.md": dangling})
        self.addCleanup(temporary.cleanup)

        result = self.run_checker(Path(temporary.name))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("dangling reference IFC-MISSING-001", result.stderr)

    def test_rejects_missing_required_metadata(self):
        missing = """
        ## Incomplete owner clause {#ARC-TOP-001}
        <!-- ndf: kind=arch level=must status=draft since=0.1 -->

        TOP MUST only route cross-box transactions.
        """
        temporary = self.make_spec({"10-architecture/top.md": missing})
        self.addCleanup(temporary.cleanup)

        result = self.run_checker(Path(temporary.name))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing metadata layer", result.stderr)

    def test_rejects_unverified_l1_must(self):
        temporary = self.make_spec({"20-behavior/packet.md": VALID_REQUIREMENT})
        self.addCleanup(temporary.cleanup)

        result = self.run_checker(Path(temporary.name))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(
            "missing verifies edge for L1 MUST IFC-IFU-CTU-001", result.stderr
        )

    def test_rejects_malformed_reference_hash(self):
        reference = """
        ## File mechanism projection {#REF-LOCAL-001}
        <!-- ndf: kind=info level=may layer=L0 status=stable since=0.1 -->
        <!-- ndf: origin-kind=file origin=/tmp/reference origin-status=interpretation sha256=bad -->

        This projection is informative.
        """
        temporary = self.make_spec({"refs/file-reference.md": reference})
        self.addCleanup(temporary.cleanup)

        result = self.run_checker(Path(temporary.name))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("invalid sha256 for REF-LOCAL-001", result.stderr)

    def test_optional_local_reference_verification_hashes_real_file(self):
        with tempfile.TemporaryDirectory() as external_dir:
            external = Path(external_dir) / "reference.txt"
            external.write_text("reference mechanism\n", encoding="utf-8")
            digest = hashlib.sha256(external.read_bytes()).hexdigest()
            reference = f"""
            ## Local mechanism projection {{#REF-LOCAL-001}}
            <!-- ndf: kind=info level=may layer=L0 status=stable since=0.1 -->
            <!-- ndf: origin-kind=file origin={external} origin-status=interpretation sha256={digest} -->

            This projection is informative.
            """
            temporary = self.make_spec({"refs/file-reference.md": reference})
            self.addCleanup(temporary.cleanup)

            passing = self.run_checker(
                Path(temporary.name), verify_local_references=True
            )
            external.write_text("changed mechanism\n", encoding="utf-8")
            failing = self.run_checker(
                Path(temporary.name), verify_local_references=True
            )

        self.assertEqual(passing.returncode, 0, passing.stderr)
        self.assertNotEqual(failing.returncode, 0)
        self.assertIn(
            "local reference hash mismatch for REF-LOCAL-001", failing.stderr
        )

    def test_local_reference_check_reports_missing_fields_without_traceback(self):
        reference = f"""
        ## Incomplete local projection {{#REF-LOCAL-001}}
        <!-- ndf: kind=info level=may layer=L0 status=stable since=0.1 -->
        <!-- ndf: origin-kind=file origin-status=interpretation sha256={"0" * 64} -->

        This projection is incomplete.
        """
        temporary = self.make_spec({"refs/file-reference.md": reference})
        self.addCleanup(temporary.cleanup)

        result = self.run_checker(
            Path(temporary.name), verify_local_references=True
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing origin for REF-LOCAL-001", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_local_git_reference_accepts_pinned_non_head_commit(self):
        with tempfile.TemporaryDirectory() as checkout_dir:
            checkout = Path(checkout_dir)
            subprocess.run(["git", "init", "-q", str(checkout)], check=True)
            subprocess.run(
                ["git", "-C", str(checkout), "config", "user.name", "NDF Test"],
                check=True,
            )
            subprocess.run(
                [
                    "git",
                    "-C",
                    str(checkout),
                    "config",
                    "user.email",
                    "ndf-test@example.invalid",
                ],
                check=True,
            )
            source = checkout / "reference.txt"
            source.write_text("pinned\n", encoding="utf-8")
            subprocess.run(
                ["git", "-C", str(checkout), "add", "reference.txt"], check=True
            )
            subprocess.run(
                ["git", "-C", str(checkout), "commit", "-qm", "pinned"], check=True
            )
            revision = subprocess.run(
                ["git", "-C", str(checkout), "rev-parse", "HEAD"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout.strip()
            source.write_text("new head\n", encoding="utf-8")
            subprocess.run(
                ["git", "-C", str(checkout), "commit", "-qam", "new head"],
                check=True,
            )
            reference = f"""
            ## Git mechanism projection {{#REF-SNPU-001}}
            <!-- ndf: kind=info level=may layer=L0 status=stable since=0.1 -->
            <!-- ndf: origin-kind=git repository=https://example.invalid/reference.git revision={revision} checkout={checkout} origin-status=interpretation -->

            This projection is informative.
            """
            temporary = self.make_spec({"refs/git-reference.md": reference})
            self.addCleanup(temporary.cleanup)

            result = self.run_checker(
                Path(temporary.name), verify_local_references=True
            )

        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
