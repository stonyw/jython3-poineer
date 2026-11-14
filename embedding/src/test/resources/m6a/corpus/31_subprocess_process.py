# Popen and run over a real child process: the three streams, the exit status, input and the option
# surface.  Both sides spawn their own interpreter through `sys.executable`, so the argv[0] is the
# only thing that differs and it is never printed.
import sys

# The reference interpreter's own library, exactly as the other corpora do it, so both sides read
# the same `subprocess.py`.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import subprocess

# 1. The simplest path: a child of this very interpreter, its stdout dropped into ours.
print("run:", subprocess.run([sys.executable, "-c", "print(1)"]).returncode)

# 2. The two streams are kept apart, in bytes.
binary = subprocess.run(
    [sys.executable, "-c", "import sys;print('out');print('err', file=sys.stderr)"],
    capture_output=True)
print("capture:", binary.returncode, binary.stdout, binary.stderr)

# 3. text=True decodes both streams with the locale's encoding.
text = subprocess.run([sys.executable, "-c", "print('h\u00e9llo')"],
                      capture_output=True, text=True)
print("text:", text.returncode, text.stdout)

# 4. input is written to the child's stdin, which is a pipe in this mode.
echo = subprocess.run(
    [sys.executable, "-c", "import sys;sys.stdout.write(sys.stdin.read())"],
    input="na\u00efve\n", text=True, capture_output=True)
print("input:", echo.returncode, echo.stdout)

# 5. A non-zero exit status is returned as a negative signal or a positive code; `check` turns it
#    into CalledProcessError without touching the streams.
print("exit:", subprocess.run([sys.executable, "-c", "raise SystemExit(3)"]).returncode)
try:
    subprocess.run([sys.executable, "-c", "raise SystemExit(3)"], check=True)
except subprocess.CalledProcessError as error:
    print("check:", error.returncode, error.cmd[1:])

# 6. timeout kills the child and reports the command, without the executable path.
try:
    subprocess.run([sys.executable, "-c", "import time;time.sleep(10)"], timeout=0.5)
except subprocess.TimeoutExpired as error:
    print("timeout:", error.timeout, error.cmd[1:], error.stdout, error.stderr)

# 7. Popen's pid is a live number; wait() yields the status and poll() repeats it.
child = subprocess.Popen([sys.executable, "-c", "import sys;sys.exit(7)"])
print("pid:", isinstance(child.pid, int), child.pid > 0)
print("wait:", child.wait(), child.poll())

# 8. The switches the harness glues on (`-Wd`, `-X utf8`) and the ones it splits reach a child.
for switches in (["-Wd"], ["-X", "utf8"], ["-E"], ["-s"], ["-u"], ["-B"]):
    finished = subprocess.run([sys.executable, *switches, "-c", "print('ok')"],
                              capture_output=True, text=True)
    print("option:", switches, finished.returncode, finished.stdout)
