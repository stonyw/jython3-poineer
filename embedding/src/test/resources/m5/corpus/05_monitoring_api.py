"""M5 slice 4: the `sys.monitoring` bookkeeping `bdb` drives.

The runtime has no execution events to report, so nothing here waits for a callback. What is pinned
is every answer the registration surface gives from outside: the ids, the masks, what a second
registration replaces, and the words each refusal uses.
"""

import sys

monitoring = sys.monitoring
events = monitoring.events

print(monitoring.DEBUGGER_ID, monitoring.COVERAGE_ID, monitoring.PROFILER_ID,
      monitoring.OPTIMIZER_ID)
print(events.NO_EVENTS, events.PY_START, events.CALL, events.LINE, events.JUMP,
      events.BRANCH_LEFT, events.BRANCH_RIGHT, events.BRANCH)
print(type(monitoring.DISABLE).__name__, type(monitoring.MISSING).__name__)

# A free id is claimed by a name, and the name is what `get_tool` answers.
print(monitoring.get_tool(3))
monitoring.use_tool_id(3, "m5-tool")
print(monitoring.get_tool(3), monitoring.get_events(3))

# Registering a callback answers the one it replaced.
def callback(*args):
    return monitoring.DISABLE


print(monitoring.register_callback(3, events.LINE, callback))
print(monitoring.register_callback(3, events.LINE, callback) is callback)

# A mask is what `set_events` takes, and reading it back shows what was set.
monitoring.set_events(3, events.LINE | events.CALL)
print(monitoring.get_events(3) == events.LINE | events.CALL)

# `BRANCH` is reported as its two legs, so the mask reads back as both.
monitoring.set_events(3, events.BRANCH)
print(monitoring.get_events(3) == events.BRANCH_LEFT | events.BRANCH_RIGHT)

# A local mask is filed against one code object.
code = (lambda: None).__code__
monitoring.set_local_events(3, code, events.PY_START)
print(monitoring.get_local_events(3, code) == events.PY_START)
print(monitoring.get_local_events(3, (lambda: 1).__code__))

# Clearing keeps the name; freeing takes it and the masks away.
monitoring.clear_tool_id(3)
print(monitoring.get_tool(3), monitoring.get_events(3))
monitoring.free_tool_id(3)
print(monitoring.get_tool(3), monitoring.get_events(3))
monitoring.use_tool_id(3, "again")
print(monitoring.get_tool(3), monitoring.get_events(3))


def refusal(call):
    try:
        call()
    except Exception as error:
        print(type(error).__name__, error)
    else:
        print("accepted")


refusal(lambda: monitoring.use_tool_id(3, "twice"))
refusal(lambda: monitoring.use_tool_id(9, "over"))
refusal(lambda: monitoring.use_tool_id(3, 5))
refusal(lambda: monitoring.set_events(4, 0))
refusal(lambda: monitoring.register_callback(4, events.LINE | events.CALL, callback))
refusal(lambda: monitoring.register_callback(4, 1 << 20, None))
refusal(lambda: monitoring.set_events(4, 1 << 20))
refusal(lambda: monitoring.get_local_events(4, 5))
refusal(lambda: monitoring.get_tool())
refusal(lambda: monitoring.get_events(tool=0))
refusal(lambda: monitoring.restart_events(1))
