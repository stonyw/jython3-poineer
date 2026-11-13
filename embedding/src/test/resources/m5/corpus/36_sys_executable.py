# sys.executable：启动器自身的绝对路径（M5 切片 34）。
#
# 参考与本运行时不可能给出同一个字符串（一个是 python3.14，一个是 jy3 的安装脚本），所以这里
# 一条路径都不打印，只打印两边都必须成立的性质：绝对、存在、可执行。三种启动形态各走一遍——
# 脚本本体、`-c`、`-S`，再加一次 stdin 的 `-` 形式——每次都是真的起一个子进程，子进程再用
# 自己的 sys.executable 回答同一组性质；子进程能起来本身就说明这个路径能被 exec。

import sys

# 与其它语料同一份参考库，两边读同一个 subprocess.py；子进程也需要它才能 import os。
STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import os
import subprocess

PROBE = ("import os, sys\n"
         "print(os.path.isabs(sys.executable), os.path.isfile(sys.executable),"
         " os.access(sys.executable, os.X_OK))\n")

ENV = os.environ.copy()
ENV["PYTHONPATH"] = STD


def properties():
    """The three answers that have to hold wherever the interpreter was started from."""
    return (os.path.isabs(sys.executable), os.path.isfile(sys.executable),
            os.access(sys.executable, os.X_OK))


if os.environ.get("JY3_SLICE34_CHILD"):
    # 脚本形态的子进程：只回答启动器交给它的 sys.executable，然后退出，不再递归。
    print("script-form", *properties())
    raise SystemExit(0)


def child(tag, argv, data=None, **extra):
    env = dict(ENV, **extra)
    done = subprocess.run([sys.executable] + argv, input=data, capture_output=True, env=env)
    print(tag, done.returncode, done.stdout.decode().strip(), done.stderr)


print("this-run", *properties())

child("script", [os.path.abspath(__file__)], JY3_SLICE34_CHILD="1")
child("script -S", ["-S", os.path.abspath(__file__)], JY3_SLICE34_CHILD="1")
child("-c", ["-c", PROBE])
child("-S -c", ["-S", "-c", PROBE])
child("stdin", ["-"], PROBE.encode())
