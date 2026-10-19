# Jython3 — 总体计划（Python 3.14 on JVM, Java 21+）

> 状态：计划已确认（2026-10-18）。M0 完成；M1 完成 MVP①（2026-10-19，`test_types` 子集仍待 M4 的导入系统）；M2 完成 MVP②③④（2026-10-19，互操作面偏差见 §3 M2 行）。本文件是唯一的路线图来源，各阶段完成后更新"验收结果"。

## 0. 目标与硬约束

- 语言与运行时兼容目标：**CPython 3.14**（当前 3.14.8）。
- 运行环境：**Java 21 LTS 及以上**（CI 矩阵 21 / 25）。
- MVP 验收四条能力：
  1. Python 源码在 JVM 上运行（hello world）；
  2. Python 中可用 Java 内建内省；
  3. Python 中可访问 Java 内建类库与常用类库（绑定/导出翻译）；
  4. Java 侧可调用 Jython 代码片段与 Jython 类/函数（双向互操作）。
- MVP 边界 = **扩展档**：M0–M4（含导入系统、常用纯 Python 标准库、REPL）。

## 1. 架构决策记录

| # | 决策 | 理由 |
|---|---|---|
| D1 | **两段式编译前端**：Stage A 以 CPython 3.14 为编译器，JVM 侧读 `marshal` v5 code object 并解释 CPython **未特化 wordcode**；Stage B（M5）替换为自研 pegen4j 前端 + 自有 code object | Stage A 免费获得 3.14 语法/AST/语义平价，最短路径打通四条 MVP；官方 Jython 3 线独立选择了同一前端，方向已被验证 |
| D2 | **code object 从 M1 起就是接口**（`PyCode` 抽象 + `Cpython314Code` / 未来 `NativeCode` 两个实现）；任何 wordcode / linetable / exception table 细节只允许存在于 Stage A 读取器内 | 修复官方 jython3 把 3.11 格式硬编码进 runtime 的致命伤，使 Stage B 是"替换"而非"重写" |
| D3 | **解释器优先**，MVP 不做 JVM 字节码生成；`invokedynamic` 热路径编译与 `.class` 导出列为后续可选 | 2.7 的 `CodeCompiler` 系 Py2 发射逻辑且必须重写，投入不回报 MVP 四条能力 |
| D4 | 互操作栈**以 2.7 为参考、按 py3 语义原生重写**（不复制代码） | 2.7 有 ~11k LOC 已适配 JDK 21 的成熟设计（`PyJavaType`/`ReflectedArgs`/`PyArray`/`packagecache`/`expose`）；官方 jython3 的 `Exposer` 反而不支持 `import java.util.*` |
| D5 | 标准库策略：**直接分发 CPython 3.14 `Lib/` 的纯 Python 部分**（187/297 模块），配 `CPythonLib.includes` 清单 + "自有 `Lib/` 永远覆盖同名文件" + `*_jy.py` 约定；扩展支撑的 110 模块逐个用 Java 实现或显式裁剪 | 复用 2.7 已验证的装配机制，避免重写百万行 stdlib |
| D6 | 测试方法论：**移植 `regrtest` 的 `_expectations`/`_failures` 双向意外检测**（既报意外失败也报意外通过），平台键 `java`/`java.nt`/`java.posix`，JUnit XML + CI 矩阵 | 没有"可度量的 done"就无法推进跨会话的长期工程 |
| D7 | Java 包名沿用 `org.python.*`（`core`/`util`/`modules`），Maven groupId 用新坐标 | 2.7 互操作栈移植改名成本为零，且嵌入 API 与既有文档习惯兼容 |
| D8 | 线程模型按 **PEP 703/779 无 GIL** 语义描述（JVM 天然无 GIL），差异写入文档 | 3.14 自由线程已官方支持，与 JVM 模型天然对齐 |
| D9 | 本机 JDK 由 **jenv** 管理：仓库根 `.java-version = 21.0.12.1`，构建用 `JAVA_HOME=$(jenv javahome)`；Gradle 侧用 toolchain `languageVersion=21` + `options.release=21` 断言。JDK 27 可运行构建但**不得作为 target** | 单人可复现的版本选择，且 CI 与本地选择同源；`./gradlew doctor` 一键核对 |

