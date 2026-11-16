# `_sqlite3` 盘点（M5 切片 65）

> 结论先行：**JVM 没有内置 SQLite 实现，也没有已缓存的驱动；但有一个成熟的第三方 JDBC 驱动可用，
> 其内嵌 SQLite 版本与参考 3.14.10 完全一致。**因此实现前缀是「需要新增第三方依赖」，本轮停在这里，
> 未改动任何 build 文件，也未写任何 DB-API 替身。选项见 §4，等待决策。

盘点依据均为本机实测（2026-11-16），命令与原文如下。

## 1. 当前状态

**1.1 `_sqlite3`**：不存在。

```
$ jy3 -c "import _sqlite3"
ModuleNotFoundError: No module named '_sqlite3'
```

**1.2 `sqlite3`**（参考 stdlib 的纯 Python 包）：断在 `dbapi2` 第一条 C 依赖。

```
$ PYTHONPATH=<参考 stdlib> jy3 -c "import sqlite3"
  File ".../sqlite3/__init__.py", line 57, in <module>
    from sqlite3.dbapi2 import *
  File ".../sqlite3/dbapi2.py", line 27, in <module>
    from _sqlite3 import *
ModuleNotFoundError: No module named '_sqlite3'
```

**1.3 参考**：`sqlite_version` = **3.54.1**，`threadsafety` 3，与 `_sqlite3` 同名。

**1.4 `test.test_sqlite3`**：不在「0 ran」这一类，而是**整个包在收集阶段被跳过**——
`test/test_sqlite3/__init__.py:4` 的 `import_helper.import_module('_sqlite3')` 抛 `unittest.SkipTest`，
runner 没有 RESULT 行。包的规模：10 个测试模块、**504 个 `test_*` 方法**
（`test_dbapi` 205／`test_userfunctions` 64／`test_transactions` 48／`test_types` 48／
`test_regression` 38／`test_hooks` 32／`test_factory` 28／`test_cli` 18／`test_backup` 15／`test_dump` 8）。
注意：`sqlite3/dbapi2.py` 是 `from _sqlite3 import *`，所以 `Row`、`Blob`、`PrepareProtocol` 与 196 个
公开名在 import 时全部被要求——没有「只点亮最小面、其余不跑」的中间态，包一导入就是 504 例。

**1.5 `_sqlite3` 的面**（参考 `dir()`）：公开名 **196** 个（其中约 150 个是 `SQLITE_*` 常量）、
`Connection` **42** 个成员、`Cursor` **15** 个、`Row` **1** 个。

## 2. JVM 侧后端可用性

**2.1 已注册的 JDBC 驱动：0 个。**

```
$ jy3 -c "from java.sql import DriverManager; ... DriverManager.getDrivers()"
count 0
```

**2.2 常见驱动类加载失败：**

```
$ jy3 -c "from java.lang import Class; Class.forName('org.sqlite.JDBC')"
ClassNotFoundException org.sqlite.JDBC
```

**2.3 JDK**：Temurin **25.0.5**（仓库 `.java-version` 固定 21.0.12.1；构建 `options.release=21`）。
JDK 只提供 API 模块 `java.sql`、`java.sql.rowset`，**不含任何驱动实现**。

**2.4 本地缓存**：`~/.gradle/caches`（1.0 GiB）与 `~/.m2` 下 `*sqlite*` 命中 **0**；
`gradle/libs.versions.toml` 只有 junit／assertj；仓库里没有 SQLite 构件的 jar。

**2.5 系统层**：`/usr/bin/sqlite3` 存在，版本 **3.51.0**；SDK 有 `libsqlite3.tbd`（链接桩），
`/usr/lib/libsqlite3.dylib` 在 dyld 共享缓存里不可见。另有 Android SDK 自带 `sqlite3` 3.44.3。
这些是**进程外二进制**，JVM 不能直接调用。

**2.6 仓库配置**：`settings.gradle.kts` 只声明 `mavenCentral()`，无离线开关；
`org.gradle.java.installations.auto-download=false`，但普通依赖仍会联网解析。

## 3. 结论

不是「JVM 没有 SQLite」这么绝对：JVM **没有内置实现、没有内置驱动、没有缓存驱动**，
但**可以引入一个纯 Java + 内嵌原生库的 JDBC 驱动**（下一节实测）。所以前缀是
「有驱动但需要新增第三方依赖」。按指令停在此处：不改 build 文件、不写假 DB-API。

## 4. 选项（需要你选一个）

