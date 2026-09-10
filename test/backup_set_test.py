"""Exercise the shared pure set verifier without credentials or cloud access."""
import ast
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

source = Path(__file__).parents[1] / 'green/src/resources/io/github/getcolors/clickhouse/tools/ansible/clickhouse-backup.py'
parsed = ast.parse(source.read_text())
function = next(node for node in parsed.body if isinstance(node, ast.FunctionDef) and node.name == 'verify_set')
namespace = {}
exec(compile(ast.Module(body=[function], type_ignores=[]), str(source), 'exec'), namespace)
verify = namespace['verify_set']

class BackupSetTest(unittest.TestCase):
    def setUp(self):
        self.metadata = b'<config><uuid>test-id</uuid><contents><file><name>one</name><size>10</size><checksum>abc</checksum></file><file><name>two</name><size>10</size><checksum>abc</checksum><data_file>one</data_file></file><file><name>empty</name><size>0</size></file></contents></config>'
        self.stats = dict(num_files=3, total_size=20, num_entries=1,
                          uncompressed_size=10 + len(self.metadata), compressed_size=10 + len(self.metadata))
        self.listed = [{'Key': 'set/one', 'Size': 10}, {'Key': 'set/.backup', 'Size': len(self.metadata)}]

    def check(self, metadata=None, listed=None, stats=None):
        return verify(metadata or self.metadata, self.listed if listed is None else listed, 'set/', stats or self.stats, 'test-id')

    def test_deduplicated_and_empty_logical_files_reconcile_exactly(self):
        evidence = self.check()
        self.assertEqual((evidence['num_files'], evidence['total_size']), (3, 20))
        self.assertEqual((evidence['object_count'], evidence['object_bytes']), (2, 10 + len(self.metadata)))

    def test_missing_extra_wrong_size_or_equal_total_wrong_key_refused(self):
        for listed in [self.listed[:1], self.listed + [{'Key': 'set/unexpected', 'Size': 0}],
                       [{'Key': 'set/one', 'Size': 9}, self.listed[1]],
                       [{'Key': 'set/swapped', 'Size': 10}, self.listed[1]]]:
            with self.subTest(listed=listed), self.assertRaises(AssertionError):
                self.check(listed=listed)

    def test_logical_physical_and_identity_mismatches_refused(self):
        for key in self.stats:
            with self.subTest(key=key), self.assertRaises(AssertionError):
                self.check(stats={**self.stats, key: self.stats[key] + 1})
        with self.assertRaises(AssertionError):
            verify(self.metadata, self.listed, 'set/', self.stats, 'wrong-id')
        with self.assertRaises(AssertionError):
            self.check(metadata=self.metadata.replace(b'<data_file>one', b'<data_file>../one'))

if __name__ == '__main__':
    unittest.main()
