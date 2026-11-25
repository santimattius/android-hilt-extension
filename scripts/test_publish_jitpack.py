"""Contract checks for the JitPack entry point; no network or Gradle required."""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


class PublishJitPackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        (self.root / "hilt-trace-gradle-plugin").mkdir()
        source = Path(__file__).with_name("publish-jitpack.sh")
        if source.exists():
            shutil.copy(source, self.root / "scripts/publish-jitpack.sh")
        wrapper = self.root / "gradlew"
        wrapper.write_text(
            "#!/usr/bin/env python3\n"
            "import json, os, sys\n"
            "with open(os.environ['CALL_LOG'], 'a') as log:\n"
            "    log.write(json.dumps(sys.argv[1:]) + '\\n')\n"
            "sys.exit(int(os.environ.get('GRADLE_EXIT', '0')))\n"
        )
        wrapper.chmod(0o755)
        self.log = self.root / "calls.jsonl"
        self.env = dict(os.environ, VERSION="v1.2.3-alpha01", CALL_LOG=str(self.log))
        self.env.pop("GRADLE_EXIT", None)

    def run_publish(self, *args):
        return subprocess.run(
            ["bash", str(self.root / "scripts/publish-jitpack.sh"), *args],
            cwd=self.root / "hilt-trace-gradle-plugin",
            env=self.env, text=True, capture_output=True,
        )

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_publishes_same_four_artifacts_and_plugin_marker(self):
        result = self.run_publish("-Dmaven.repo.local=/isolated/repository")
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, "", ""))
        common = [
            "-PlibraryGroupId=com.github.santimattius.android-hilt-extension",
            "-PlibraryVersion=v1.2.3-alpha01", "-Philtext.skipSigning=true",
            "-Dmaven.repo.local=/isolated/repository",
        ]
        self.assertEqual(self.calls(), [
            [":hilt-trace-runtime:publishToMavenLocal",
             ":hilt-coroutines-runtime:publishToMavenLocal",
             ":hilt-trace-compiler:publishToMavenLocal", *common],
            ["-p", "hilt-trace-gradle-plugin", "publishToMavenLocal", *common],
        ])

    def test_only_alpha_tags_publish(self):
        for version in ("v1.2.3", "1.2.3", "v1.2.3-beta01", "v1.2.3-rc01",
                        "main-SNAPSHOT", "abc1234", "not-an-alpha-tag"):
            with self.subTest(version=version):
                self.env["VERSION"] = version
                result = self.run_publish()
                self.assertEqual(
                    (result.returncode, result.stdout, result.stderr),
                    (2, "", "JitPack only publishes alpha version tags (for example v1.2.3-alpha01).\n"),
                )
                self.assertEqual(self.calls(), [])

    def test_alpha_tag_without_v_prefix_publishes(self):
        self.env["VERSION"] = "1.2.3-alpha.1"
        result = self.run_publish()
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, "", ""))
        self.assertEqual(len(self.calls()), 2)
        for call in self.calls():
            self.assertIn("-PlibraryVersion=1.2.3-alpha.1", call)

    def test_central_job_excludes_prereleases_and_qualified_tags(self):
        workflow = (Path(__file__).resolve().parents[1] /
                    ".github/workflows/publish-maven-central.yml").read_text()
        self.assertIn(
            "  publish:\n"
            "    if: >-\n"
            "      ${{ !github.event.release.prerelease &&\n"
            "          !contains(github.event.release.tag_name, '-') }}\n",
            workflow,
        )

    def test_missing_version_does_not_publish(self):
        self.env.pop("VERSION")
        result = self.run_publish()
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (2, "", "VERSION must be a non-empty JitPack tag or commit.\n"))
        self.assertEqual(self.calls(), [])

    def test_gradle_failure_stops_publication(self):
        self.env["GRADLE_EXIT"] = "7"
        result = self.run_publish()
        self.assertEqual((result.returncode, result.stdout, result.stderr), (7, "", ""))
        self.assertEqual(len(self.calls()), 1)


if __name__ == "__main__":
    unittest.main()
