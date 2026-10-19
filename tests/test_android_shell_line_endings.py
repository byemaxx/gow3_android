import importlib.util
import io
import json
from pathlib import Path
import tarfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    'validate_release_apk', ROOT / 'tools/android/apk/validate_release_apk.py')
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class AndroidShellLineEndingsTest(unittest.TestCase):
    def validate_script(self, script):
        name = 'gow3port/run-bionic.sh'
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode='w:gz') as archive:
            entry = tarfile.TarInfo(name)
            entry.mode = 0o755
            entry.size = len(script)
            archive.addfile(entry, io.BytesIO(script))
        manifest = json.dumps({'application_id': validator.APPLICATION_ID,
                               'required_paths': [{'path': name}]}).encode()
        return validator.validate_payload_tree(output.getvalue(), manifest)

    def test_accepts_android_lf_script(self):
        self.validate_script(b'#!/system/bin/sh\nset -eu\nexit 0\n')

    def test_rejects_windows_crlf_before_delivery(self):
        with self.assertRaisesRegex(validator.ApkError, 'contains CR/CRLF'):
            self.validate_script(b'#!/system/bin/sh\r\nset -eu\r\nexit 0\r\n')

    def test_rejects_mixed_line_endings(self):
        with self.assertRaisesRegex(validator.ApkError, 'contains CR/CRLF'):
            self.validate_script(b'#!/system/bin/sh\nset -eu\r\nexit 0\n')


if __name__ == '__main__':
    unittest.main()
