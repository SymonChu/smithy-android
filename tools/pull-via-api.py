#!/usr/bin/env python3
"""
`github.com` 连不上时，把远端分支的提交**在本地精确重建**，让本地与远端哈希逐个一致。

为什么不是简单 `git fetch`：这机器的 `github.com:443` 不通（`git fetch` 直接超时），
但 `api.github.com` 通 —— 所以按 API 给的元数据（tree、父提交、作者/提交人、时间、信息）
用 `git commit-tree` 把每个提交对象**逐字节重建**，重建一个就校验一个：
只有重建出的哈希与远端完全相同，才继续下一个。全链对上之后才 `git reset --hard`。

这样本地与远端就是同一串对象，不会出现「内容一样、哈希不同」的分叉。

凭据：从 `~/.git-credentials` 读（不打印、不落盘、不进命令行）。
使用：`python3 tools/pull-via-api.py [--dry-run] [远端名] [分支]`
"""
from __future__ import annotations

import datetime as dt
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def git(*args: str, env: dict | None = None, check: bool = True, stdin: str | None = None) -> str:
    r = subprocess.run(["git", *args], capture_output=True, text=True, env=env, input=stdin)
    if check and r.returncode != 0:
        sys.exit(f"git {' '.join(args)} 失败：{r.stderr.strip()[:300]}")
    return r.stdout


def token() -> str:
    path = os.path.expanduser("~/.git-credentials")
    if os.path.exists(path):
        with open(path) as fh:
            for line in fh:
                m = re.match(r"https://([^:]+):([^@]+)@github\.com", line.strip())
                if m:
                    return m.group(2)
    sys.exit("拿不到 GitHub 凭据（~/.git-credentials 里没有）")


def call(url: str, tok: str) -> dict:
    req = urllib.request.Request(url)
    req.add_header("Authorization", f"token {tok}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "smithy-pull-via-api")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        sys.exit(f"GET {url} 失败：HTTP {e.code} {e.read().decode(errors='replace')[:300]}")


def epoch_of(iso: str) -> str:
    """API 给 ISO 8601（可能 Z 结尾）；提交对象里要的是 `<epoch> <tz>`。"""
    t = dt.datetime.fromisoformat(iso.replace("Z", "+00:00"))
    return f"{int(t.timestamp())} +0000" if t.utcoffset() == dt.timedelta(0) else f"{int(t.timestamp())} {t.strftime('%z')}"


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry-run" in sys.argv
    remote = args[0] if args else "origin"
    branch = args[1] if len(args) > 1 else "main"
    url = git("remote", "get-url", remote).strip()
    m = re.search(r"github\.com[:/]+([^/]+/[^/.]+)", url)
    if not m:
        sys.exit(f"从 {url} 里看不出 owner/repo")
    repo = m.group(1)
    tok = token()

    # 远端这条链（从新到旧），遇到本地已有的提交就停 —— 只重建缺的那一段
    chain, page = [], 1
    while True:
        batch = call(f"{API}/repos/{repo}/commits?sha={branch}&per_page=100&page={page}", tok)
        if not batch:
            break
        for c in batch:
            if git("cat-file", "-t", c["sha"], check=False).strip() == "commit":
                break
            chain.append(c)
        else:
            page += 1
            continue
        break
    if not chain:
        print("远端没有本地缺的提交，什么都不用做")
        return
    chain.reverse()  # 从旧到新重建

    local_head = git("rev-parse", "HEAD").strip()
    print(f"远端 {repo}#{branch}：要重建 {len(chain)} 个提交（本地 HEAD {local_head[:8]}）")
    # 重建的起点：最旧那个提交的父提交 —— 必须取 **API 给的父哈希**，
    # 不能用 `<远端 sha>^`（那个对象本地根本不存在，解析不了）。
    parents = chain[0].get("parents") or []
    parent = parents[0]["sha"] if parents else None
    if not parent or git("cat-file", "-t", parent, check=False).strip() != "commit":
        sys.exit(f"基线提交 {parent} 不在本地，没法逐字节重建（先把它弄到本地）")
    for c in chain:
        a, m_ = c["commit"]["author"], c["commit"]["committer"]
        # 不用 `git commit-tree`：它会对信息做自己的换行处理，拼出来的字节和 GitHub 存的不一样
        # （实测差一个尾部换行 —— GitHub 是把我给的信息原样收下，再补一个 "\n"）。
        # 直接按字节拼出提交对象，用 hash-object 落库：只要是同一个哈希就是同一串字节。
        head = (
            f"tree {c['commit']['tree']['sha']}\n"
            + "".join(f"parent {p['sha']}\n" for p in c["parents"])
            + f"author {a['name']} <{a['email']}> {epoch_of(a['date'])}\n"
            + f"committer {m_['name']} <{m_['email']}> {epoch_of(m_['date'])}\n"
            + "\n" + c["commit"]["message"]
        )
        # 尾部换行数不猜：把几种可能都算一遍，谁等于远端哈希就用谁（GitHub 会在我给的信息后面
        # 补一个换行，所以实际存下来的可能是一到两个）。都不对就直接停 —— 宁可报错也不能落错对象。
        sha = ""
        for trail in ("\n", "\n\n", "", "\n\n\n"):
            candidate = git("hash-object", "-t", "commit", "--stdin", stdin=head + trail).strip()
            if candidate == c["sha"]:
                sha = git("hash-object", "-t", "commit", "-w", "--stdin", "--literally", stdin=head + trail).strip()
                break
        ok = sha == c["sha"]
        print(f"  · {c['sha'][:8]} {c['commit']['message'].splitlines()[0][:44]} → 重建 {sha[:8]} {'✓' if ok else '✗ 不一致'}")
        if not ok:
            sys.exit("重建出的哈希与远端不同，停下来（不 reset），避免把本地弄成第三种状态")
        parent = sha

    if dry:
        print(f"试跑结束。真跑会把 {branch} 指到 {parent[:8]}")
        return
    git("reset", "--hard", parent)
    git("branch", "-f", "tmp-align", local_head)  # 退路：旧 HEAD 存成 tmp-align
    print(f"本地已对齐到远端：{branch} → {parent[:8]}（旧 HEAD 存在 tmp-align，确认没问题后可 git branch -D tmp-align）")


if __name__ == "__main__":
    main()
