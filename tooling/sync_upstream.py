#!/usr/bin/env python3
"""把上游 ccch1mneyyy/dsh-Go 的 main 合进本 fork。

fork 相对上游主要做了两件事：把 Kotlin 包名从
``io.github.ccch1mneyyy.dsh_app`` 改成 ``io.github.ouy09103.dsh_go_root``，
以及新增 root 相关文件。所以冲突基本是「我们只改了包名、上游改了内容」。
解决办法：把 base / theirs 也换成新包名后做一次真正的三方合并。

用法::

    python3 tooling/sync_upstream.py report   # 只报告，不改
    python3 tooling/sync_upstream.py push     # 解决 + 全量改名 + 提交
"""

from __future__ import annotations

import os
import subprocess
import sys

BASE: list[str] = []

OLD_DOT = "io.github.ccch1mneyyy.dsh_app"
NEW_DOT = "io.github.ouy09103.dsh_go_root"
OLD_DIR = OLD_DOT.replace(".", "/")
NEW_DIR = NEW_DOT.replace(".", "/")

BINARY_EXT = (".xz", ".zip", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".so",
              ".jar", ".apk", ".ttf", ".otf", ".wav", ".mp3", ".mp4", ".ico")


def git(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    p = subprocess.run(["git", *args], capture_output=True)
    if check and p.returncode != 0:
        sys.stderr.write(p.stdout.decode(errors="replace"))
        sys.stderr.write(p.stderr.decode(errors="replace"))
        raise SystemExit(f"git {' '.join(args)} failed ({p.returncode})")
    return p


def stage(n: int, path: str) -> bytes | None:
    p = git("show", f":{n}:{path}", check=False)
    return p.stdout if p.returncode == 0 else None


def to_new(path: str) -> str:
    return path.replace(OLD_DIR, NEW_DIR, 1)


def to_old(path: str) -> str:
    return path.replace(NEW_DIR, OLD_DIR, 1)


def norm(blob: bytes) -> bytes:
    return (blob.replace(OLD_DOT.encode(), NEW_DOT.encode())
                .replace(OLD_DIR.encode(), NEW_DIR.encode()))


def commit_blob(rev: str, path: str) -> bytes | None:
    p = git("show", f"{rev}:{path}", check=False)
    return p.stdout if p.returncode == 0 else None


def base_blob(path: str) -> bytes | None:
    """base 内容：直接问 merge-base 提交，先试新路径再试旧路径。"""
    rev = BASE[0]
    blob = commit_blob(rev, path)
    return blob if blob is not None else commit_blob(rev, to_old(path))


def pick(n: int, path: str) -> bytes | None:
    """先按冲突路径取，再按改名前的旧路径取。"""
    blob = stage(n, path)
    if blob is None:
        blob = stage(n, to_old(path))
    if blob is None and n == 1:
        blob = base_blob(path)
    return blob


def conflict_paths() -> list[str]:
    out = git("diff", "--name-only", "--diff-filter=U").stdout.decode()
    return [line for line in out.splitlines() if line.strip()]


def merge_file(ours: bytes, base: bytes, theirs: bytes) -> tuple[bool, bytes]:
    """三方合并，返回 (是否干净, 结果)。"""
    tmp = "/tmp/sync-merge"
    os.makedirs(tmp, exist_ok=True)
    paths = [f"{tmp}/ours", f"{tmp}/base", f"{tmp}/theirs"]
    for path, blob in zip(paths, (ours, base, theirs)):
        with open(path, "wb") as fh:
            fh.write(blob)
    p = subprocess.run(["git", "merge-file", "-p", "--diff3", *paths],
                       capture_output=True)
    return p.returncode == 0, p.stdout


def resolve(path: str) -> tuple[str, bytes | None, str]:
    """返回 (处置, 写入内容, 说明)。处置: write / delete / manual。"""
    ours = pick(2, path)
    base = pick(1, path)
    theirs = pick(3, path)

    if "/src/test/kotlin/io/github/ccch1mneyyy/" in path:
        # fork 把测试整体迁到新包名了，旧包名这份保持删除；CI 也不编译它们。
        return "delete", None, "旧包名测试，保持删除"
    if theirs is None:
        return "write", ours, "上游没有，保留我们"
    if base is None:
        # add/add：两边都新增，取上游。
        return "write", norm(theirs), "add/add，取上游"
    if ours is None:
        return "write", norm(theirs), "我们删了，取上游"
    if norm(ours) == norm(theirs):
        return "write", norm(theirs), "两边一致"
    if norm(ours) == norm(base):
        return "write", norm(theirs), "只改了包名，取上游"
    clean, merged = merge_file(ours, norm(base), norm(theirs))
    if clean:
        return "write", merged, "三方合并干净"
    # 真冲突：先落上游版本，随后单独把 fork 的改动补回来。
    return "manual", norm(theirs), "真冲突（先取上游，稍后补）"


def rename_pass() -> list[str]:
    """全量把旧包名换成新包名：先挪路径，再改内容。"""
    files = [f.decode() for f in git("ls-files", "-z").stdout.split(b"\0") if f]
    moved = 0
    collisions: list[str] = []
    for f in files:
        if OLD_DIR not in f:
            continue
        target = to_new(f)
        if os.path.exists(target):
            with open(target, "rb") as fh:
                existing = fh.read()
            with open(f, "rb") as fh:
                old = fh.read()
            if norm(old) == existing:
                git("rm", "-q", "-f", f)
                moved += 1
                continue
            collisions.append(f)
            continue
        os.makedirs(os.path.dirname(target) or ".", exist_ok=True)
        git("mv", "-f", f, target)
        moved += 1
    print(f"rename pass: 移动 {moved} 个路径，撞名 {len(collisions)} 个")
    for f in collisions:
        print("  撞名:", f)
    touched = 0
    files = [f.decode() for f in git("ls-files", "-z").stdout.split(b"\0") if f]
    for f in files:
        if f.endswith(BINARY_EXT) or not os.path.isfile(f):
            continue
        with open(f, "rb") as fh:
            blob = fh.read()
        fixed = norm(blob)
        if fixed == blob:
            continue
        with open(f, "wb") as fh:
            fh.write(fixed)
        touched += 1
    print(f"rename pass: 改写 {touched} 个文件内容")
    git("add", "-A")
    return collisions


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "report"
    git("config", "user.name", "dsh-go sync bot")
    git("config", "user.email", "sync@users.noreply.github.com")
    git("config", "merge.renameLimit", "200000")
    git("config", "diff.renameLimit", "200000")
    git("remote", "add", "upstream", "https://github.com/ccch1mneyyy/dsh-Go.git", check=False)
    git("fetch", "--no-tags", "upstream", "main")
    print("upstream head:", git("rev-parse", "upstream/main").stdout.decode().strip())
    BASE.append(git("merge-base", "HEAD", "upstream/main").stdout.decode().strip())
    print("merge base   :", BASE[0])

    p = subprocess.run(["git", "merge", "--no-ff", "--no-commit", "upstream/main"],
                       capture_output=True)
    print("merge exit:", p.returncode)

    paths = conflict_paths()
    print(f"=== 冲突 {len(paths)} 个 ===")
    manual: list[str] = []
    stats: dict[str, int] = {}
    for path in paths:
        kind, content, why = resolve(path)
        stats[why] = stats.get(why, 0) + 1
        if kind == "manual":
            manual.append(path)
        if mode == "push":
            os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
            if content is None:
                git("rm", "-q", "-f", "--ignore-unmatch", path, check=False)
            else:
                with open(path, "wb") as fh:
                    fh.write(content)
                git("add", "--", path)

    print("=== 处置统计 ===")
    for why, n in sorted(stats.items()):
        print(f"  {why}: {n}")
    if manual:
        print("=== 需要人工处理 ===")
        for path in manual:
            print(" ", path)
    if mode == "report":
        git("merge", "--abort", check=False)
        return 0

    collisions = rename_pass()

    if manual:
        print("=== 已先取上游、待补 fork 改动的文件 ===")
        with open("/tmp/manual.txt", "w") as fh:
            for path in manual:
                print(" ", path)
                fh.write(path + "\n")

    left = conflict_paths()
    if left or collisions:
        print("改名后仍有冲突：", left, collisions)
        return 1

    markers = git("grep", "-n", "-I", "-e", "^<<<<<<< ", "-e", "^>>>>>>> ",
                  check=False).stdout.decode()
    if markers.strip():
        print("=== 还有冲突标记 ===")
        print(markers[:4000])
        return 1
    print("=== 合并完成，无冲突标记 ===")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
