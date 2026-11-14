import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import io
import select
import socket

# `threading` reads this switch at import time; with no second thread to inherit a context into,
# every interpreter that can run this script has it off.
print("thread inherit", sys.flags.thread_inherit_context)

# A file opened for reading is positionable, which is what the sendfile fallback asks of it.
with open(__file__, "rb") as stream:
    print("seekable", stream.seekable())
    print("tell", stream.tell())
    stream.seek(0, 2)
    print("size", stream.tell())
    stream.seek(0)
    print("head", stream.read(6))
    print("again", stream.tell())

# Both ends of a socket pair are this runtime's own descriptors, so select is asked about them
# directly: nothing is ready until the other end has written.
left, right = socket.socketpair()
print("none", select.select([], [], [], 0))
print("idle", len(select.select([left], [], [], 0)[0]))
right.send(b"x")
print("ready", len(select.select([left], [], [], 1)[0]))
print("byte", left.recv(1))
print("drained", len(select.select([left], [], [], 0)[0]))
print("writable", len(select.select([], [right], [], 0)[1]))
left.close()
right.close()
