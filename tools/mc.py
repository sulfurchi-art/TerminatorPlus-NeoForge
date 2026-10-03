"""Type commands into the running Minecraft server's console window and print what the server logged.

usage: python mc.py [--wait SECONDS] "command 1" ["command 2" ...]
                                       run commands and print their output; --wait keeps reading the log SECONDS
                                       longer, for delayed effects such as command blocks
       python mc.py --log [N]         print the last N interesting log lines (chat, joins, deaths, commands)
       python mc.py --say TEXT         broadcast TEXT (any language) to all players
       python mc.py --stop [SECONDS [COMMAND ...]]
                                       warn players SECONDS ahead (default 0), run COMMANDs, stop the server and
                                       close its window
       python mc.py --start            start the server in a new window and wait until it is ready
"""
import ctypes
import json
import os
import re
import subprocess
import sys
import time
from ctypes import wintypes

SERVER_DIR = r"G:\PCL\Server-1.21.1-NeoForge_21.1.249"
LOG = os.path.join(SERVER_DIR, "logs", "latest.log")

k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.CreateFileW.restype = wintypes.HANDLE
k32.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, wintypes.LPVOID, wintypes.DWORD,
                            wintypes.DWORD, wintypes.HANDLE]
k32.OpenProcess.restype = wintypes.HANDLE
k32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
k32.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]


class KEY_EVENT_RECORD(ctypes.Structure):
    _fields_ = [("bKeyDown", wintypes.BOOL), ("wRepeatCount", wintypes.WORD), ("wVirtualKeyCode", wintypes.WORD),
                ("wVirtualScanCode", wintypes.WORD), ("uChar", wintypes.WCHAR), ("dwControlKeyState", wintypes.DWORD)]


class EVENT(ctypes.Union):
    _fields_ = [("KeyEvent", KEY_EVENT_RECORD)]


class INPUT_RECORD(ctypes.Structure):
    _fields_ = [("EventType", wintypes.WORD), ("Event", EVENT)]


def server_procs():
    """(java pid, pid of the cmd window running run.bat) of the running server, or None."""
    out = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
         "Where-Object { $_.CommandLine -like '*neoforge*21.1.249*win_args*' } | "
         "ForEach-Object { \"$($_.ProcessId) $($_.ParentProcessId)\" }"],
        capture_output=True, text=True).stdout.split()
    return (int(out[0]), int(out[1])) if out else None


def server_pid():
    procs = server_procs()
    if not procs:
        sys.exit("server is not running")
    return procs[0]


def wait_exit(pid, timeout):
    """True once the process has exited, False if it is still running after timeout seconds."""
    handle = k32.OpenProcess(0x00100000, False, pid)  # SYNCHRONIZE
    if not handle:
        return True  # already gone
    try:
        return k32.WaitForSingleObject(handle, int(timeout * 1000)) == 0  # WAIT_OBJECT_0
    finally:
        k32.CloseHandle(handle)


def type_lines(pid, lines):
    k32.FreeConsole()
    if not k32.AttachConsole(pid):
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        handle = k32.CreateFileW("CONIN$", 0xC0000000, 3, None, 3, 0, None)
        if handle in (None, wintypes.HANDLE(-1).value):
            raise ctypes.WinError(ctypes.get_last_error())
        for line in lines:
            text = line + "\r"
            records = (INPUT_RECORD * (len(text) * 2))()
            for i, ch in enumerate(text):
                for j, down in enumerate((True, False)):
                    rec = records[i * 2 + j]
                    rec.EventType = 1  # KEY_EVENT
                    rec.Event.KeyEvent.bKeyDown = down
                    rec.Event.KeyEvent.wRepeatCount = 1
                    rec.Event.KeyEvent.wVirtualKeyCode = 0x0D if ch == "\r" else 0
                    rec.Event.KeyEvent.uChar = ch
            written = wintypes.DWORD()
            if not k32.WriteConsoleInputW(handle, records, len(records), ctypes.byref(written)):
                raise ctypes.WinError(ctypes.get_last_error())
            time.sleep(0.15)
        k32.CloseHandle(handle)
    finally:
        k32.FreeConsole()


# Typed after the real commands. The server answers it only on the console ("Seed: [...]"), so once that line is in
# the log every command typed before it has been executed; the console can lag several seconds behind the typing.
BARRIER = "seed"


def read_from(offset):
    with open(LOG, encoding="utf-8", errors="replace") as f:
        f.seek(offset)
        return f.readlines()


