#!/usr/bin/env python3
"""Regression checks for plugin-lock integrity and credential-safe validation."""
import base64
import hashlib
import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


locker = load('locker', ROOT / 'jenkins/scripts/lock-plugins.py')
installer = load('installer', ROOT / 'jenkins/scripts/install-locked-plugins.py')
validator = load('validator', ROOT / 'jenkins/scripts/validate-pipelines.py')


class PluginIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def plugin(self, name='test', deps=''):
        path = self.root / (name + '.jpi')
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('META-INF/MANIFEST.MF', f'Short-Name: {name}\r\nPlugin-Version: 1.0\r\nJenkins-Version: 2.580.1\r\nPlugin-Dependencies: {deps}\r\n')
        return path

    def test_rejects_altered_plugin_checksum(self):
        self.plugin()
        metadata = {'plugins': {'test': {'version': '1.0', 'sha256': base64.b64encode(bytes(32)).decode()}}}
        with self.assertRaisesRegex(ValueError, 'SHA256 mismatch'):
            locker.lock_plugins(self.root, metadata)

    def test_rejects_missing_required_dependency(self):
        self.plugin(deps='missing:1.0')
        with self.assertRaisesRegex(ValueError, 'required dependency'):
            locker.lock_plugins(self.root)

    def test_optional_dependency_does_not_make_lock_incomplete(self):
        self.plugin(deps='missing:1.0;resolution:=optional')
        self.assertEqual(locker.lock_plugins(self.root)[0]['name'], 'test')

    def test_folded_manifest_values_are_preserved(self):
        fields = locker.manifest_fields(b'Plugin-Dependencies: a:1.0,\r\n b:2.0\r\n')
        self.assertEqual(fields['Plugin-Dependencies'], 'a:1.0,b:2.0')

    def test_verify_only_rejects_tampering_without_network(self):
        path = self.plugin()
        expected = hashlib.sha256(path.read_bytes()).hexdigest()
        path.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'Missing or altered'):
            installer.download({'sha256': expected}, path, True)

    def test_downloader_rejects_untrusted_origin(self):
        with self.assertRaisesRegex(ValueError, 'official Jenkins archive'):
            installer.download({'sha256': '0' * 64, 'url': 'https://example.invalid/plugin.jpi'}, self.root / 'test.jpi')

    def test_authenticated_redirect_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'redirected'):
            validator.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://example.invalid')


if __name__ == '__main__':
    unittest.main()
