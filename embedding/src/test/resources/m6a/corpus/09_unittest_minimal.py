"""The acceptance corpus for the slice that lands `_ast`: unittest runs a test class of its own.

This file is not registered in `m6aCorpus()` yet. `_ast` now exists, so `import unittest` gets past
`ast.py`; the next stop is `opcode`, whose C half `_opcode` this runtime does not answer, reached from
`inspect` through `dis`. Nothing below can run until that import exists. It is kept here so the
reference's answer -- two tests run, both passing -- is written down next to the corpus that will
have to produce it.
"""
import sys

sys.path.insert(
    0,
    "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/"
    "Versions/3.14/lib/python3.14",
)

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
