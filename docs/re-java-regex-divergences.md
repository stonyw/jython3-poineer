# `re` 以 `java.util.regex` 为后备的差异清单（M6-a 切片 13）

本运行时的 `re` 是参考 3.14 的纯 Python 解析器与编译器（`re/_parser.py`、`re/_compiler.py`），
运行时只回答一件事：`_sre.compile`。[SreRegex.java](../runtime/src/main/java/org/python/core/SreRegex.java)
把解析器交来的模式串重新拼成 Java 正则，并返回 `java.util.regex.Pattern`。
因此「Python 的语义」由解析器保证，「匹配」由 Java 引擎执行；差异只可能出现在这一层翻译里。

回归入口：

- `embedding/src/test/resources/m6a/corpus/17_re_unicode_classes.py` —— 与参考 3.14.10 逐字节对拍，覆盖
  下面第 1–5 节里**已经对齐**的行为。
- `embedding/src/test/resources/m6a/corpus/18_re_conditionals.py` —— 条件组与数字反向引用的逐字节
  对照（见 6.2），同样由参考与本运行时同解。
- `embedding/src/test/resources/m6a/corpus/19_buffer_protocol.py` —— bytes-like 主体与 buffer 导出面
  的逐字节对照（见第 7 节）。
- `runtime/src/test/java/org/python/core/SreRegexDivergenceTest.java` —— 直接对翻译层钉住已对齐项，
  并把第 6 节里**仍存**的差异当作现状固定下来，改翻译时先改这两个文件。

---

## 1. 字符类：已经对齐

Java 的 `UNICODE_CHARACTER_CLASS` 不是 Python 的 Unicode 语义：它的 `\w` 含全部组合记号
（`Mn`/`Me`/`Mc`）与连接符标点（`Pc`），它的 `\s` 又缺 U+001C..U+001F。因此这个标志**不再打开**，
四个转义改由翻译层自己写：

| Python（str，Unicode） | 翻译成 | 依据 |
|---|---|---|
| `\w` | `[\p{L}\p{N}_]` | `str.isalnum()` 是 `L*` 加 `Nd`/`Nl`/`No`，再加下划线 |
| `\W` | `[^\p{L}\p{N}_]`（类内是同一个嵌套类） | 上式的补 |
| `\d` | `\p{Nd}` | `str.isdecimal()` 只认 `Nd` |
| `\D` | `\P{Nd}` | 上式的补 |
| `\s` | `[\p{IsWhite_Space}\x{1c}-\x{1f}]` | `str.isspace()` 是 White_Space 再加四个 C0 分隔符 |
| `\S` | `[^\p{IsWhite_Space}\x{1c}-\x{1f}]` | 上式的补 |
| `\b` | `(?:(?<=[W])(?![W])\|(?<![W])(?=[W]))`，`W` 即 `\p{L}\p{N}_` | Python 的词边界按上面的 `\w` 定义 |
| `\B` | 上式的反面 | — |
| `\A` / `\Z` / `\z` | `\A` / `\z` / `\z` | Python 3.14 的 `\Z` 与 `\z` 都只认绝对结尾 |

`bytes` 模式与 `re.ASCII`/`re.LOCALE` 模式**不翻译**，直接交给 Java 的默认语义——那正是 Python 的 ASCII 表。

实测（参考 3.14.10 与本运行时同解，均已进语料）：

| 被测串 | 结论 |
|---|---|
| `\w` on U+0301（组合尖音符） | False，Java 的 Unicode `\w` 会给 True |
| `\w` on U+203F（连接符标点） | False |
| `\w` on U+2160（`Nl`）、U+2460（`No`） | True |
| `\d` on U+FF10（全角零）、U+0660（阿拉伯-印度数字） | True |
| `\s` on U+001C、U+0085、U+2028 | True |
| `\s` on U+180E | False |
| `[\W]` on U+0301、`[\S]` on `\t`/U+001C | 与参考同解 |
| `a\b` on `"a\u0301"` | True（边界不认组合记号） |

`re.VERBOSE` 丢弃的空白也只认 CPython 的 `WHITESPACE`（空格、`\t`、`\n`、`\r`、`\v`、`\f`）；
Java 的 `Character.isWhitespace` 还会吞掉 U+001C..U+001F，那四个在 Python 模式里是普通字面量
（`(?x)` 下 `chr(0x1c)` 仍能匹配自己）。

## 2. 标志：接受面与影响面

- `re.ASCII`：str 模式切到 ASCII 表；`(?a:...)` 只影响自己的组（翻译层按作用域跟踪）。
- `re.UNICODE`：str 模式本来默认如此，接受但无额外动作；`(?u:...)` 在 `re.ASCII` 模式里切回 Unicode 表。
- `re.LOCALE`：只对 `bytes` 模式有效。str + `re.LOCALE` 由 `re/__init__.py` 在进 `_sre` 之前拒绝
  （`ValueError: cannot use LOCALE flag with a str pattern`），本层看不到。
