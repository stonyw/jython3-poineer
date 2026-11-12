"""The acceptance corpus for the slice that lands `_ast`: unittest runs a test class of its own.

This file is not registered in `m6aCorpus()` yet. `import unittest` reaches `traceback`, then
`_colorize`, `dataclasses` and `inspect`, and `ast.py` stops at `from _ast import *`; nothing below
can run until that module exists. It is kept here so the reference's answer -- two tests run, both
passing -- is written down next to the corpus that will have to produce it.
"""

import io
import unittest


class Arithmetic(unittest.TestCase):

    def test_adds(self):
        self.assertEqual(1 + 2, 3)

    def test_refuses(self):
        with self.assertRaises(ZeroDivisionError):
            1 / 0


result = unittest.TextTestRunner(stream=io.StringIO(), verbosity=0).run(
    unittest.defaultTestLoader.loadTestsFromTestCase(Arithmetic))
print(result.testsRun, len(result.failures), len(result.errors), result.wasSuccessful())
