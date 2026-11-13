"""The six date and time types, as the built-in `_datetime` defines them.

`datetime.py` in 3.14 is a thirteen-line shim over `_datetime`: `from _datetime import *`.
Everything a program constructs, compares or hashes therefore comes from `_datetime`, and
this file asks that module directly — a corpus runs without a standard library on
`sys.path`, so the shim would not be found, and its classes are the same objects anyway.

The first half is construction.  Each field check names the value it refused and the range
it wanted, so a wrong year, month, day, hour, minute, second, microsecond or fold is
reported with the reference's own message rather than a generic one.

The second half is arithmetic and ordering: the timedelta's operations (add, subtract,
negate, multiply, divide, floor-divide, modulo, absolute value) and `total_seconds`; a date
or datetime moved by a timedelta and the difference of two of them; `toordinal`,
`fromordinal`, `replace`, and the `isoformat`/`fromisoformat` pair; then comparison across
types and the hash each type derives from the fields that compare.

Two kinds of report appear below.  `report` prints the message too, and is used wherever
the two reference implementations agree word for word.  `report_named` prints only the
exception's name, for the places where CPython's C `_datetime` and its pure-Python twin
`_pydatetime` — the source this runtime runs — say the same thing in different words.
"""

import _datetime as dt


def report(label, thunk):
    """Run one expression, printing its value or the exception's name and message."""
    try:
        value = thunk()
    except Exception as exc:
        print(label, type(exc).__name__, exc)
    else:
        print(label, repr(value))


def report_named(label, thunk):
    """Run one expression, printing its value or just the exception's name.

    The C module and `_pydatetime` raise the same exception for these arguments and word
    the message differently: `timedelta` overflow, an operand whose type name is dotted
    only in C, `fromordinal(0)`, and a comparison of a date with a datetime.
    """
    try:
        value = thunk()
    except Exception as exc:
        print(label, type(exc).__name__)
    else:
        print(label, repr(value))


print(dt.__name__, dt.date.__module__, dt.datetime.__module__)
print(dt.MINYEAR, dt.MAXYEAR, repr(dt.UTC))

# --- construction: every refusal names the argument and the range ---------------------

report("year-low", lambda: dt.date(0, 1, 1))
report("year-high", lambda: dt.date(10000, 1, 1))
report("month-low", lambda: dt.date(2020, 0, 1))
report("month-high", lambda: dt.date(2020, 13, 1))
report("day-zero", lambda: dt.date(2020, 1, 0))
report("day-past-month", lambda: dt.date(2020, 2, 30))
report("day-leap", lambda: dt.date(2021, 2, 29))
report("leap-day", lambda: dt.date(2020, 2, 29))
report("year-float", lambda: dt.date(2020.0, 1, 1))
report("day-bool", lambda: dt.date(2020, 1, True))

report("hour-high", lambda: dt.time(24, 0, 0))
report("minute-high", lambda: dt.time(0, 60, 0))
report("second-high", lambda: dt.time(0, 0, 60))
report("micro-high", lambda: dt.time(0, 0, 0, 1000000))
report_named("fold-two", lambda: dt.time(0, 0, 0, 0, 2))
report("time-float", lambda: dt.time(1.5, 0))

report("datetime-hour", lambda: dt.datetime(2020, 1, 1, 24))
report("datetime-day", lambda: dt.datetime(2020, 1, 32))

report_named("tdays-overflow", lambda: dt.timedelta(days=1000000000))
report_named("tseconds-overflow", lambda: dt.timedelta(seconds=1e20))
report("tz-day", lambda: dt.timezone(dt.timedelta(days=1)))
report("tz-part-minute", lambda: dt.timezone(dt.timedelta(seconds=1)))

# --- the timedelta's value and its five operations ------------------------------------

