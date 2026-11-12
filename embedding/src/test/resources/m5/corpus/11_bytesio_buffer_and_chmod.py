"""M5 slice 9: `BytesIO.getbuffer()` and `os.chmod()`.

The pickler's framing asks the stream it writes into for a view of what it holds; the temporary-file
machinery asks the platform to narrow a file's permissions. Both used to be missing attributes, and
both are measured here against the reference interpreter: the view reports the octets written before
the call, and `chmod` leaves a mode that `stat` reads back unchanged.
"""

import sys

# The reference interpreter's own library, as the other corpora read it, so both sides reach the same
# `stat` and `os` modules.
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import io
import os
import stat

# The view is read before anything else touches the stream: the reference refuses to grow a buffer
# that still has an export of it, and this runtime's view is a snapshot of the bytes written so far.
stream = io.BytesIO()
stream.write(b"frame")
view = stream.getbuffer()
print("view", len(view), view.tobytes())
print("value", stream.getvalue())

# A fixed name beside the working directory rather than a temporary one: `tempfile` needs a real
# descriptor out of `os.open`, which this runtime does not yet hand out.
path = os.path.join(os.getcwd(), "m5_chmod_probe")
with open(path, "wb") as handle:
    handle.write(b"x")
os.chmod(path, 0o600)
print("600", oct(stat.S_IMODE(os.stat(path).st_mode)))
os.chmod(path, 0o644)
print("644", oct(stat.S_IMODE(os.stat(path).st_mode)))

# A missing path is refused the way the reference refuses it, not as a Java failure.
try:
    os.chmod(os.path.join(os.getcwd(), "m5_chmod_gone"), 0o600)
except FileNotFoundError as error:
    print("missing", type(error).__name__, error.errno)

os.remove(path)
