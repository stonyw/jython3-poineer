# `asyncio` 最小可运行子集与 `_asyncio` 普查（M6-a 切片 14）

本片的目标只有一条：让 `import asyncio` 到 `asyncio.run(main())` 真的跑起来，并把
`test_asyncio` 从「完全没起跑」推进到「起跑并给出 ran／ok／fail／err／skip」。事件循环沿用
参考 3.14 的纯 Python 实现（`asyncio/base_events.py` 与 `asyncio/unix_events.py`），运行时只补
它们向 JVM 问的那几个问题；不新写调度器，也不假装支持 IO。

回归入口：

- `embedding/src/test/resources/m6a/corpus/20_asyncio_subset.py` —— 与参考 3.14.10 的 stdout 与
  stderr **逐字节对拍**（`run` 的返回值与异常、`call_soon`／`call_later`／`call_at` 的顺序、
  `sleep` 的计时只比结构不比墙钟、`gather` 的顺序与 `return_exceptions`、`Future`／`Task` 的
  `done`／`result`／`exception`／`cancel`／`add_done_callback`、`get_running_loop`／
  `get_event_loop` 内外两侧的报错句、`new_event_loop`／`set_event_loop`／`run_until_complete`／
  `close`）。
- `embedding/src/test/java/org/python/util/Jy3LauncherTest.java` 的 `m6aCorpus` —— 上面这个文件
  由 JUnit 交给 jy3 执行并与参考对拍。

---

## 1. 普查：`_asyncio` 不是必需层

参考 3.14 的 `asyncio` 把加速层写成**可选**的：

- `asyncio/futures.py:472` 与 `asyncio/tasks.py:381` 都是 `try: import _asyncio` / `except
  ImportError:` 的句式，失败时落到 `_PyFuture`／`_PyTask`（同文件里的纯 Python 类）。
- `asyncio/events.py`、`base_events.py` 从不直接问 `_asyncio`；`_asyncio` 出现在 `sys.modules`
  里时只是被 `Task`／`Future` 的别名指向。

jy3 没有 `_asyncio`，`import _asyncio` 按普通导入失败，两个模块于是选中纯 Python 实现——这正是
本片要的那条路。**结论一：不需要实现 `_asyncio` 的 `Future`／`Task`／`_get_running_loop` 等 C
对象**；`isinstance(fut, asyncio.Future)`、`asyncio.ensure_future`、`loop.create_task` 全部由
纯 Python 类成立。`test_asyncio` 里以 `hasattr(tasks, '_CTask')` 为条件的 C 层用例会自行 skip，
这是如实的「没有这一层」，不是失败。

## 2. 断点：缺的是 `sys` 的三个函数与 `sys.version` 的形状

`asyncio` 的纯 Python 层在 jy3 上依次问了四件事，前三件已经能答，后两件是断点：

| 问题 | 谁问 | 修前 |
|---|---|---|
| `selectors`／socket 自管道 | `base_events.py` 的 `_make_self_pipe`、`_add_reader` | 可用（`select`／`socket` 已由 M6-a 切片 2 起提供） |
| `time.monotonic` | `base_events.BaseEventLoop.time` | 可用，`call_later`／`call_at` 的定时源就是它 |
| `sys.get_asyncgen_hooks`／`sys.set_asyncgen_hooks` | `base_events.py:648`／`:650`（`run_forever` 的 setup）与 `:670`（cleanup 还原） | **`AttributeError: module 'sys' has no attribute 'get_asyncgen_hooks'`** |
| `sys.is_finalizing` | `unix_events.py:72`（`_UnixSelectorEventLoop` 的 `close`） | 缺失 |
| `sys.version` 的形状 | `platform._sys_version`，被 `test.test_asyncio.utils` → `wsgiref.simple_server` 间接问到 | `ValueError: failed to parse CPython sys.version`，`test_asyncio` **一例都没跑** |

修法（`runtime/src/main/java/org/python/core/`）：

- `PyRuntime` 注册 `get_asyncgen_hooks`／`set_asyncgen_hooks`／`is_finalizing`。前两者的参数面按
  clinic 的 `($module, /, firstiter=None, finalizer=None)` 逐句对齐：最多两个参数、位置与关键字
  重复时 `argument for function given by name ('firstiter') and position (1)`、未知关键字
  `this function got an unexpected keyword argument 'bad'`、非可调用
  `callable firstiter expected, got int`。