t = dt.timedelta(days=1, hours=2, minutes=3, seconds=4, microseconds=5)
print(repr(t), t.days, t.seconds, t.microseconds, t.total_seconds())
print(repr(dt.timedelta(seconds=-1)), dt.timedelta(seconds=-1).total_seconds())
print(repr(t + t), repr(t - 2 * t), repr(-t), repr(abs(-t)))
print(repr(t * 3), repr(3 * t), repr(t / 2), repr(t // 2), repr(t % dt.timedelta(days=1)))
print(repr(divmod(t, dt.timedelta(hours=5))))
print(repr(t * 0.5), repr(t / 0.5))
print(repr(dt.timedelta(0)), bool(dt.timedelta(0)), bool(t))
print(t == dt.timedelta(days=1, hours=2, minutes=3, seconds=4, microseconds=5),
      t < t * 2, sorted([t, -t, dt.timedelta(0)])[0] is -t)
report_named("t-plus-int", lambda: t + 1)
report("t-div-zero", lambda: t / 0)

# --- dates: ordinal, arithmetic, difference, replace ----------------------------------

d = dt.date(2020, 1, 2)
print(repr(d), d.toordinal(), d.year, d.month, d.day, d.weekday(), d.isoweekday())
print(repr(dt.date.fromordinal(1)), repr(dt.date.fromordinal(d.toordinal())))
print(repr(dt.date.max.toordinal()), dt.date.max.toordinal() == dt.date(9999, 12, 31).toordinal())
print(repr(d + dt.timedelta(days=30)), repr(d - dt.timedelta(days=2)))
print(repr(dt.date(2020, 3, 1) - d), repr((dt.date(2020, 3, 1) - d).days))
print(repr(d.replace(year=2021)), repr(d.replace(month=12, day=31)))
report_named("d-minus-d-int", lambda: d - 1)
report_named("d-fromordinal-zero", lambda: dt.date.fromordinal(0))
iso = d.isocalendar()
print(d.isoformat(), type(iso).__name__, iso.year, iso.week, iso.weekday, d.ctime())
print(repr(dt.date.fromisoformat("2020-01-02")))

# --- datetimes: the same moves, with a time of day ------------------------------------

x = dt.datetime(2020, 1, 2, 3, 4, 5, 6)
y = dt.datetime(2020, 1, 3, 4, 5, 6, 7)
print(repr(x), repr(x + dt.timedelta(hours=1)), repr(x - dt.timedelta(days=1)))
print(repr(y - x), (y - x).total_seconds())
print(repr(x.replace(year=2021)), repr(x.replace(hour=0, minute=0, second=0, microsecond=0)))
print(repr(x.date()), repr(x.time()), repr(x.timetz()), tuple(x.timetuple())[:6])
print(x.isoformat(), x.isoformat(sep=" "), x.isoformat(timespec="minutes"))
print(repr(dt.datetime.fromisoformat("2020-01-02 03:04:05.000006")))
print(repr(dt.datetime.combine(d, dt.time(12, 30))))

# --- aware values: offset, conversion, formatting -------------------------------------

ist = dt.timezone(dt.timedelta(hours=5, minutes=30), "IST")
aware = dt.datetime(2020, 1, 2, 3, 4, 5, tzinfo=ist)
print(repr(ist), ist.tzname(None), ist.utcoffset(None), ist.dst(None))
print(repr(aware), aware.utcoffset(), aware.tzname(), aware.isoformat())
print(repr(aware.astimezone(dt.UTC)))
print(repr(dt.datetime(2020, 1, 2, 3, 4, 5, tzinfo=dt.UTC).astimezone(ist)))
print(x.strftime("%Y-%m-%d %H:%M:%S"), x.strftime("%A %B %d"), x.strftime("%I:%M %p %j"))
print(repr(dt.time(1, 2, 3, 4, tzinfo=dt.UTC)), dt.time(1, 2, 3).isoformat())
print(repr(dt.time.fromisoformat("01:02:03.000004")))

# --- comparison and hash ---------------------------------------------------------------

print(d == dt.date(2020, 1, 2), d < dt.date(2020, 1, 3), d != dt.date(2020, 1, 2))
print(d == dt.datetime(2020, 1, 2), dt.date(2020, 1, 1) <= d)
report_named("d-vs-datetime", lambda: d < x)

print(hash(d) == hash(dt.date(2020, 1, 2)), hash(x) == hash(dt.datetime(2020, 1, 2, 3, 4, 5, 6)))
print(hash(dt.time(1, 2, 3)) == hash(dt.time(1, 2, 3)),
      hash(t) == hash(dt.timedelta(days=1, hours=2, minutes=3, seconds=4, microseconds=5)))
print(hash(ist) == hash(dt.timezone(dt.timedelta(hours=5, minutes=30))), hash(dt.UTC) == hash(dt.timezone.utc))
print(len({d, dt.date(2020, 1, 2), x, dt.datetime(2020, 1, 2, 3, 4, 5, 6), t}))
print(sorted([y, x, dt.datetime(2019, 12, 31)]))
print(repr(dt.date(2020, 1, 1) - dt.date(2020, 1, 1)), bool(dt.date(2020, 1, 1) - dt.date(2020, 1, 1)))

# --- the classes are the module's own, and the exceptions are built in -----------------

print(type(d).__name__, type(x).__name__, type(t).__name__, type(dt.UTC).__name__)
print(issubclass(dt.datetime, dt.date), isinstance(x, dt.date), isinstance(d, dt.datetime))
print(dt.tzinfo is type(ist).__mro__[1], issubclass(type(ist), dt.tzinfo))
print(hasattr(dt.time, "strptime"), hasattr(dt.date, "strptime"), callable(dt.timedelta))
