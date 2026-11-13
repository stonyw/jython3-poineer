# The command line's option surface, read from inside the child it starts (M5 slice 35).
#
# Every case starts a child with the interpreter that is running this file and asks that child what
# `sys` says about its own start-up. Only answers the two interpreters agree on are printed, and no
# path, pid or address appears, so the outputs compare byte for byte. `-W` is exercised both as the
# list it fills and as the effect it has: `-W error` turns a warning into an exception and `-W ignore`
# keeps one off the screen.

import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import os
import subprocess

ENV = os.environ.copy()
ENV["PYTHONPATH"] = STD

WARN = "import sys; print(sys.warnoptions)"

FLAGS = (
    "import sys; f = sys.flags; "
    "print(f.dont_write_bytecode, f.no_site, f.no_user_site, f.quiet)"
)

RAISE = ("import warnings\n"
         "try:\n"
         "    warnings.warn('x')\n"
         "except UserWarning:\n"
         "    print('raised')\n")

IGNORE = ("import warnings\n"
          "warnings.warn('x')\n"
          "print('quiet')\n")

def child(tag, argv):
    done = subprocess.run([sys.executable] + argv, capture_output=True, env=ENV)
    print(tag, done.returncode, done.stdout.decode().strip())

child("-Wall", ["-Wall", "-c", WARN])
child("-W error -W ignore::DeprecationWarning",
      ["-W", "error", "-W", "ignore::DeprecationWarning", "-c", WARN])
child("-Wignore", ["-Wignore", "-c", WARN])
child("-Wd -X utf8",
      ["-Wd", "-X", "utf8", "-c", "import sys; print(sys.warnoptions, sys.flags.utf8_mode)"])
child("-Xutf8", ["-Xutf8", "-c", "import sys; print(sys.flags.utf8_mode, sorted(sys._xoptions))"])
child("-X dev",
      ["-X", "dev", "-c",
       "import sys; print(sys.warnoptions, sys.flags.dev_mode, sorted(sys._xoptions))"])
child("-X faulthandler", ["-X", "faulthandler", "-c", "import sys; print(sorted(sys._xoptions))"])
child("-B -S -s -q", ["-B", "-S", "-s", "-q", "-c", FLAGS])
child("-W error acts", ["-W", "error", "-c", RAISE])
child("-W ignore acts", ["-W", "ignore", "-c", IGNORE])
