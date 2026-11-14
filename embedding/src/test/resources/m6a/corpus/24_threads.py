import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import _thread
import queue
import threading
from concurrent.futures import ThreadPoolExecutor

# How this file is compared: a real thread's interleaving is not reproducible, so nothing is
# printed as it happens. Every worker appends its answer to one list under one lock, and the list
# is sorted and printed after every join. The result is a set of answers in a fixed order, and the
# two interpreters can be diffed byte for byte on stdout, stderr and exit status alike. Thread
# identifiers are compared by their properties (nonzero, distinct, not reused) rather than by
# value, because the reference's numbers come from the operating system and this runtime's from
# the JVM.
results = []
guard = threading.Lock()


def note(label, value):
    with guard:
        results.append((label, value))


# --- what the main thread says about itself before any other thread exists -------------------
main = threading.main_thread()
print("main current", threading.current_thread() is main)
print("main ident nonzero", main.ident != 0 and main.ident == _thread.get_ident())
print("main alive", main.is_alive(), main.daemon)
print("main native positive", _thread.get_native_id() > 0)
print("main main-ident", _thread._get_main_thread_ident() == main.ident)
print("main interpreter", _thread._is_main_interpreter())
print("daemon allowed", _thread.daemon_threads_allowed())
print("count", _thread._count(), threading.active_count())

# --- the two knobs threading reads from here -------------------------------------------------
print("switch interval", sys.getswitchinterval())
sys.setswitchinterval(0.001)
print("switch interval set", sys.getswitchinterval())
try:
    sys.setswitchinterval(0)
except ValueError as exc:
    print("switch zero:", exc)
sys.setswitchinterval(0.005)

print("stack size default", _thread.stack_size())
print("stack size set", _thread.stack_size(65536), _thread.stack_size())
print("stack size reset", _thread.stack_size(0), _thread.stack_size())
try:
    _thread.stack_size(1024)
except ValueError as exc:
    print("stack too small:", exc)

# --- a Thread's identity, liveness and join ---------------------------------------------------
workers = [
    threading.Thread(target=note, args=("worker", index), name="worker-%d" % index)
    for index in range(4)
]
print("unstarted ident", workers[0].ident)
for worker in workers:
    worker.start()
for worker in workers:
    worker.join()
print("joined alive", [worker.is_alive() for worker in workers])
print("joined ident distinct", len({worker.ident for worker in workers}) == 4)
print("join again", workers[0].join(0), workers[0].is_alive())
print("count after join", _thread._count(), threading.active_count())

# A thread naming itself from its target, which the reference does when no name is given.
named = threading.Thread(target=note, args=("named", 9))
print("default name", named.name)
named.start()
named.join()

# --- the two refusals a Thread makes on its own ----------------------------------------------
never = threading.Thread(target=note, args=("never", 0))
try:
    never.join()
except RuntimeError as exc:
    print("join unstarted:", exc)

twice = threading.Thread(target=note, args=("twice", 0), name="twice")
twice.start()
try:
    twice.start()
except RuntimeError as exc:
    print("start twice:", exc)
twice.join()


def join_self():
    try:
        threading.current_thread().join()
    except RuntimeError as exc:
        note("self-join", str(exc))


self_thread = threading.Thread(target=join_self, name="self")
self_thread.start()
self_thread.join()

# --- lock: a bare semaphore, with the reference's four refusals -------------------------------
lock = threading.Lock()
print("lock fresh", lock.locked(), lock.acquire(False), lock.locked())
lock.release()
print("lock free", lock.locked())
try:
    lock.release()
except RuntimeError as exc:
    print("lock release unlocked:", exc)
try:
    lock.acquire(False, 1.0)
except ValueError as exc:
    print("lock timeout on non-blocking:", exc)
try:
    lock.acquire(True, -2)
except ValueError as exc:
    print("lock negative timeout:", exc)
try:
    lock.acquire(True, float("nan"))
except ValueError as exc:
    print("lock nan timeout:", exc)
try:
    lock.acquire(True, 1e12)
except OverflowError as exc:
    print("lock overflow timeout:", exc)
print("lock entries", lock.acquire(), lock.__exit__(None, None, None), lock.locked())
# A waiter is a different thread, and the answer comes back after the holder releases.
held = threading.Lock()
held.acquire()
waited = []