- 作用域标志 `(?imsx-imsx:...)` 由翻译层自己跟踪 `m`/`s`/`x`/`a`/`u`：Java 只收到 `i`（与 `uU` 的
  等价标记），因为 `m`、`s`、`x` 的语义由翻译层重写。

## 3. 量词：贪婪／懒惰／占有

`*`、`+`、`?`、`{m,n}` 与它们的 `?`（懒惰）、`+`（占有）后缀原样交给 Java，匹配顺序与 Python 一致：
左最先、重复体先贪婪、失败才回溯；占有量词不回吐。回归语料里有 `a+?`、`a*+a`、`(a+)+` 与
`a+?b` 的对照值。

上界 `{4294967295}`（`re` 的 `MAXREPEAT`）两侧都在**解析器**里报
`OverflowError: the repetition number is too large`，到不了翻译层，故不是差异。

## 4. 命名组

- `(?P<name>...)` → Java 的 `(?<gN>...)`；`(?P=name)` → `\k<gN>`；数字分组编号保持一一对应。
  `group("name")`、`groupdict()`、`expand(r"\g<name>")` 因此都与参考同解。
- 为了让条件组的参与标记（6.2）不扰动编号，**所有** Python 捕获组都发成命名组 `(?<gN>`，
  数字反向引用 `\1` 写成 `\k<g1>`，`Match`/`Pattern` 的组访问一律按名字取值。
- `(?<name>...)` 是 Java 的合法写法、Python 的非法写法：`re._parser` 先报
  `PatternError: unknown extension ?<n at position 1`，不会进入翻译层。这是**方向相反**的一处：
  Java 接受而 Python 拒绝，本运行时跟随 Python。
- Java 的 `\k<name>` 写法 Python 不认，同理在解析器处就被拒绝。

## 5. 锚点与空匹配：已对齐的部分

- `^` / `$` 由翻译层改写：`^` → `\A`（多行时 `(?:\A|(?<=\n))`），`$` → `(?:\z|(?=\n\z))`
  （多行时 `(?:\z|(?=\n))`）。Java 的 `MULTILINE` 会把 `\r`、U+0085、U+2028、U+2029 也当行分隔，
  Python 只认 `\n`，所以 Java 的 `MULTILINE` 位不再设置。
- `findall`/`finditer`/`sub`/`subn`/`split` 在**非空匹配**上与参考一致；`Match.group`/`groups`/
  `groupdict`/`start`/`end`/`span`/`expand` 与 `Pattern.findall`/`finditer`/`sub`/`subn`/`split`
  的接口形状不变。

## 6. 差异清单（具名，不假装一致）

6.2 已在本切片闭合，条目保留下来记录机制与回归入口。

### 6.1 空匹配与非空匹配相邻时的推进（Python 3.7+ 规则）

Java 的 `Matcher.find()` 在一次**空**匹配之后会前进一个字符再找；Python 3.7 起允许在同一个位置
先试一个**非空**匹配，找不到才前进。因此下面三处不同，其余组合一致：

```python
re.split(r'\b|:+', 'a::bc')      # 参考 ['', 'a', '', '', 'bc', '']；本运行时 ['', 'a', ':', '', 'bc', '']
re.sub(r'\b|:+', '-', 'a::bc')   # 参考 '-a---bc-'；本运行时 '-a-:--bc-'
re.findall(r'\b|:+', 'a::bc')    # 参考 ['', '', '::', '', '']；本运行时 ['', '', ':', '', '']
```

纯空匹配的推进（如 `re.sub(r'\b', '|', 'ab cd')`、`re.findall(r'\b', 'ab cd')`）以及纯非空匹配
都与参考一致。要修这一处，需要改写 `PySrePattern` 的迭代器，让它在空匹配后于原地重试非空匹配；
Java 的 `Matcher` 没有这个开关。

### 6.2 条件组 `(?(id)yes|no)`：已对齐（切片 12 闭合）

Python 支持条件组，Java 没有对应构造，翻译层把它改写成对「组 N 是否参与」的问询：

- 若组 N 在条件之前就闭合，翻译层在组 N 的末尾插入一个空命名组 `(?<mkN>)`。Java 对**未参与**组的
  反向引用恒失败，对**参与且捕获空串**的引用在任意位置成功，所以 `(?=\k<mkN>)` 恰好等价于
  「组 N 参与过」。条件本身写成 `(?:(?=\k<mkN>)yes|(?!\k<mkN>)no)`。
- 若条件嵌在它引用的组内部（组尚未闭合），CPython 只在组闭合时记录结束位置，该引用读作
  「未参与」，故翻译为 `(?:(?!)yes|no)`（`(?!)` 恒失败）；yes 分支仍翻译，以保持组编号。
