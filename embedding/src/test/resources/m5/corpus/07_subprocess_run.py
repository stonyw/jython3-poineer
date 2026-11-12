"""M5 slice 5: `subprocess` really runs a process, through `_posixsubprocess.fork_exec`.

Every command is named by an absolute path so the answer does not depend on the caller's PATH, and
nothing here prints a pid, an address or a message the two interpreters could word differently. The
last case is the error protocol: a program that does not exist comes back through the error pipe as
`FileNotFoundError`, and only its type and errno are printed.
"""

import sys

# The reference interpreter's own library, exactly as the other corpora do it, so both sides read
# the same `subprocess.py`.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import subprocess

echo = subprocess.run(["/bin/echo", "hello", "world"], capture_output=True)
print("echo", echo.returncode, echo.stdout, echo.stderr)

failure = subprocess.run(
    ["/bin/sh", "-c", "printf 'one\\ntwo\\n'; printf 'warn' >&2; exit 7"],
    capture_output=True)
print("failure", failure.returncode, failure.stdout, failure.stderr)

here = subprocess.run(["/bin/pwd"], capture_output=True, cwd="/tmp")
print("cwd", here.returncode, here.stdout)

environment = subprocess.run(
    ["/bin/sh", "-c", "printf '%s' \"$JY3_SLICE5\""], capture_output=True,
    env={"PATH": "/bin:/usr/bin", "JY3_SLICE5": "ok"})
print("env", environment.returncode, environment.stdout)

piped = subprocess.run(["/bin/cat"], input=b"through the pipe\n", capture_output=True)
print("stdin", piped.returncode, piped.stdout, piped.stderr)

checked = subprocess.run(["/bin/echo", "checked"], capture_output=True, check=True)
print("checked", checked.returncode, checked.stdout)

try:
    subprocess.run(["/bin/sh", "-c", "exit 9"], capture_output=True, check=True)
except subprocess.CalledProcessError as error:
    print("called-process-error", error.returncode, error.stdout, error.stderr)

try:
    subprocess.run(["/definitely/not/a/program"], capture_output=True)
except FileNotFoundError as error:
    print("missing", type(error).__name__, error.errno)

process = subprocess.Popen(["/bin/sh", "-c", "printf polled"], stdout=subprocess.PIPE)
print("popen", process.wait(), process.stdout.read())
