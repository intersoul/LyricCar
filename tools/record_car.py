#!/usr/bin/env python3
"""车机录屏：把车机屏幕录成 mp4 并拉回本地。

用法（在项目根目录跑）：

    python3 tools/record_car.py                 # 立刻录 60 秒，拉到 footage/
    python3 tools/record_car.py -s 120          # 录 120 秒
    python3 tools/record_car.py --wait          # 先等「歌在放 + 歌词在滚」，再录 60 秒
    python3 tools/record_car.py --wait -s 90 -n A6_lan_局域网

为什么要有 --wait：App 只在「当前句」变化时才重绘（这是故意的，为了别一秒重建
4 次），所以画面不动时录下来就是一整分钟静止帧，剪片子没法用。--wait 会盯着播放
状态和 App 日志，等真的滚起来了才开录。

只依赖 adb；装了 ffmpeg 的话录完顺手打一份时长 / 分辨率。
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUTDIR = PROJECT_ROOT / "footage"

# PlaybackState.state：3 = 正在播放，2 = 暂停
STATE_PLAYING = 3


def find_adb() -> str:
    """找 adb：环境变量 → PATH → Android SDK 常见位置。"""
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    found = shutil.which("adb")
    if found:
        return found
    candidates = [
        Path.home() / "Library/Android/sdk/platform-tools/adb",  # macOS
        Path.home() / "Android/Sdk/platform-tools/adb",  # Linux
        Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe",
    ]
    for path in candidates:
        if path.is_file():
            return str(path)
    sys.exit("找不到 adb：装 Android platform-tools，或设环境变量 ADB=/path/to/adb")


ADB = find_adb()


def adb(*args: str, timeout: int = 60) -> str:
    return subprocess.run(
        [ADB, *args], capture_output=True, text=True, timeout=timeout
    ).stdout


def check_device() -> None:
    devices = [l for l in adb("devices").splitlines()[1:] if l.strip()]
    ready = [l for l in devices if l.endswith("\tdevice")]
    if not ready:
        sys.exit(f"没有已授权的车机：{devices or '一台都没连上'}")
    print(f"设备：{ready[0].split()[0]}")


def play_state() -> tuple[int, int] | None:
    """读车机蓝牙播放器的 (state, position_ms)，读不到返回 None。"""
    dump = adb("shell", "dumpsys", "media_session")
    match = re.search(
        r"package=com\.android\.bluetooth\s*\n\s*state=PlaybackState \{state=(\d+), position=(-?\d+)",
        dump,
    )
    return (int(match.group(1)), int(match.group(2))) if match else None


def saw_replay_mode() -> bool:
    """App 最新一次状态切换是不是「回放」（= 歌词正在按时间戳滚）。"""
    log = adb("logcat", "-d", "-t", "300")
    switches = [l for l in log.splitlines() if "状态 →" in l]
    return bool(switches) and "replay" in switches[-1]


def wait_for_good_moment(timeout: int, verbose: bool = True) -> str:
    """等「歌在放 + 歌词在滚」。等到返回一段说明，等不到返回空串。"""
    adb("logcat", "-c")  # 清一下，只认开录之前的状态
    started = time.time()
    prev: int | None = None
    advancing = 0
    while time.time() - started < timeout:
        state = play_state()
        pos = state[1] if state else None
        playing = state is not None and state[0] == STATE_PLAYING
        if playing and pos is not None and prev is not None and pos != prev:
            advancing += 1
        else:
            advancing = 0
        replay = saw_replay_mode()
        if verbose:
            print(
                f"  [{int(time.time() - started):3d}s] state={state[0] if state else '-'} "
                f"pos={pos} 在走={bool(advancing)} 回放模式={replay}",
                flush=True,
            )
        if advancing >= 2 and replay:
            return "回放中（歌词在滚）"
        # 已经稳定在放了，只是还没进回放：不等了，录采集过程也行
        if advancing >= 2 and time.time() - started > 90:
            return "播放中（非回放）"
        prev = pos
        time.sleep(3)
    return ""


def record(seconds: int, remote: str) -> None:
    print(f"开录 {seconds} 秒…（车机正在录屏，别拔线）")
    adb(
        "shell",
        "screenrecord",
        "--size",
        "1280x720",
        "--bit-rate",
        "8000000",
        "--time-limit",
        str(seconds),
        remote,
        timeout=seconds + 60,
    )


def pull(remote: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"拉回本地：{dest}")
    adb("pull", remote, str(dest), timeout=300)
    adb("shell", "rm", "-f", remote)  # 车机上不留垃圾


def probe(path: Path) -> dict[str, str]:
    """ffprobe 拿基本信息；没装 ffprobe 就返回空。"""
    if not shutil.which("ffprobe"):
        return {}
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-show_entries", "stream=width,height", "-of", "default=nw=1", str(path)],
        capture_output=True, text=True,
    ).stdout
    info = dict(
        line.split("=", 1) for line in out.splitlines() if "=" in line
    )
    return {k: v for k, v in info.items() if v != "N/A"}


def frame_count(path: Path) -> int:
    """数一下真实帧数 —— 车机只在画面变化时才出帧，帧太少说明录的是静止画面。"""
    if not shutil.which("ffprobe"):
        return 0
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v",
         "-show_entries", "frame=pts_time", "-of", "csv=p=0", str(path)],
        capture_output=True, text=True,
    ).stdout
    return len([l for l in out.splitlines() if l.strip()])


def describe(path: Path, seconds: int) -> None:
    info = probe(path)
    frames = frame_count(path)
    parts = [f"{path.name}", f"{path.stat().st_size / 1024 / 1024:.2f} MB"]
    if info.get("width"):
        parts.append(f"{info['width']}x{info['height']}")
    if info.get("duration"):
        parts.append(f"{float(info['duration']):.1f} 秒")
    if frames:
        parts.append(f"{frames} 帧")
    print("　".join(parts))
    if frames and frames < seconds * 2:
        print(
            "⚠ 帧数偏少：这段时间里画面几乎没变（App 只在「当前句」变化时重绘）。\n"
            "  下次加 --wait，让它等歌词真的滚起来再开录。"
        )


def main() -> None:
    parser = argparse.ArgumentParser(description="车机录屏到本地")
    parser.add_argument("-s", "--seconds", type=int, default=60, help="录多久，默认 60")
    parser.add_argument("-n", "--name", default="", help="文件名（不给就按时间命名）")
    parser.add_argument(
        "--wait",
        action="store_true",
        help="先等「歌在放 + 歌词在滚」再开录，最多等 --wait-timeout 秒",
    )
    parser.add_argument("--wait-timeout", type=int, default=240, help="--wait 最多等多久")
    parser.add_argument("-o", "--outdir", type=Path, default=DEFAULT_OUTDIR, help="拉到哪个目录")
    args = parser.parse_args()

    check_device()
    remote = "/sdcard/lyriccar_record.mp4"

    if args.wait:
        print("等一个好时机：正在播放 + 歌词真的在滚…")
        reason = wait_for_good_moment(args.wait_timeout)
        if not reason:
            sys.exit("等了这么久也没等到在放歌，先确认手机在播、车机连着蓝牙")
        print(f"可以了：{reason}")

    name = args.name or time.strftime("record_%Y%m%d-%H%M%S")
    dest = args.outdir / (name if name.endswith(".mp4") else f"{name}.mp4")

    record(args.seconds, remote)
    pull(remote, dest)
    describe(dest, args.seconds)
    print("提示：可变帧率素材剪视频前先转固定帧率 ——")
    print(f"  ffmpeg -i {dest} -vf fps=30 -c:v libx264 -crf 18 -pix_fmt yuv420p {dest.with_suffix('.30fps.mp4')}")


if __name__ == "__main__":
    main()