- marker 会改变 Java 的组编号，因此所有 Python 捕获组改发为 `(?<gN>`，`\1` 写成 `\k<g1>`，
  组访问一律按名字取值（见第 4 节）。
- 数字反向引用最多两位：`\119` 是组 11 加字面 `9`，与 `re._parser` 的读法一致。
- 条件可以引用**之后才闭合**的组（`(a)b(?=(?(2)x|c))(c)`，CPython 的 `test_lookahead`）。Java
  拒绝尚未见到的**命名**反向引用，却接受前置的**数字**反向引用，所以 marker 引用先写成占位符，
  整条模式翻译完再换成编号。

回归：语料 `18_re_conditionals.py`（与参考 3.14.10 逐字节一致）与 `SreRegexDivergenceTest` 的
`conditionalGroupsTestParticipation` / `conditionalMarkerKeepsThePythonGroupNumbers` /
`forwardConditionalReferencesWorkByNumber` / `numericBackReferencesTakeAtMostTwoDigits`。`test_re` 的
`test_re_groupref_exists_validation_bug`（256 个 subTest）、`test_re_groupref_exists`、
`test_symbolic_groups`、`test_bug_3629`、`test_debug_flag`、`test_lookahead` 随之离开错误簇。

### 6.3 回溯规模与栈深度

两者都是回溯引擎，但没有共同的规模契约：

- Java 的 `Pattern` 在深层回溯时抛 `StackOverflowError`（或 `PatternSyntaxException` 之外的
  `RuntimeException`），Python 的 `_sre` 用堆上的栈，只受内存限制，且没有可调的回溯上限。
- 灾难性回溯的**时间**在 Java 侧同样指数级，本运行时不做超时或步数限制；不要指望与 CPython
  逐毫秒一致。
- Java 9 起对 `Matcher.find()` 的空匹配处理做过一次修改，但未改变上面 6.1 的差异；不要把它当作
  Python 3.7 那次修改的对应物。

### 6.4 大小写折叠的边角

`re.IGNORECASE` 在 str 模式走 Java 的 `UNICODE_CASE`，多数简单折叠一致（`k` 匹配 U+212A、
`s` 匹配 U+017F、`i` 匹配 U+0130），但 Java 的折叠表与 CPython 的
`_PyUnicode_ToLowercase` 不是同一张：多字符折叠（`ß` 对 `ss`）两侧都不做，而个别
`SpecialCasing` 条目可能不同。语料只钉住已实测一致的几项；新发现的不一致记在这里。

### 6.5 不属于翻译层的缺口

`test_re` 里余下的失败还混着运行时自身的洞，与 `java.util.regex` 无关：`re.Match` 的
`NotImplementedError`（应报 `TypeError: cannot create 're.Match' instances`）、`Pattern.scanner`、
`Pattern.__weakref__`、`test_enum`/`test_disallow_instantiation` 的内建类型元数据等。
这些按里程碑另行处理，不在本清单口径内。

其中两条与主体（subject）有关，切片 13 已让 bytes 模式接受全部 exporter，但**生命期**尚未建模：
`test_keep_buffer` 要求 `finditer(bytearray)` 在迭代器存活期间持有导出、使 `bytearray` 的
`append` 抛 `BufferError`；`test_bug_29444` 要求 `Match.group()` 读的是那块活 buffer，而不是匹配时的
快照。本运行时在匹配入口把主体取成一段 `str`（这正是跨过 Java 引擎必须付的代价），所以这两条
仍是具名差异，不假造导出计数。

---

## 7. 输入面：bytes-like 主体

bytes 模式的主体按 CPython 的 `getstring` 取数：先问 buffer，拿不到才报
`expected string or bytes-like object, got 'X'`。因此 `bytearray`、`array.array`、`memoryview` 都是
合法主体，与 `bytes` 同解；str 主体仍报 `cannot use a bytes pattern on a string-like object`，
反过来 str 模式遇上 buffer 报 `cannot use a string pattern on a bytes-like object`。

这一节的对拍入口是语料 `19_buffer_protocol.py`：它同时钉住 `memoryview` 的
`format`/`itemsize`/`ndim`/`shape`/`strides`、切片与 `cast` 之后再导出、`released` 之后的
`ValueError: operation forbidden on released memoryview object`、`array` 与 `memoryview` 的双向转换
（`tobytes`/`frombytes`/`buffer_info`、按元素切片）、`bytes()`/`bytearray()`/`BytesIO.write` 的取数
口径，以及导出期间 `array` 的十种改写各自抛
`BufferError: cannot resize an array that is exporting buffers`。

唯一不逐字节相同的字段是 `array.buffer_info()[0]`：它是进程地址，JVM 上不存在，本运行时答 `0`；
语料只比较长度那一半。
