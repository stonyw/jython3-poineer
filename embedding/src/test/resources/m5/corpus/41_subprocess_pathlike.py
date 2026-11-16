# `fork_exec` converts its argument vector and cwd with `PyUnicode_FSConverter` (M5 slice 75).
#
# `subprocess.Popen` hands the reference's C helper the list unchanged, so each element and the
# working directory may be anything answering `__fspath__`; a value that does not is refused with
# `os.fspath`'s own sentence.  `pathlib.Path` is the case the test suite exercises.

import pathlib
import subprocess

echo = pathlib.Path("/bin/echo")


def attempt(tag, action):
    try:
        result = action()
    except Exception as exc:
        print(tag, type(exc).__name__, exc)
    else:
        print(tag, result.returncode, repr(result.stdout), repr(result.stderr))


attempt("path-in-args", lambda: subprocess.run(
    [echo, pathlib.Path("hello")], capture_output=True, text=True))
attempt("path-as-argv0", lambda: subprocess.run(
    pathlib.Path("/bin/echo"), capture_output=True, text=True))
attempt("path-as-executable", lambda: subprocess.run(
    ["echo", "y"], executable=echo, capture_output=True, text=True))
attempt("path-as-cwd", lambda: subprocess.run(
    ["/bin/echo", "z"], cwd=pathlib.Path("/tmp"), capture_output=True, text=True))
attempt("path-check-output", lambda: subprocess.check_output(
    [echo, pathlib.Path("z")], text=True))

# Non-path arguments keep `os.fspath`'s refusal, and the cwd is converted the same way.
attempt("int-arg", lambda: subprocess.run([123]))
attempt("object-arg", lambda: subprocess.run(["/bin/echo", object()]))
attempt("int-cwd", lambda: subprocess.run(["/bin/echo"], cwd=123))
