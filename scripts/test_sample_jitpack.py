"""Network-free contract checks for the opt-in sample smoke."""
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class SampleJitPackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        (self.root / 'scripts').mkdir()
        script = ROOT / 'scripts/check-sample-jitpack.sh'
        if script.exists():
            shutil.copy(script, self.root / 'scripts')
        wrapper = self.root / 'gradlew'
        wrapper.write_text(
            '#!/usr/bin/env python3\nimport json, os, sys\n'
            'open(os.environ["CALL_LOG"], "w").write(json.dumps([os.getcwd(), sys.argv[1:]]))\n'
            'print("wrapper stdout")\nprint("wrapper stderr", file=sys.stderr)\n'
            'sys.exit(int(os.environ.get("GRADLE_EXIT", "0")))\n'
        )
        wrapper.chmod(0o755)
        self.log = self.root / 'call.json'
        self.env = dict(os.environ, CALL_LOG=str(self.log))
        self.env.pop('GRADLE_EXIT', None)

    def run_smoke(self, *args):
        return subprocess.run(['bash', str(self.root / 'scripts/check-sample-jitpack.sh'), *args],
                              cwd=self.root / 'scripts', env=self.env, text=True, capture_output=True)

    def expected(self, version, *args):
        return [str(self.root), [f'-PsampleJitPackVersion={version}', ':app:verifySampleJitPack',
                ':sample-feature:testDebugUnitTest', ':app:testDebugUnitTest',
                ':app:assembleDebug', ':app:assembleRelease', *args]]

    def test_default_and_forwarded_version_and_arguments(self):
        for args, version, forwarded in [((), 'v1.0.0-alpha04', ()),
                                          (('v9.8.7', '--offline', '-x', 'lint'), 'v9.8.7', ('--offline', '-x', 'lint'))]:
            with self.subTest(args=args):
                result = self.run_smoke(*args)
                self.assertEqual((result.returncode, result.stdout, result.stderr),
                                 (0, 'wrapper stdout\n', 'wrapper stderr\n'))
                self.assertEqual(json.loads(self.log.read_text()), self.expected(version, *forwarded))

    def test_failure_propagates(self):
        self.env['GRADLE_EXIT'] = '7'
        result = self.run_smoke()
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (7, 'wrapper stdout\n', 'wrapper stderr\n'))
        self.assertEqual(json.loads(self.log.read_text()), self.expected('v1.0.0-alpha04'))

    def test_default_substitutions_are_guarded(self):
        settings = (ROOT / 'settings.gradle.kts').read_text()
        app = (ROOT / 'app/build.gradle.kts').read_text()
        feature = (ROOT / 'sample-feature/build.gradle.kts').read_text()
        self.assertIn('if (sampleJitPackVersion == null)', settings)
        self.assertIn('includeBuild("hilt-trace-gradle-plugin")', settings)
        self.assertIn('if (sampleJitPackVersion == null)', app)
        self.assertIn('project(":hilt-coroutines-runtime")', app)
        self.assertIn('project(":hilt-trace-compiler")', feature)


if __name__ == '__main__':
    unittest.main()
