# `-m`：把库模块当程序跑（M5 切片 76）。
#
# 参考的 `python -m` 把模块交给 runpy：模块先按 importlib 的路径找到，再以 `__main__` 装载，
# `sys.argv` 是 `["-m", *用户参数]`（模块名不在里面），argv[0] 随后被换成模块文件路径，
# `sys.path[0]` 是启动目录。这里在临时目录写一个普通模块、两个包和一个自己退出的模块，用子
# 进程把各种形态跑一遍，逐项打印返回值与模块自己看到的名字。
#
# 唯一要从 stderr 抹掉的是 sys.executable 的拼写——两边的可执行文件本来就不是同一个，那行
# `No module named ...` 的前缀是它自己。其余每个字符都要求两边相同。

import sys

# 与其它语料同一份参考库，两边读同一个 subprocess.py；子进程也需要它才能 import os。
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import os
import subprocess

ENV = os.environ.copy()
ENV["PYTHONPATH"] = STD

SIMPLE = """\
import os
import sys

print("simple name", __name__, repr(__package__))
print("simple spec", __spec__.name, repr(__spec__.parent))
print("simple loader", type(__loader__).__name__, __loader__.name)
print("simple argv", sys.argv[0].endswith("simplemod.py"), sys.argv[1:])
print("simple path0", sys.path[0] == os.getcwd())
"""

PACKAGE_INIT = """\
print("package init", __name__, repr(__package__))
"""

PACKAGE_MAIN = """\
import os
import sys

print("package main", __name__, repr(__package__), __spec__.name)
print("package main file", os.path.basename(__file__))
print("package main argv", sys.argv[1:])
"""

EXIT_MODULE = """\
import sys
print("exit module", __name__)
sys.exit(3)
"""

os.makedirs("mpkg", exist_ok=True)
os.makedirs("mpkg2", exist_ok=True)
with open("simplemod.py", "w") as simple:
    simple.write(SIMPLE)
with open("mpkg/__init__.py", "w") as init:
    init.write(PACKAGE_INIT)
with open("mpkg/__main__.py", "w") as main:
    main.write(PACKAGE_MAIN)
with open("mpkg2/__init__.py", "w") as empty:
    empty.write("")
with open("exitmod.py", "w") as exit_module:
    exit_module.write(EXIT_MODULE)


def run(tag, argv, data=None):
    done = subprocess.run([sys.executable, "-B"] + argv,
                          input=data, capture_output=True, env=ENV)
    print(tag, "rc", done.returncode)
    print(tag, "out", repr(done.stdout.decode()))
    print(tag, "err", repr(done.stderr.decode().replace(sys.executable, "EXE")))


run("module", ["-m", "simplemod", "one", "two"])
run("package", ["-m", "mpkg", "x"])
run("absent", ["-m", "nosuchmod"])
run("no-main", ["-m", "mpkg2"])
run("exit", ["-m", "exitmod"])
# `-m` 结束选项表：它后面的东西全是模块的参数，即便长得像另一个选项。
run("after -m", ["-m", "simplemod", "-c", "ignored"])
# `-c` 同样结束选项表：`-m simplemod` 这时只是被执行的代码看到的参数。
run("after -c", ["-c", "import sys; print(sys.argv[1:])", "-m", "simplemod"])

# 少了模块名：参考先抱怨这一行，再给出用法，退出码 2。
missing = subprocess.run([sys.executable, "-m"], capture_output=True, env=ENV)
print("missing rc", missing.returncode)
print("missing first", missing.stderr.decode().splitlines()[0])

# 标准库里的模块也能这样跑：json.tool 从 stdin 读、往 stdout 写。
run("json.tool", ["-m", "json.tool"], b'{"b": 2, "a": [1, 3]}')

# 读端先关：参考让子进程的写失败成 EPIPE，json.tool 把它交给 SystemExit，退出码是 32。
piped = subprocess.Popen([sys.executable, "-B", "-m", "json.tool"],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=ENV)
piped.stdout.close()
piped.communicate(b'{"a": 1}')
print("closed stdout rc", piped.returncode)