### A. 引入 `org.xerial:sqlite-jdbc:3.54.1.0`（推荐）

- **许可证**：Apache-2.0（pom `<licenses>` 原文）。
- **体积**：**12,132,578 字节（约 11.6 MiB）**单 jar；内含各平台原生库
  （`org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib` 1,140,608 B；x86_64 1,300,412 B；另有 Linux/Windows/FreeBSD）。
- **内嵌 SQLite 3.54.1**，与参考 `sqlite_version` **完全一致**（本机实测见下）。
- **传递依赖**：`org.slf4j:slf4j-api` 声明为 `<optional>true</optional>`，因此**不引入**；无其他运行期依赖。
- **离线构建影响**：仓库缓存里没有，所以**首次构建必须联网一次**（Gradle 拉入 `~/.gradle/caches` 后即可离线）。
  若要求绝对离线，可把 jar 放进仓库内 `libs/` 并 `files(...)` 引用（会把 11.6 MiB 二进制入库，需你同意）。
- **本机实测**（`java -cp sqlite-jdbc-3.54.1.0.jar`，JDK 25）：

```
sqlite_version 3.54.1
row 1 a
dup org.sqlite.SQLiteException | [SQLITE_CONSTRAINT_PRIMARYKEY] A PRIMARY KEY constraint failed (UNIQUE constraint failed: t.x)
```

  注意 JDK 25 会对 `System::load` 打印 restricted-method 警告，需给运行器加
  `--enable-native-access=ALL-UNNAMED`（或在未来 JDK 上会变成拒绝）。

### B. 用 JDK 的 FFM 直接绑系统 `libsqlite3`（零新依赖，但不推荐）

- 优点：不引入第三方构件。
- 代价：项目 `options.release=21`，而 FFM 在 21 是 **preview**（22 才正式），要么升 target、要么 `--enable-preview`；
  需手写几十个 C 函数绑定（`sqlite3_open_v2`／`prepare`／`bind`／`step`／`column_*`／`errmsg`／`changes`…）；
  平台覆盖依赖系统库，且本机系统 SQLite **3.51.0 ≠ 参考 3.54.1**，`sqlite_version` 一读就不同。

### C. 进程外调 `sqlite3` CLI（排除）

不是 DB-API：BLOB、类型亲和、`text_factory`、事务状态、错误码、`detect_types` 都无法对齐，
等于用一个壳子骗测试。不做。

## 5. 若选 A：最小真实面与风险（预估，未实施）

- **驱动之上仍是真活**：JDBC 只给 `Connection`/`PreparedStatement`/`ResultSet`，DB-API 的形状
  （`connect` 参数、`cursor` 工厂、`row_factory`、`text_factory`、`detect_types`、`isolation_level`、
  `Row`、`Blob`、`Backup`、`user_function`、`set_*hook`）都要自己搭；`dbapi2` 的 `from _sqlite3 import *`
  要求 196 个名字同时存在。
- **错误消息**：JDBC 的消息形如 `[SQLITE_CONSTRAINT_PRIMARYKEY] A PRIMARY KEY constraint failed (UNIQUE constraint failed: t.x)`，
  括号内才是 SQLite 的 `errmsg`；参考直接给 `UNIQUE constraint failed: t.x`。
  逐条裁剪可对上大部分（`NOT NULL constraint failed: t.y`、`no such table: nope`、`no such column: z` 均已验证可提取），
  但**不会全部逐字节**，对不上的要具名。
- **我们自己控制的句子反而好对**：绑定个数（参考 `Incorrect number of bindings supplied. ...`，
  JDBC 侧是 `ArrayIndexOutOfBoundsException`）、关闭后操作（参考 `Cannot operate on a closed database.`，
  JDBC 是 `The database has been closed`）——这些在 Python 面先判，按参考原文抛。
- **异常层映射**：可用 `org.sqlite.SQLiteException#getResultCode()` 的 primary code
  （19 → `IntegrityError`，其余 → `OperationalError` 等），比解析消息稳。
- **判据的现实**：`test_sqlite3` 一旦可导入就是 **504 例**，不是一个小数字；建议先出基线，
  再按 `test_dbapi` → `test_types` → `test_transactions` 的顺序分簇，而不是承诺一次全绿。

## 6. 本轮做了什么

- 只做了盘点与本文件；未改 `build.gradle.kts`／`libs.versions.toml`，未加依赖，未写 `_sqlite3` 任何代码。
- PLAN 双文件记「待依赖决策」，不把 `_sqlite3` 写进「明确不做」（它取决于你的选择）。