## 2. 实测量级锚点

| 项 | 数值 |
|---|---|
| CPython 3.14 stdlib | 297 模块 = 187 纯 Python（可随发）+ 110 扩展支撑（Java 实现或裁剪，其中约 20 个 Windows/可选） |
| 3.14 指令集 | 237 命名 opcode（含特化），`.pyc` 落盘未特化，**基础集 ≈130** |
| 3.14 语法增量 | t-strings（`BUILD_TEMPLATE`/`BUILD_INTERPOLATION`，`string.templatelib` 为纯 Python）、PEP 758 无括号 except、PEP 765、延迟注解（`SETUP_ANNOTATIONS` + `__annotate__` + `annotationlib`） |
| 若走自研前端（M5 规模参考） | `python.gram` 1,577 行；`Grammar/Tokens` 78 行；tokenizer+pegen ≈ 2.4k C 行；`compile.c`+`ast.c`+`symtable.c`+`codeobject.c` ≈ 9.8k C 行；AST 节点 ≈ 200 |
| 参考体量 | Jython 2.7：371k Java LOC + 491 个 `Lib/*.py`；官方 jython3 `core`：36.6k LOC，约 50/221 opcode，估算完成度 5–8% |

## 3. 里程碑

| 阶段 | 目标 | 验收判据 | 量级 | 验收结果 |
|---|---|---|---|---|
| **M0** 基建 | JDK 21 + Gradle 多模块骨架 + CI（21/25 × mac/linux）+ 黄金测试 harness（引入 `lib-python/3.14` 与 expected-fail 双向清单）+ `jy3` CLI 桩 | 空实现也能产出 pass/fail/expected 报告；CI 绿 | 0.5–1 周 | ✅ 完成：6 模块 Gradle 9.8.1 构建绿、配置缓存无告警；30 个 harness 单元用例通过；参考后端 smoke 12/12 PASS 0 意外；jy3 后端 12/12 诚实报 NOT_IMPLEMENTED（exit 3 协议已验证） |
| **M1** 运行时骨架 → **MVP①** | `marshal` v5 读取 + `PyCode` 接口 + 15 个核心类型 + 槽位分派 + 异常模型 + ≈60 opcode + `sys`/`builtins` 骨架 + stdout | `jy3 hello.py`；算术/分支/循环/函数调用；`test_types` 子集通过 | 2–4 周 | ✅ 前两条判据达成并实测：`jy3 hello.py` 的 stdout/stderr/exit 与 CPython 3.14.8 **逐字节一致**；`m1/corpus` 10 个差分脚本（整数、浮点、比较、控制流、函数、闭包、字符串、容器、切片与 range、builtins+sys）在两个后端下 stdout+stderr+exit 全等，`Jy3LauncherTest` 41 例全绿；opcode 实测 90 个，`:runtime:checkOpcodes` 门禁保证表与 `bytecodes.c` 同步。第 3 条 `test_types` **未达成**：它要先 `import unittest`，导入 finder 属 M4。已知偏差（均已在代码或此处具名）：未捕获异常只打印类型与消息（traceback 帧属 M3）；`sys.version_info` 是纯元组、无 `.major`（structseq 属 M3）；`sys.path` 保持 `[]`（M4）；`int.to_bytes`/`bytes` 方法与完整 Unicode case folding 的希腊分支未做；`-m`/`-X`/`-O`/目录入口/REPL/`--harness` 一律诚实报 exit 3；`int ** 负整数` 的末位 ulp 取决于 libm（CPython 自己走 `float_pow`），不做逐位对齐 |
| **M2** 双向互操作 → **MVP②③④** | py3 原生重写 `PyJavaType`/`ReflectedArgs`/`PyArray`/包扫描/`@Exposed`；重载、可变参数、装箱、泛型擦除、SAM 函数式接口、静态成员、bean 属性、`__tojava__`；Java→Python：`PythonInterpreter`、片段求值、`PyObject`→Java 接口代理、跨边界异常；JPMS/`--add-opens` 边界 | 四条 MVP 各有可运行 demo + `tests/java` 用例；`import java.util.*`、`from java.awt import Point`、Java 调 py 函数取回 `List` | 3–6 周 | ✅ 四条 MVP 全部达成并实测。demo 脚本按 MVP 编号放在 `embedding/src/test/resources/m2/`，`InteroperabilityDemoTest` 用 `jy3` 子进程跑 ②③、在本 JVM 内跑 ④（①仍是 `m1/hello.py`，由 `Jy3LauncherTest` 对 CPython 度量，不重复测量）。② `02_introspection.py`：Java 内建类型的基本/包装/`Integer.toHexString`/反射属性；③ `03_libraries.py`：`import java.util.*`、`from java.awt import Point`、Stream/`Optional`/`LocalDate`/`Duration`/`Point2D` 静态与未绑定两写法/`reflect.Array`，每行钉的是 JDK 自己的答案（CPython 跑不了这些行），并由 `WidgetOracleTest`、`JavaFromPythonTest` 在 Java 侧重新量一遍。④ `04_embedding.py`：`PythonInterpreter.invoke("build", 4)` 取回 `java.util.List`（验收句本身）、`shout`/`counts` 的值与容器翻译、`export(Panel.class)` 多方法命名空间代理回写 `log`、Python 失败以 `PyRaise("ValueError: bad value")` 落到 Java `catch`。实现要点：重载按 JLS 15.12.2 三段有序搜索（`Overloads`）+ 单一转换表（`JavaConvert`）、varargs 末位已是数组时按数组透传（15.12.2.4）、SAM 与多方法接口 `Proxy`、bean 属性、`java.lang.Class` 形参、JPMS 强封装在调用点显式报错。测试基线：`:runtime:test` 176 例、`:embedding:test` 92 例、`:front-cpython314:test` 77 例全绿，harness smoke 12/12 零意外，`jy3 --version` 改为 `0.1.0-M2`。已知偏差（均已在代码或此处具名）：⓪判据里的 `tests/java` 是 §6 骨架的示意位置，M0 起 JUnit 用例实际就随模块放在各 `*/src/test/java`，互操作用例集中在 `embedding`；①不做独立 `PyArray` 类型，数组由 `PyJavaObject` 的数组协议服务（长度/下标/赋值/`in`/迭代），`str(array)` 是 Java 的 identity 形式，切片未支持 → M3；②`@Exposed` 未实现，改为「JVM 在调用点判定 + `invocable()` 把包私有声明类重绑到最近公开父类型」，因此 `--add-opens`/`--add-exports` 是唯一开关，若 M4 装配 jar 需要 manifest 元数据再回头；③`__tojava__` 只实现 `PyJavaType → Class<?>`，Python 自定义的一侧留 M3（它唯一的可能是用户自己的类）；④内建方法表随值类加载才注册，从未实例化过的类型上 `float.hex` 一类属性可能 AttributeError → M3 类系统按类型装载表；⑤`bool` 不继承 `int` 的方法（`True.bit_length()` AttributeError），与④同源于 M3 的 MRO/descriptor，`PyMethodDescriptor` 已改为委托接收者自身查找，两种写法必然同解；⑥~~`try/except` 仍不可用（需 3.14 异常表，M3），跨边界异常因此走「设置异常对象」路径验证~~ **已作废**：M3 切片 1 实现了异常表与 catching 语义，`except` 与 `except Exception` 捕获 Java `Throwable` 都已实测（见 M3 行），该路径现在只是嵌入方的另一条路由；⑦未捕获异常仍只打印类型与消息（traceback 帧 M3 后续切片） |
| **M3** 语言面完整 | 补齐 ≈130 opcode：class + C3 MRO + metaclass + descriptor + `super`、迭代协议、生成器/`send`、3.14 异常表 + `except*`、推导式、解包、`match`、f-string/t-string、延迟注解、剩余 builtins、`codecs`/`_io` | `test_class`/`test_exceptions`/`test_generators`/`test_descr`/`test_builtin`/`test_fstring` 达约定通过率 | 6–10 周 | 🟡 **进行中。切片 1：3.14 异常表与 catching 语义已达成并实测**。解码侧 `ExceptionTable` 读 `co_exceptiontable`（每行 4 个 varint，偏移量按 CPython 的 code unit 保存，与 `dis` 的字节偏移按 ×2 对齐度量），`PyCode.handlerFor(index)` 是 runtime 唯一的提问方式（D2：wordcode/linetable/异常表细节一概不许越过 `front-cpython314`）；驱动侧 `Interpreter` 每执行一条指令就 `setExecuting`，`catch (PyRaise)` 交给 `unwind`——它就是 `exception_unwind` 的翻译：按覆盖失败指令的那一行 pop 到 `stackLevel`、需要时把失败指令的 unit 压在异常下面、然后把 ip 指向 handler，异常本身不经任何存储（Java 正在携带它）。`PUSH_EXC_INFO`/`POP_EXCEPT`/`RERAISE`/`CHECK_EXC_MATCH` 落地；运行时的 handled 槽是 CPython 的 `exc_info->exc_value`（thread-local，只由这两个 opcode 写，`raise` 不写），于是 `sys.exc_info()`、裸 `raise`、隐式 `__context__`（含 CPython 的 Floyd 环切断）、`raise X from Y`/`from None`、`__suppress_context__` 全部与 CPython 同解；`except` 子句校验只下一层，`except ((TypeError, KeyError), ValueError)` 按 CPython 报 `TypeError: catching classes that do not inherit from BaseException is not allowed`；`RERAISE` 的 lasti 只搬报告站点、不搬执行站点（两字段分开，否则同一行会被再次命中而死循环）；KeyError 单参数的 `str` 用 `repr`（CPython 的 `KeyError_str`，`{}['']` 要读成 `KeyError: ''`）。度量：`m3/corpus` 4 个差分脚本（handler 次序/元组子句/子句变量删除、`finally` 与 `reraise`、异常链、`sys.exc_info`）在 jy3 与 CPython 3.14.8 下 stdout+stderr+exit 全等，`PythonInterpreterTest` 的 catching 段 9 例（含 `except Exception` 捕获 Java `Throwable` 后仍是那个 Java 异常），`:front-cpython314:test` 因两个新增异常表 fixture 增至 79 例；全量 `./gradlew check` 绿，harness smoke 12/12 零意外。**M3 剩余**：traceback 帧与 `__traceback__`（要帧链，现在恒为 `None`，未捕获异常因此仍只打印类型与消息）、`with`（`LOAD_SPECIAL`/`WITH_EXCEPT_START`）、生成器与 `send`、`except*`（`CHECK_EG_MATCH`/`BaseExceptionGroup`）、`match`、class + C3 MRO + metaclass + descriptor + `super` + 按类型装载方法表（收 M2 偏差④⑤）、`bool` 继承 `int`、`sys.version_info` 的 structseq（收 M2 偏差、并解 `int.to_bytes`）；`test_class`/`test_exceptions`/`test_generators`/`test_descr` 等判据尚不能跑，它们要先 `import unittest`（导入 finder 属 M4） |
| **M4** 导入系统 + 标准库装配 | `_imp`、`sys.meta_path`、3.14 `importlib` 语义、zip/jar 导入；`CPythonLib.includes` 重生成 3.14 版；`Setup` 内建模块注册表；REPL（JLine 3） | `unittest/json/logging/configparser/datetime/dataclasses` 可用；`jy3 -m unittest` 可自举；纯 Python 模块清单首批 N=40 通过 | 4–8 周 | — |
| **M6-a** 首批扩展 | async/协程（`_asyncio` + `asyncio`）；`re` = `java.util.regex` + 公开差异清单；free-threading 语义文档化；`posix`/`_posixsubprocess`/`_socket`/`select`/压缩/哈希 | `test_asyncio` 子集；差异清单成文并有回归用例；subprocess/socket demo | async 4–6 周，其余滚动 | — |
| **M5** 原生前端（解耦、可推迟） | pegen4j 读 `Grammar/python.gram` 生成 Java + 3.12+ tokenizer 移植 + `symtable` + `NativeCode` 实现 D2 接口 | PATH 上无需 `python3.14`；`exec(str)`/REPL/纯 JVM 部署；与 CPython 语法错误消息对齐测试 | 8–16 周 | — |

