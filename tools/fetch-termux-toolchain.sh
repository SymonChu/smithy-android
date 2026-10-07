#!/usr/bin/env bash
# 从 Termux 仓库收集一份 **能在手机上直接跑** 的 C/C++ 工具链闭包，产出的 zip 走
# Smithy 的「导入工具链包…」就能装。
#
# 为什么是 Termux 而不是 NDK / LLVM 官方：
#   - NDK 只发 x86_64 / darwin / windows 宿主机版 —— 在 arm64 手机上跑不起来；
#   - LLVM 官方 release 没有 android 目标（17/18/19 的资产表都核过）；
#   - Termux 的包是 **bionic + 原生 aarch64** 构建，能在应用沙箱里直接 execve（免 root）。
#
# 做法参考 Soodok/Deepseek-Harness-Local-Android（MIT）的
# scripts/collect-termux-runtime.sh 与 engine/ExtensionManager.kt：
#   索引 → 依赖闭包 → 逐包 SHA-256 → 镜像 failover → 解包 → 前缀处理 → 原子产出。
#
# 用法： ./fetch-termux-toolchain.sh [架构=aarch64] [输出zip]
set -euo pipefail

ARCH="${1:-aarch64}"
OUT_ZIP="${2:-termux-clang-${ARCH}.tar.gz}"
case "$ARCH" in aarch64|x86_64) ;; *) echo "只支持 aarch64 / x86_64（Termux 只有这两个）" >&2; exit 2;; esac

MIRRORS=(
  "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"
  "https://mirrors.ustc.edu.cn/termux/apt/termux-main"
  "https://mirrors.bfsu.edu.cn/termux/apt/termux-main"
  "https://packages.termux.dev/apt/termux-main"
)

# 要编一个 Zygisk 模块，需要：编译器本体 + **bionic 的头与桩库**。
# clang 的 Depends 会把 llvm / lld / libllvm / libcompiler-rt / ndk-sysroot 一起带进来，
# 闭包由索引解析，不手写清单。
#
# ndk-multilib-native-stubs 是**显式加上**的：链接期的平台桩库（`liblog.so`、`libc.so`），
# ndk-sysroot 只给头与少数几个库；缺了它的表现是 `cannot find -llog`，而 Zygisk 模块都要 log。
#
# **刻意不要 `make`**：一是这条路用不到（Smithy 直接调 clang++，不生成 Makefile），
# 二是 make 是 GPL-3.0 —— 要再分发这份 bundle 就得承担 GPL 的义务。
# 参考项目同样「刻意不打包任何 GPL 工具链组件」（见 docs/07）。
ROOTS=(clang ndk-sysroot ndk-multilib-native-stubs)
TERMUX_PREFIX="/data/data/com.termux/files/usr"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
ROOT="$WORK/root"
mkdir -p "$ROOT"

# .deb 缓存：反复调脚本时不必重下几百 MB
CACHE_DIR="${SMITHY_TERMUX_CACHE:-${TMPDIR:-/tmp}/smithy-termux-cache}"
mkdir -p "$CACHE_DIR"

echo "== 1/6 选一个能用的镜像"
IDX_URL=""
for m in "${MIRRORS[@]}"; do
  url="$m/dists/stable/main/binary-$ARCH/Packages.gz"
  if curl -fsSL --max-time 30 -o "$WORK/Packages.gz" "$url" 2>/dev/null; then
    IDX_URL="$m"; echo "   用：$m"; break
  fi
  echo "   跳过（不可达）：$m"
done
[ -n "$IDX_URL" ] || { echo "所有镜像都不可达 —— 网络问题，不是脚本问题" >&2; exit 3; }

echo "== 2/6 解析索引并算依赖闭包（从 ${ROOTS[*]} 出发）"
python3 - "$WORK/Packages.gz" "$WORK/meta.tsv" "${ROOTS[@]}" <<'PY'
import gzip, sys
idx_path, meta_path, *roots = sys.argv[1:]
pkgs = {}
with gzip.open(idx_path, 'rt', errors='replace') as fh:
    cur = {}
    for line in fh:
        line = line.rstrip('\n')
        if not line:
            if cur.get('Package'):
                pkgs[cur['Package']] = cur
            cur = {}
            continue
        if line.startswith(' ') or ':' not in line:
            continue
        k, v = line.split(':', 1)
        cur[k.strip()] = v.strip()
    if cur.get('Package'):
        pkgs[cur['Package']] = cur

def deps(rec):
    out = []
    for part in (rec.get('Depends') or '').split(','):
        part = part.strip()
        if not part:
            continue
        name = part.split('(')[0].split(':')[0].strip()
        if name:
            out.append(name)
    return out

