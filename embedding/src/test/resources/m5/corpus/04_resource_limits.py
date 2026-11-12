"""M5 slice 4: the `resource` surface a program reads and writes.

Nothing here prints a limit the kernel owns, because the two interpreters are two processes and a
limit may legitimately differ between them. What is pinned is the shape of every answer, the
constants, the pair a program writes and reads back, and the words each refusal uses.
"""

import resource

print(resource.RLIMIT_CPU, resource.RLIMIT_FSIZE, resource.RLIMIT_DATA, resource.RLIMIT_STACK,
      resource.RLIMIT_CORE, resource.RLIMIT_AS, resource.RLIMIT_MEMLOCK, resource.RLIMIT_NPROC,
      resource.RLIMIT_NOFILE)
print(resource.RLIMIT_RSS == resource.RLIMIT_AS)
print(resource.RUSAGE_SELF, resource.RUSAGE_CHILDREN)
print(resource.RLIM_INFINITY == 2**63 - 1, resource.getpagesize())

# The pair is a tuple of two integers, and a finite soft limit never exceeds its hard one.
pair = resource.getrlimit(resource.RLIMIT_STACK)
print(type(pair).__name__, len(pair), type(pair[0]).__name__, type(pair[1]).__name__)
print(pair[0] <= pair[1] or pair[1] == resource.RLIM_INFINITY)

# The core limit is the one a fresh process reports as `(0, infinity)` on this platform.
print(resource.getrlimit(resource.RLIMIT_CORE))

# Writing a pair records it: read back, the answer is exactly what was written.
resource.setrlimit(resource.RLIMIT_CORE, (1234, 5678))
print(resource.getrlimit(resource.RLIMIT_CORE))


def refusal(call):
    try:
        call()
    except Exception as error:
        print(type(error).__name__, error)
    else:
        print("accepted")


refusal(lambda: resource.getrlimit(99))
refusal(lambda: resource.getrlimit())
refusal(lambda: resource.getrlimit(resource=0))
refusal(lambda: resource.setrlimit(99, (0, 0)))
refusal(lambda: resource.setrlimit(resource.RLIMIT_CORE, (5,)))
refusal(lambda: resource.setrlimit(resource.RLIMIT_CORE, (10, 5)))
refusal(lambda: resource.getrusage(99))
refusal(lambda: resource.getrusage())

# `getrusage` answers a named sixteen-tuple; the times are floats and the counts are integers.
usage = resource.getrusage(resource.RUSAGE_SELF)
print(type(usage).__name__, len(usage))
print(type(usage.ru_utime).__name__, type(usage.ru_stime).__name__)
print(usage.ru_utime >= 0.0, usage.ru_stime >= 0.0)
print(usage[2] == usage.ru_maxrss, usage.ru_nivcsw >= 0)
print(resource.getrusage(resource.RUSAGE_CHILDREN).ru_utime)

# The struct is also constructible from a sixteen-item sequence, and names its items.
built = resource.struct_rusage(tuple(range(16)))
print(type(built) is type(usage), built.ru_utime, built.ru_nivcsw, built[3])
print(built)
refusal(lambda: resource.struct_rusage((1, 2, 3)))
refusal(lambda: setattr(usage, "ru_maxrss", 1))
