#!/usr/bin/env python3
"""
github.com 连不上时，用 REST API 把本地提交推上去。

为什么需要它：有些网络里 `github.com:443` 被墙（`git push` 直接超时），
但 `api.github.com` 与 `uploads.github.com` 是通的 —— 那就别指望 `git push`，
改用 Git Data API 自己拼 tree/commit：

    POST /git/blobs   把每个变更文件的内容做成 blob
    POST /git/trees   按 `base_tree` 叠出这一版的目录树（保留文件模式，处理删除）
    POST /git/commits 生成一个提交，父提交指向远端当前 HEAD
    PATCH /git/refs   把分支指过去

**逐提交推**，历史与提交信息都保留，和 `git push` 的结果一致。

凭据：从 `~/.git-credentials` 里取（只读进内存，不打印、不落盘、不进命令行）。
使用：`python3 tools/push-via-api.py [--dry-run] [远端名] [分支]`
"""
from __future__ import annotations

import base64
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def git(*args: str) -> str:
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def token() -> str:
    """从 ~/.git-credentials 取 token。找不到就让 git 自己问（git credential fill）。"""
    path = os.path.expanduser("~/.git-credentials")
    if os.path.exists(path):
        with open(path) as fh:
            for line in fh:
                m = re.match(r"https://([^:]+):([^@]+)@github\.com", line.strip())
                if m:
                    return m.group(2)
    out = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True, check=True,
    ).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    sys.exit("拿不到 GitHub 凭据（~/.git-credentials 里没有，git credential fill 也没给）")


def call(method: str, url: str, tok: str, payload: dict | None = None) -> dict:
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"token {tok}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "smithy-push-via-api")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = resp.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")[:400]
        sys.exit(f"{method} {url} 失败：HTTP {e.code} {detail}")


def remote_repo(remote: str) -> str:
    url = git("remote", "get-url", remote).strip()
    m = re.search(r"github\.com[:/]+([^/]+/[^/.]+)", url)
    if not m:
        sys.exit(f"从 {url} 里看不出 owner/repo")
    return m.group(1)


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry-run" in sys.argv
    remote = args[0] if args else "origin"
    branch = args[1] if len(args) > 1 else "main"
    repo = remote_repo(remote)
    tok = token()

    head = git("rev-parse", "HEAD").strip()
    remote_sha = call("GET", f"{API}/repos/{repo}/git/ref/heads/{branch}", tok)["object"]["sha"]
    print(f"仓库 {repo}｜分支 {branch}")
    print(f"远端 {remote_sha[:8]} → 本地 {head[:8]}")

    todo = git("rev-list", "--reverse", f"{remote_sha}..{head}").split()
    if not todo:
        print("已经是最新的，没什么要推")
        return
    print(f"要推 {len(todo)} 个提交（{'试跑，不写远端' if dry else '逐提交推，保留历史'}）")

    parent = remote_sha
    for sha in todo:
        subject = git("log", "-1", "--format=%s", sha).strip()
        body_msg = git("log", "-1", "--format=%B", sha)
        # 这个提交相对它父提交的变更（含删除）
        stats = git("diff", "--name-status", f"{sha}^", sha).splitlines()
        entries = []
        for line in stats:
            parts = line.split("\t")
            status, path = parts[0], parts[-1]
            if status.startswith("D"):
                entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
                continue
            if status.startswith("R") or status.startswith("C"):
                old, new = parts[1], parts[2]
                entries.append({"path": old, "mode": "100644", "type": "blob", "sha": None})
                path = new
            mode = git("ls-tree", sha, "--", path).split()[0]  # 100644 / 100755 / 120000
            content = git("show", f"{sha}:{path}")
            blob = call(
                "POST", f"{API}/repos/{repo}/git/blobs", tok,
                {"content": base64.b64encode(content.encode()).decode(), "encoding": "base64"},
            )
            entries.append({"path": path, "mode": mode, "type": "blob", "sha": blob["sha"]})
        print(f"  · {sha[:8]} {subject[:50]}（{len(entries)} 个条目）")
        if dry:
            parent = sha
            continue
        tree = call("POST", f"{API}/repos/{repo}/git/trees", tok,
                    {"base_tree": call("GET", f"{API}/repos/{repo}/git/commits/{parent}", tok)["tree"]["sha"],
                     "tree": entries})
        commit = call("POST", f"{API}/repos/{repo}/git/commits", tok,
                      {"message": body_msg, "tree": tree["sha"], "parents": [parent]})
        parent = commit["sha"]

    if not dry:
        call("PATCH", f"{API}/repos/{repo}/git/refs/heads/{branch}", tok,
             {"sha": parent, "force": False})
        print(f"推完了：{branch} → {parent[:8]}")
        print(f"https://github.com/{repo}/commits/{branch}")


if __name__ == "__main__":
    main()