seen, stack, missing = set(), list(roots), []
while stack:
    name = stack.pop()
    if name in seen:
        continue
    seen.add(name)
    rec = pkgs.get(name)
    if rec is None:
        missing.append(name)
        continue
    stack.extend(deps(rec))

if missing:
    sys.exit("索引里找不到这些包（Termux 改过名？）：" + ", ".join(sorted(set(missing))))

with open(meta_path, 'w') as out:
    for name in sorted(seen):
        r = pkgs[name]
        out.write('\t'.join([
            name, r.get('Version', '?'), r.get('Filename', ''), r.get('SHA256', ''),
            str(int(r.get('Installed-Size', '0') or 0)),
        ]) + '\n')
print(f"   闭包 {len(seen)} 个包")
PY
awk -F'\t' '{printf "   %-22s %-14s %6.1f MB\n", $1, $2, $5/1024}' "$WORK/meta.tsv"

echo "== 3/6 逐包下载 + SHA-256 校验（按镜像 failover）"
mkdir -p "$WORK/debs"
total_kb=$(awk -F'\t' '{s+=$5} END {print s+0}' "$WORK/meta.tsv")
echo "   安装后合计约 $(awk -v s="$total_kb" 'BEGIN{printf "%.0f", s/1024}') MB"
while IFS=$'\t' read -r name ver filename sha size; do
  [ -n "$filename" ] || { echo "   $name 没有 Filename 字段，跳过"; continue; }
  dest="$WORK/debs/$(basename "$filename")"
  cached="$CACHE_DIR/$(basename "$filename")"
  if [ -f "$cached" ]; then
    if [ -z "$sha" ] || [ "$(sha256sum "$cached" | cut -d' ' -f1)" = "${sha,,}" ]; then
      cp "$cached" "$dest"; printf '   ↩ %-22s %-14s 用缓存\n' "$name" "$ver"; continue
    fi
    echo "   缓存里那份 sha256 不对，重下：$name"
  fi
  got=0
  for m in "$IDX_URL" "${MIRRORS[@]}"; do
    if curl -fsSL --max-time 600 -o "$dest" "$m/$filename" 2>/dev/null; then got=1; break; fi
    echo "   $name：$m 失败，换下一个镜像"
  done
  [ "$got" = 1 ] || { echo "   $name 所有镜像都下不动" >&2; exit 4; }
  if [ -n "$sha" ]; then
    actual=$(sha256sum "$dest" | cut -d' ' -f1)
    [ "$actual" = "${sha,,}" ] || { echo "   $name SHA-256 不一致！索引说 $sha，实际 $actual" >&2; exit 5; }
  else
    echo "   $name 索引里没有 SHA256 字段（少见的包），只校验了体积"
  fi
  printf '   ✓ %-22s %-14s %s\n' "$name" "$ver" "$(basename "$filename")"
  cp "$dest" "$cached"
done < "$WORK/meta.tsv"

