# stdin 与中断：没有程序名时读标准输入，未捕获的 KeyboardInterrupt 以信号离场（M5 切片 77）。
#
# 参考的入口：无参数且 stdin 不是终端时，`pymain_run_stdin` 让 `_pymain_run_repl` 直接落到
# `_PyRun_AnyFile`，整个流就是程序；`sys.argv` 是 `config_update_argv` 留下的那一个空串，
# `__file__` 是 `<stdin>`，`__spec__` 是 None。显式的 `-` 只把 argv[0] 换成 `-`，其余照旧。
#
# 未捕获的 KeyboardInterrupt 不是以 130 离场：参考的 `exit_sigint` 把 SIGINT 恢复成默认动作，
# 再朝自己发一次，父进程从 `waitpid` 读到的是 -2（被信号终止）而不是 130。hook 自己抛出的
# KeyboardInterrupt 也一样，因为那个标记在 hook 跑之前就记下了。
#
# 下面每个子进程都打印 returncode 与它看到的输入面；stderr 里只抹掉 sys.executable 的拼写
# （`-I -m` 那条报错的前缀是它自己，两边的可执行文件本来就不是同一个）。

import sys

# 与其它语料同一份参考库，两边读同一个 subprocess.py；子进程也需要它才能 import os。
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import os
import subprocess
import time

ENV = os.environ.copy()
ENV["PYTHONPATH"] = STD

SHOW = """\
import sys

print("argv", sys.argv)
print("path0", repr(sys.path[0]) if sys.path else "empty")
print("file", repr(globals().get("__file__")))
print("spec", repr(__spec__))
"""

with open("simplemod44.py", "w") as simple:
    simple.write("import sys\nprint(\"mod args\", sys.argv[1:])\n")


def run(tag, argv, data=None, quiet=False):
    done = subprocess.run([sys.executable, "-B"] + argv,
                          input=data, capture_output=True, env=ENV)
    print(tag, "rc", done.returncode)
    print(tag, "out", repr(done.stdout.decode()))
    if not quiet:
        print(tag, "err", repr(done.stderr.decode().replace(sys.executable, "EXE")))


# 没有程序名、标准输入是管道：流就是程序，argv 只有那一个空串。
run("stdin", [], SHOW.encode())
# 显式 `-`：同样的输入面，只有 argv[0] 换成 `-`，其后的词是它自己的参数。
run("stdin-dash", ["-"], SHOW.encode())
run("stdin-dash args", ["-", "x", "y"], SHOW.encode())
# 输入里的错误照样是未捕获异常，退出码 1；traceback 的源码行两边写法不同，这里只比退出码。
run("stdin-error", [], b"1/0", quiet=True)

# `-m` 之前的选项：`-I` 拿走工作目录，模块因此找不到；`-E`/`-B` 只是照常通过。
run("isolated -m", ["-I", "-m", "simplemod44"])
run("env -m", ["-E", "-B", "-m", "simplemod44", "z"])

# 未捕获的 KeyboardInterrupt 与 hook 自己抛出的那一个：都以信号终止，returncode 是 -2。
run("interrupt", ["-c", "raise KeyboardInterrupt"], quiet=True)
run("hook interrupt", ["-c",
                       "import sys\n"
                       "sys.excepthook = lambda *a: (_ for _ in ()).throw(KeyboardInterrupt)\n"
                       "raise ValueError('x')"], quiet=True)

# 真让 `kill -INT` 送达一个睡着了的子进程：它按默认动作终止，父进程读到 -2 而不是 130。
sleeper = subprocess.Popen([sys.executable, "-B", "-c",
                            "import time; time.sleep(30)"],
                           env=ENV, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(2.0)
subprocess.run(["kill", "-INT", str(sleeper.pid)])
sleeper.wait()
print("kill -INT rc", sleeper.returncode)