**MVP（M0–M4）合计 ≈ 16–29 人·周**（单人专注）；含 M6-a 首批扩展再 +10–14 周。

## 4. 明确不做

Python 子类化 Java / JavaAdapter、`.class` 导出（D3 后置）；CPython C-API 扩展模块兼容（numpy 等）；
`_ctypes`；`_tkinter`；`nt`/`msvcrt`/`_winapi`/`_overlapped`/`winsound`/`_wmi`；`_interpreters`/
`_interpchannels`/`_remote_debugging`；`readline`/`_curses`/`termios`/`syslog` 视平台按需。

## 5. 风险登记册（前 5）

| 风险 | 影响 | 缓解 |
|---|---|---|
| 生成器/协程在 JVM 上的帧堆管理（每帧独立栈、`send`/`throw`/挂起恢复） | M3/M6-a 最大不确定项 | 帧对象入堆、禁止依赖 JVM 调用栈做 Python 控制流；先用 `test_generators` 打通最小可行模型 |
| `re` 语义差异（3.11 起纯 Python `sre_compile` 回退已删除） | 大量 stdlib/第三方依赖 `re` | 采 D4 式"Java 正则 + 差异清单 + `*_jy.py` 覆盖 `re.py`"；差异用例入回归 |
| `.pyc` 非稳定 ABI，Stage A 锁 3.14.x | 升级 CPython 即破 | D2 接口隔离；读取器内做 `magic`/`marshal` 版本校验并给出明确错误 |
| Java `Character`/`Normalizer` 与 CPython Unicode 版本漂移 | `str.upper/casefold`、`unicodedata` 表差异 | 引入 ICU4J 对齐 Unicode 版本，并固定版本表；差异入 `test_string`/`test_unicode_file_functions` |
| JPMS 强封装下的 Java 内省（`setAccessible`、`Add-Opens`） | 内省与 `@Exposed` 在 21+ 上受限 | 2.7 已有应对（manifest `Add-Opens`、`isAccessibleClass`）；优先 `MethodHandles.Lookup` 私有访问而非 `setAccessible` |
| **参考后端与 vendored 测试树版本漂移**（M0 实证） | `refs/cpython` 的 `3.14` 分支是 3.14.9+ 开发尖，其 `test.support` 调用 3.14.8 二进制不存在的 `_testlimitedcapi` 符号 → harness 报成运行时 CRASH 而非版本错误 | `:harness:syncCpythonTests` 默认从**被测解释器自身**的 `Lib/test` 取样（1661 文件），从根上消除漂移；仅当刻意对齐固定源码树时用 `-Pjython3.cpythonTestDir=` 覆盖 |

## 6. 仓库骨架（M0 建立）

```
jython3/
  settings.gradle            # 下列模块
  runtime/                   # org.python.core — py3 对象模型、槽位、帧、异常（不含任何 CPython 字节码细节）
  front-cpython314/          # Stage A：marshal v5 读取 + CPython wordcode 解释；D2 接口实现
  javainterop/               # PyJavaType / ReflectedArgs / PyArray / packagecache / expose
  embedding/                 # PythonInterpreter、RegistryKey/Options、jy3 CLI、REPL
  stdlib/                    # CPythonLib.includes、*_jy.py 覆盖、装配产物
  tests/java/                # JUnit 5
  tests/harness/             # regrtest 双向意外清单
  lib-python/3.14/           # 从 CPython 3.14 引入的回归测试
  refs/                      # 参考克隆（jython main / jython-2.7 / jython3-py3k / cpython 3.14），不参与构建
```

## 7. 推进节奏

每个里程碑结束时：更新本文件"验收结果"列 → 记录新的预期失败清单基线 → 不跨里程碑并行开新面。
M1+M2 期间**先立测试再写实现**，因为 MVP 的四条能力都可表达为可执行 demo。