echo "== 4/6 解包合并"
command -v ar >/dev/null || { echo "需要 ar（binutils）来解 .deb" >&2; exit 6; }
# Termux 的 deb 里存的是**绝对路径**：./data/data/com.termux/files/usr/bin/clang。
# 所以各自解到独立目录，再按前缀搬到 bundle 根下。
for deb in "$WORK"/debs/*.deb; do
  d="$WORK/unpack/$(basename "$deb" .deb)"
  mkdir -p "$d/tree"
  ( cd "$d" && ar x "$deb" && tar -xJf data.tar.xz -C "$d/tree" )
done
python3 - "$WORK/unpack" "$ROOT" "$TERMUX_PREFIX" <<'PY'
import os, shutil, sys
unpack, root, prefix = sys.argv[1], sys.argv[2], sys.argv[3]
prefix_path = prefix.lstrip('/')

def rewrite_link(target, link_dir_rel):
    """把指向 Termux 前缀的绝对软链接改成包内相对路径 —— 包落在哪个目录都成立。"""
    if not target.startswith(prefix + '/'):
        return target
    inner = target[len(prefix) + 1:]
    depth = len([p for p in link_dir_rel.split('/') if p])
    up = '/'.join(['..'] * depth)
    return (up + '/' + inner) if up else inner

files = links = 0
for base, _dirs, names in os.walk(unpack):
    for name in names:
        if not name.startswith('data.tar'):
            continue
        src_root = os.path.join(base, 'tree', prefix_path)
        if not os.path.isdir(src_root):
            continue
        for dirpath, _d, entries in os.walk(src_root):
            rel_dir = os.path.relpath(dirpath, src_root)
            rel_dir = '' if rel_dir == '.' else rel_dir
            out_dir = os.path.join(root, rel_dir)
            os.makedirs(out_dir, exist_ok=True)
            for n in entries:
                s = os.path.join(dirpath, n)
                d = os.path.join(out_dir, n)
                if os.path.islink(s):
                    tgt = rewrite_link(os.readlink(s), rel_dir)
                    if os.path.lexists(d):
                        os.remove(d)
                    os.symlink(tgt, d)
                    links += 1
                else:
                    shutil.copy2(s, d)
                    files += 1
print(f"   落地：{files} 个文件、{links} 个软链接")
PY
echo "   合并后：$(du -sh "$ROOT" | cut -f1)"

echo "== 5/6 前缀情况如实报出来 + 布局归一"
# Termux 的包把 PREFIX 写死在内容里（shebang、脚本里的绝对路径）。**构建期不改写**：
# 包最终装在手机上哪个目录要安装时才知道；而且我们要跑的 clang++/make 是 ELF 程序，
# 不靠 shebang，动态库靠运行时 LD_LIBRARY_PATH、头与桩库靠显式 --sysroot/-isystem/-L
# （Smithy 的驱动本来就是这么拼的）。所以这里只把情况说清楚。
echo "   含硬编码前缀 \`$TERMUX_PREFIX\` 的文件数："
echo "     文本（脚本/shebang）：$(grep -rlI "$TERMUX_PREFIX" "$ROOT" 2>/dev/null | wc -l)"
echo "     二进制（RUNPATH 一类）：$(grep -rl "$TERMUX_PREFIX" "$ROOT/bin" "$ROOT/lib" 2>/dev/null | wc -l)"

if [ -d "$ROOT/opt/ndk-sysroot" ] && [ ! -e "$ROOT/sysroot" ]; then
  ln -s opt/ndk-sysroot "$ROOT/sysroot"
  echo "   已把 opt/ndk-sysroot 软链成 sysroot（Smithy 的 locateIn 认这个布局）"
fi

# 只留本机 ABI 需要的东西。
# 注意：前缀根下的 `<abi>/lib/*.so` 是**软链接**，指向 opt/ndk-multilib/<abi>/lib 里的真文件 ——
# 所以 opt/ndk-multilib 里那份目标 ABI **不能删**（删了就全是断链接，编译时报
# 「cannot find -llog」，而那是最难倒查的一类错）。能删的是：
#   - cross-compiler-rt（190MB，给「在电脑上跨 ABI 编」用的运行时）
#   - 另外三个 ABI 的桩库
KEEP_ABI="aarch64-linux-android"; [ "$ARCH" = "x86_64" ] && KEEP_ABI="x86_64-linux-android"
if [ -d "$ROOT/opt/ndk-multilib" ]; then
  rm -rf "$ROOT/opt/ndk-multilib/cross-compiler-rt"
  echo "   删掉 opt/ndk-multilib/cross-compiler-rt（跨 ABI 编才需要，190MB）"
  for d in "$ROOT/opt/ndk-multilib"/*-linux-android*; do
    [ -d "$d" ] || continue
    if [ "$(basename "$d")" != "$KEEP_ABI" ]; then
      rm -rf "$d"; echo "   删掉别的 ABI 的桩库正文：$(basename "$d")"
    fi
  done
fi
for d in "$ROOT"/*-linux-android*; do
  [ -d "$d" ] || continue
  if [ "$(basename "$d")" != "$KEEP_ABI" ]; then
    rm -rf "$d"; echo "   删掉别的 ABI 的桩链接：$(basename "$d")"
  fi
done
# 断链检查：这一步是我自己踩过的坑（先删正文再删链接的顺序错了，包出来全是坏链接）
[ -f "$ROOT/$KEEP_ABI/lib/liblog.so" ] || {
  echo "桩库 $KEEP_ABI/lib/liblog.so 不可用（软链接断了？）—— 打包会产出一个不能用的包" >&2
  ls -l "$ROOT/$KEEP_ABI/lib/liblog.so" >&2
  exit 8
}
broken=$(find "$ROOT" -xtype l 2>/dev/null | wc -l)
[ "$broken" = 0 ] || echo "   注意：有 $broken 个断掉的软链接（Termux 包里本来就有的）"
if [ ! -x "$ROOT/bin/clang++" ]; then
  echo "包里没有可执行的 bin/clang++，内容如下：" >&2
  ls -l "$ROOT" >&2
  ls -l "$ROOT/bin" 2>/dev/null | head >&2
  exit 7
fi
echo "   clang++：$( ls -l "$ROOT/bin/clang++" | sed 's/  */ /g' | cut -d' ' -f9- )"
echo "   ndk-sysroot 布局：$( ls "$ROOT/opt/ndk-sysroot" 2>/dev/null | head -4 | tr '\n' ' ' )"
echo "   sysroot 里的 jni 头：$( ls "$ROOT/sysroot/usr/include/jni.h" "$ROOT/sysroot/sysroot/usr/include/jni.h" 2>/dev/null | head -1 )"