- `PyAsyncGenHooks` 是 `get_asyncgen_hooks()` 返回的 structseq，形状照 `PyVersionInfo`：`len` 为 2、
  `n_fields`／`n_sequence_fields`／`n_unnamed_fields`、`__match_args__`、`__replace__`、字段只读
  （`readonly attribute`）、`repr` 为 `asyncgen_hooks(firstiter=None, finalizer=None)`、
  `__reduce__` 交回 `(类型, (元组, {}))`。
- `sys.version` 从 `3.14.0 (Jython3 on the JVM)` 改成
  `3.14.0 (Jython3 on the JVM) [Java]`。`platform._sys_version` 的正则要求结尾是一对方括号里的
  compiler 名，没有它 `platform.python_implementation()` 就抛 `ValueError`，而
  `test.test_asyncio.utils` 在 import 时就调它。

## 3. 顺带修掉的运行时崩溃：`PyDeque` 的游标

`asyncio.as_completed` 用 `deque` 装已完成的 future，`for f in todo` 走到
`PyDeque.iterator()`，此前它复用 tuple 的 `SequenceIterators.Fixed`，而 `Fixed.size()` 强行
`(PyTuple) source`，于是 `ClassCastException: PyDeque cannot be cast to PyTuple` 让进程以 70
崩溃。修法是新增 `DEQUE_ITERATOR` 与 `DequeCursor`，形状与参考的 `collections._deque_iterator`
对齐：`type(it).__name__`、`__length_hint__`、`__reduce__` 的第一个槽是类型本身、耗尽后仍保留
deque（之后 `append` 还能继续迭代）、没有 `__setstate__`。

## 4. 判据对照

`test.test_asyncio.test_futures`（同一台机器、同一个 `jy3`，`cwd=/tmp`，`sys.path` 手工指向参考
stdlib）：

| | ran | ok | fail | err | skip |
|---|---|---|---|---|---|
| 修前 | 0 | 0 | 0 | 0 | 0（import 阶段就 `ValueError`） |
| 修后 | 186 | 53 | 3 | 7 | 123 |

- 3 个 fail：`test_copy_state`、`test_future_exception_never_retrieved`、
  `test_tb_logger_exception_unretrieved`。前者的缺口在 `concurrent.futures` 的 `copy_state`（与
  asyncio 无关的既有项）；后两者依赖 `Future` 析构时的「未取回异常」告警记账，CPython 在 C 层做，
  纯 Python 层不产生该告警。
- 7 个 err：主因是 `gc.get_referrers` 未实现与 `sys.get_coroutine_origin_tracking_depth` 缺失
  （`loop.set_debug(True)` 的记账面），都不是调度语义。

`test.test_asyncio.test_tasks` 已能起跑；本次跑到第 864 例
（`PyTask_PyFuture_SubclassTests.test_cancel_gather_2`）后不再前进，未能给出全量 RESULT。该用例
的四组 `cancel_args`（`()`／`(None,)`／`('my message',)`／`(5,)`）单独复现全部通过；挂起只在
整模块连跑时出现，说明是前面用例留下的状态（未提及 `_asyncio` 的 C 层假设之外，最可能是许多
未关闭的 loop 与 `_py_all_tasks` 的累计）而不是调度本身。这一条如实记为**已知问题**，下一步先
定位它，再把 `test_tasks` 的数字补齐。

## 5. 具名差距

1. **asyncgen hooks 只存不调**。`set_asyncgen_hooks` 收下两个可调用对象并原样交回，但
   `_asyncgen_firstiter_hook`／`_asyncgen_finalizer_hook` 从不被调用：jy3 的 async generator 没有
   finalizer 协议，且 `weakref.ref(agen)` 不支持，asyncio 的 firstiter 会做 `WeakSet.add`，强行
   调用只会 `TypeError`。因此 `loop.shutdown_asyncgens()` 今天没有可关闭的注册项。
2. **structseq 的实例化句式**。`type(sys.get_asyncgen_hooks())` 是 `asyncgen_hooks`，但它
   `withoutNew`，`asyncgen_hooks(None, None)` 的报错句与参考的 sequence 构造不同；`__reduce__`
   的往返仍然成立。
3. **没有 IO selector 支持**。本片不新写调度器，`base_events` 的 `_add_reader`／`_add_writer`
   走的是参考自己的 selector 代码；真正依赖 fd 就绪的用例要么已经可用，要么按运行时的现状如实
   报错，不做轮询假装。
4. **`_asyncio` 对象不存在**。`hasattr(tasks, '_CTask')` 为假，C 层用例整个 skip；这是普查得出
   的结论，不是待补的洞——除非将来要拿 C 层的 `Future` 优化与 `Future.__await__` 的
   `_asyncio_future_blocking` 细节去对齐。
