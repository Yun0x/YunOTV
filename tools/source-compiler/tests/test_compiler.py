import importlib.util
import pathlib
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('compiler', pathlib.Path(__file__).resolve().parents[3] / 'scripts/yuno-source-compiler.py')
compiler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compiler)


class CompilerSafetyTest(unittest.TestCase):
    def test_relative_resource_and_md5_keep_original_base(self):
        self.assertEqual('https://raw.githubusercontent.com/a/b/main/jar/spider.jar;md5;abc',
            compiler.resolve('https://raw.githubusercontent.com/a/b/main/config.json', './jar/spider.jar;md5;abc'))

    def test_atomic_exchange_keeps_complete_previous_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            old, new = root / 'current', root / 'staging'
            old.mkdir(); new.mkdir()
            (old / 'bundle').write_text('old'); (new / 'bundle').write_text('new')
            compiler.atomic_publish(new, old)
            self.assertEqual('new', (old / 'bundle').read_text())
            self.assertEqual('old', (new / 'bundle').read_text())

    def test_failed_validation_does_not_touch_previous_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            old = root / 'app/src/main/assets/yunotv'
            old.mkdir(parents=True)
            (old / 'yunotv-bundle.json').write_text('last-good')
            with self.assertRaises(FileNotFoundError):
                compiler.publish(root)
            self.assertEqual('last-good', (old / 'yunotv-bundle.json').read_text())

    def test_root_download_failure_preserves_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            old = root / 'app/src/main/assets/yunotv'
            old.mkdir(parents=True)
            (old / 'yunotv-bundle.json').write_text('last-good')
            (root / 'sources.txt.json').write_text('{"sources":[{"url":"https://example.test/config.json"}]}')
            with patch.object(compiler, 'download', side_effect=OSError('blocked')):
                with self.assertRaisesRegex(ValueError, 'ROOT_CONFIG_FAILED'):
                    compiler.fetch(root)
            self.assertEqual('last-good', (old / 'yunotv-bundle.json').read_text())

    def test_malicious_host_rejected(self):
        with self.assertRaisesRegex(ValueError, 'PRIVATE_ADDRESS'):
            compiler.public_url('http://127.0.0.1/private')


if __name__ == '__main__':
    unittest.main()