{
  echo "Smithy Termux toolchain bundle"
  echo "arch=$ARCH"
  echo "mirror=$IDX_URL"
  echo "built=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "# package	version	sha256	installed_kb"
  awk -F'	' '{print $1"	"$2"	"$4"	"$5}' "$WORK/meta.tsv"
} > "$ROOT/MANIFEST.txt"

# 再分发要带的说明。这个包一旦对外发布（GitHub Release 之类）就是**再分发**，
# 义务是：附许可、说明来源、指出对应源码在哪。各包自带的 copyright 文件已经在
# share/doc/<包名>/copyright 里（解包时就带上了），这里再给一份人话版总表。
cat > "$ROOT/NOTICE.md" <<'NOTICE'
# Smithy Termux 工具链包 · 许可与来源

本包由 `smithy/tools/fetch-termux-toolchain.sh` 从 **Termux 主仓库**的二进制包组装而成，
内容**未经修改**（只做了两件事：去掉首部目录层级、按 ABI 删除用不到的其他 ABI 目录）。

## 来源

- 打包仓库：Termux 主仓库（`dists/stable/main/binary-<arch>`），
  镜像按 tuna → ustc → bfsu → 官方 依次尝试；实际用的镜像见 `MANIFEST.txt`。
- 每个包的版本与 SHA-256（来自仓库索引）见 `MANIFEST.txt`。
- 上游源码：各项目的官方仓库 / 发布页。Termux 的构建脚本在
  https://github.com/termux/termux-packages （`packages/<包名>/build.sh` 里写着上游地址与版本）。
- 各包自带的版权与许可全文：`share/doc/<包名>/copyright`。

## 主要组件与许可（按上游项目）

| 组件 | 包 | 许可 |
|---|---|---|
| clang / llvm / lld / libLLVM / compiler-rt | clang, llvm, lld, libllvm, libcompiler-rt | Apache-2.0 with LLVM exception |
| libc++ | libc++ | MIT / UIUC（LLVM 项目） |
| bionic 头与桩库 | ndk-sysroot, ndk-multilib-native-stubs | 来自 Android NDK（Apache-2.0 为主，见包内 NOTICE） |
| zlib | zlib | Zlib |
| zstd | zstd | BSD-3-Clause 或 GPL-2.0（双许可，此处按 BSD-3-Clause 使用） |
| libiconv | libiconv | **LGPL-2.1** |
| ncurses | ncurses | MIT 类（X11 许可） |
| libxml2 | libxml2 | MIT |
| libffi | libffi | MIT |
| liblzma (xz) | liblzma | 公有领域（0BSD 类） |

**本包不含 GPL-3.0 组件**：`make` 因此被刻意排除（这条路不需要它）。

## 关于 LGPL 组件（libiconv）

libiconv 以**未修改的动态库**形式随包分发（`lib/libiconv.so.*`），与其它组件是独立的文件，
使用者可以用自己的版本替换它（LGPL-2.1 §6 的再链接要求）。
其源码获取方式见 Termux 的构建脚本所列上游地址。

## 这个包不包含什么

- 不含 Android SDK / NDK 的宿主机工具链（那些是 x86_64 的，手机上跑不了）；
- 不含 `make`、`cmake` 等构建系统（Smithy 直接调用 clang++）；
- 不含其他 ABI 的桩库（只保留目标 ABI 一份）。
NOTICE
echo "   已写入 NOTICE.md（来源 + 许可 + 不包含什么）"

echo "== 6/6 打包（用 tar.gz，不用 zip）"
# 为什么是 tar.gz 而不是 zip：**zip 装不下软链接**。Termux 的 bin/clang++ 是指向
# clang-21 的软链接（还有 901 个类似的），zip 里会变成 0 字节的空文件 —— App 解出来
# 就是个空壳，编译时报一堆看不懂的错。tar 保留软链接与权限位，而 Smithy 的解包器
# （ArchiveExtract）本来就按 tar 的规矩还原这两样（Alpine rootfs 就是这么解的）。
STAGE="$WORK/stage/termux-toolchain"
mkdir -p "$STAGE"
cp -a "$ROOT/." "$STAGE/"
echo "   条装前统计：$(find "$STAGE" | wc -l) 个条目"
tar czf "$OUT_ZIP" -C "$WORK/stage" termux-toolchain
echo "   套了一层 termux-toolchain/：导入时 strip=1 正好落到 bin/、lib/、opt/、sysroot"
echo
echo "产出：$OUT_ZIP（$(du -h "$OUT_ZIP" | cut -f1)）"
echo "装到手机上：传到 /sdcard/Download → 模块页「导入工具链包…」"
echo "装完应看到：<filesDir>/addon/native-toolchain/{bin,lib,opt,sysroot}"
