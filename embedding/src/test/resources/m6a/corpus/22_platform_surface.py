import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import os
import signal
import stat

# asyncio.base_events saves and restores the origin-tracking depth around run_forever, and
# signal.Signals is built out of the upper-case names of _signal. Both are read back here.
print("depth default", sys.get_coroutine_origin_tracking_depth())
sys.set_coroutine_origin_tracking_depth(7)
print("depth set", sys.get_coroutine_origin_tracking_depth())
sys.set_coroutine_origin_tracking_depth(0)
print("depth reset", sys.get_coroutine_origin_tracking_depth())

print("nsig", signal.NSIG)
print("valid", sorted(int(number) for number in signal.valid_signals()))
print("names", signal.SIGHUP, signal.SIGTERM, signal.SIGUSR2)
print("enum", signal.Signals.SIGINT.name, int(signal.Signals.SIGINT))

# A pipe's descriptor is the one this runtime hands out itself, so its blocking mode and its
# stat result are answerable.
read_end, write_end = os.pipe()
print("blocking", os.get_blocking(read_end))
os.set_blocking(read_end, False)
print("blocking after", os.get_blocking(read_end))
print("fifo", stat.S_ISFIFO(os.fstat(read_end).st_mode))
os.write(write_end, b"abc")
print("pending", os.fstat(read_end).st_size)
print("read", os.read(read_end, 3))
os.close(read_end)
os.close(write_end)
