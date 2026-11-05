# -*- coding: utf-8 -*-
"""按 git diff 定位本次新增的 Java 代码行，统一补 // modified 行尾标注。

跳过：空行、纯结构行、注释行、块注释内部、已有标注的行。
XML 不在本脚本处理范围（属性之间不能插注释，已在合法位置手工加 <!-- modified -->）。
"""
import io, os, re, subprocess, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
os.chdir(ROOT)

diff = subprocess.check_output(["git", "diff", "--unified=0", "--", "*.java"],
                               text=True, encoding="utf-8")
files = {}
cur = None
for line in diff.splitlines():
    if line.startswith("+++ b/"):
        cur = line[6:]
        files[cur] = set()
    elif line.startswith("@@") and cur:
        m = re.search(r"\+(\d+)(?:,(\d+))?", line)
        if m:
            start = int(m.group(1))
            count = int(m.group(2)) if m.group(2) is not None else 1
            for n in range(start, start + count):
                files[cur].add(n)
    elif line.startswith("+") and not line.startswith("+++") and cur:
        pass

# 新增（untracked）Java 文件：所有行都算新增
for f in ("app/src/main/java/com/saiga/find/messagefinder/AlarmVolumeGuard.java",
          "app/src/main/java/com/saiga/find/messagefinder/BootReceiver.java"):
    if os.path.exists(f):
        with io.open(f, encoding="utf-8") as fh:
            files[f] = set(range(1, len(fh.read().splitlines()) + 1))

SKIP_EXACT = {"{", "}", "};", ");", "})", "});", "},", "*/", "/*", "(", ")", ";", "",
              "},", ")]}", "));", "));}", "return;", "break;"}
total_added = 0

for path, line_numbers in sorted(files.items()):
    if not os.path.exists(path):
        continue
    with io.open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    in_block = False
    changed = False
    for i, line in enumerate(lines, start=1):
        stripped = line.strip()
        was_block = in_block
        # 块注释状态机（只按行粗判，足够覆盖本工程写法）
        if "/*" in line and "*/" not in line:
            in_block = True
        elif "*/" in line:
            in_block = False
        if i not in line_numbers or was_block or in_block:
            continue
        if not stripped or stripped in SKIP_EXACT:
            continue
        if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
            continue
        if "modified" in line:
            continue
        if stripped.startswith("package ") or stripped.startswith("import "):
            continue
        if stripped.startswith("@"):
            continue
        # 统一在行尾追加；Java 字符串不能跨行，行尾注释在任何语法位置都安全
        lines[i - 1] = line.rstrip() + " // modified"
        changed = True
        total_added += 1
    if changed:
        with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(lines) + "\n")

print("合计补标 %d 行" % total_added)