def run(pid, commands, extra=0.0, timeout=30):
    """Type commands, wait until the server has executed them, return the log lines written meanwhile."""
    start = os.path.getsize(LOG)
    type_lines(pid, [*commands, BARRIER])
    deadline = time.time() + timeout
    while not any("Seed: [" in l for l in read_from(start)):
        if time.time() > deadline:
            return read_from(start) + ["(the server has not executed these commands yet)\n"]
        time.sleep(0.3)
    time.sleep(extra)
    return [l for l in read_from(start) if "Seed: [" not in l]


def say(pid, text):
    # json.dumps escapes non-ASCII as \uXXXX, so what gets typed is pure ASCII whatever the console code page is
    msg = json.dumps({"text": "[服务器] " + text, "color": "yellow"}, separators=(",", ":"))
    type_lines(pid, ["tellraw @a " + msg])


def stop(warn, commands=()):
    procs = server_procs()
    if not procs:
        sys.exit("server is not running")
    pid, window = procs
    if warn > 0:
        say(pid, f"服务器将在 {warn:g} 秒后重启，约 1 分钟后可以重新连接")
        time.sleep(warn)
    if commands:
        for line in run(pid, commands):
            if not NOISE.search(line):
                print(strip(line))
        time.sleep(1.5)  # let killed mobs finish dying (20 ticks), otherwise they are saved mid-death
    type_lines(pid, ["stop"])
    if not wait_exit(pid, 180):
        sys.exit("server is still running 3 minutes after stop")
    # run.bat ends with "pause": press Enter in its window so the window closes
    time.sleep(1)
    try:
        type_lines(window, [""])
    except OSError:
        pass  # the window is already gone
    print("server stopped" if wait_exit(window, 5) else "server stopped (its console window is still open)")


def first_log_line():
    try:
        with open(LOG, encoding="utf-8", errors="replace") as f:
            return f.readline()
    except OSError:
        return None


def start(timeout=300):
    if server_procs():
        sys.exit("server is already running")
    before = first_log_line()
    # Start-Process gives the server its own console window, the same way it was first launched
    subprocess.run(["powershell", "-NoProfile", "-Command",
                    f"Start-Process -FilePath '{os.path.join(SERVER_DIR, 'run.bat')}' -WorkingDirectory '{SERVER_DIR}'"],
                   check=True)
    began = time.time()
    while time.time() - began < timeout:
        time.sleep(3)
        if first_log_line() not in (None, before):  # log4j has rolled latest.log over for this run
            with open(LOG, encoding="utf-8", errors="replace") as f:
                done = [l for l in f if 'For help, type "help"' in l]
            if done:
                print(strip(done[-1]))
                return
        if time.time() - began > 30 and not server_procs():
            sys.exit("the server process exited during startup, check logs/latest.log")
    sys.exit(f"the server is not ready after {timeout}s, check logs/latest.log")


NOISE = re.compile(r"\[mixin/\]|Can't keep up|moved too quickly|moved wrongly")


def strip(line):
    # "[04 Oct 2026 04:16:58.191] [Server thread/INFO] [logger/]: message" -> "04:16:58 message"
    m = re.match(r"\[[^\]]*?(\d\d:\d\d:\d\d)\.\d+\] \[([^\]]*)\] \[[^\]]*\]: (.*)", line)
    if not m:
        return line.rstrip()
    level = m.group(2).split("/")[-1]
    return f"{m.group(1)} {'' if level == 'INFO' else level + ' '}{m.group(3)}".rstrip()


def main():
    args = sys.argv[1:]
    if args and args[0] == "--log":
        n = int(args[1]) if len(args) > 1 else 30
        with open(LOG, encoding="utf-8", errors="replace") as f:
            # chat, joins, deaths, advancements and command feedback are all logged by MinecraftServer
            lines = [l for l in f if "[net.minecraft.server.MinecraftServer/]" in l or "issued server command" in l]
        for l in lines[-n:]:
            print(strip(l))
        return
    if args and args[0] == "--say":
        say(server_pid(), " ".join(args[1:]))
        return
    if args and args[0] == "--stop":
        stop(float(args[1]) if len(args) > 1 else 0, args[2:])
        return
    if args and args[0] == "--start":
        start()
        return

    wait = 0.0
    if args and args[0] == "--wait":
        wait = float(args[1])
        args = args[2:]
    if not args:
        sys.exit(__doc__)

    for line in run(server_pid(), args, wait):
        if not NOISE.search(line):
            print(strip(line))


if __name__ == "__main__":
    main()