def wait_for_lock():
    waited.append(held.acquire(True, 5))
    held.release()


waiting = threading.Thread(target=wait_for_lock, name="waiting")
waiting.start()
held.release()
waiting.join()
print("lock waited", waited)

# --- RLock: the owner and its count, which Condition is written over -------------------------
rlock = threading.RLock()
print("rlock fresh", rlock._is_owned(), rlock._recursion_count())
print("rlock take", rlock.acquire(), rlock.acquire(), rlock._recursion_count())
state = rlock._release_save()
# The state's second half is the owner's thread identifier, which the two interpreters cannot
# agree on; its first half is the recursion count, which they must.
print("rlock save", state[0], rlock._is_owned())
rlock._acquire_restore(state)
print("rlock restore", rlock._recursion_count(), rlock._is_owned())
rlock.release()
print("rlock one left", rlock._recursion_count())
rlock.release()
print("rlock done", rlock._is_owned())
try:
    rlock.release()
except RuntimeError as exc:
    print("rlock release unowned:", exc)
rlock._at_fork_reinit()
print("rlock reinit", rlock._is_owned(), rlock.acquire(False))
rlock.release()

# --- Condition, Event, Semaphore and Barrier --------------------------------------------------
condition = threading.Condition()
ready = []


def wait_on_condition():
    with condition:
        while not ready:
            condition.wait()
        note("condition", ready[0])


def wake_condition():
    with condition:
        ready.append("go")
        condition.notify_all()


waiting = threading.Thread(target=wait_on_condition, name="condition-waiter")
waking = threading.Thread(target=wake_condition, name="condition-waker")
waiting.start()
waking.start()
waiting.join()
waking.join()

event = threading.Event()
print("event fresh", event.is_set(), event.wait(0))


def fire():
    event.set()


fired = threading.Thread(target=fire, name="fire")
fired.start()
fired.join()
print("event after", event.is_set(), event.wait(0.1))

semaphore = threading.Semaphore(1)
print("semaphore", semaphore.acquire(False), semaphore.acquire(False))
semaphore.release()
print("semaphore released", semaphore.acquire(False))
semaphore.release()

barrier = threading.Barrier(2)
arrivals = []


def arrive():
    arrivals.append(barrier.wait(5))


first = threading.Thread(target=arrive, name="barrier-1")
second = threading.Thread(target=arrive, name="barrier-2")
first.start()
second.start()
first.join()
second.join()
print("barrier", sorted(arrivals))

# --- a queue fed by one thread and drained by the main one ------------------------------------
channel = queue.Queue()


def feed():
    for value in range(3):
        channel.put(value)


feeder = threading.Thread(target=feed, name="feeder")
feeder.start()
feeder.join()
print("queue", [channel.get(), channel.get(), channel.get()], channel.empty())

# --- _thread.local: one value per thread ------------------------------------------------------
local = _thread._local()
local.value = "main"


def touch_local():
    local.value = "worker"
    note("local", local.value)


toucher = threading.Thread(target=touch_local, name="local")
toucher.start()
toucher.join()
print("local main", local.value)

# --- _thread.start_new_thread: the raw entry point underneath Thread --------------------------
raw = threading.Event()


def raw_worker(value):
    note("raw", value)
    raw.set()


raw_ident = _thread.start_new_thread(raw_worker, ("T",))
print("raw ident nonzero", raw_ident != 0, raw.wait(5))

# --- daemon threads do not keep the interpreter alive; the ones above all ended ----------------
lazy = threading.Thread(target=note, args=("lazy", 0), name="lazy", daemon=True)
print("daemon flag", lazy.daemon)
lazy.start()
lazy.join()

# --- a thread pool, whose workers are Thread objects under a queue ----------------------------
with ThreadPoolExecutor(max_workers=2) as pool:
    print("pool map", sorted(pool.map(lambda value: value * value, range(5))))
    future = pool.submit(note, "pool", "submitted")
    print("pool submit", future.result(), future.done())

# --- the collected answers, sorted so the interleaving cannot show ----------------------------
print("results", sorted(results))
print("count final", _thread._count(), threading.active_count())
