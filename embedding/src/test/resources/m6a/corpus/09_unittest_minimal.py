"""The acceptance corpus for the slice that lands `_opcode`: unittest runs a test class of its own.

`unittest` is the hardest importer in the standard library, because it reaches through `ast`,
`inspect`, `dis`, `opcode`, `signal`, `traceback`, `difflib`, `pprint`, `dataclasses` and
`collections` before it can define a single test. Importing it here, then loading a class and
running it, measures all of those at once: the reference's answer is two tests run, none failed and
none errored, and a successful result. What this slice had to add is the C half of `opcode`
(`_opcode`), the C half of `signal` (`_signal`), `functools.cmp_to_key` — the loader sorts the
chosen test names with it — and `sys.flags.ignore_environment`, which `_colorize.can_colorize`
reads when the runner builds its result.
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
