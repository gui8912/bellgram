# -*- coding: utf-8 -*-
"""按页面精确校验：Activity/Fragment 中 findViewById 的 id 是否存在于它加载的布局里
（含 <include> 递归展开，忽略 <merge> 内的动态 View）
"""
import re, os, glob
from collections import defaultdict

ROOT = r"D:\Workbuddy\2026-11-05-22-34-04\PriorityAlerts-v3\app\src\main"
RES = os.path.join(ROOT, "res")
JAVA = os.path.join(ROOT, "java", "com", "saiga", "find", "messagefinder")

def read(p):
    try:
        return open(p, encoding="utf-8", errors="replace").read()
    except Exception:
        return ""

# 布局文件 -> 其中定义的 id 集合（递归 include）
LAYOUT_DIR = os.path.join(RES, "layout")
_cache = {}

def layout_ids(name, seen=None):
    if name in _cache:
        return _cache[name]
    if seen is None:
        seen = set()
    if name in seen:
        return set()
    seen.add(name)
    txt = read(os.path.join(LAYOUT_DIR, name + ".xml"))
    ids = set(re.findall(r'@\+id/([A-Za-z0-9_]+)', txt))
    for inc in re.findall(r'<include[^>]*layout="@layout/([A-Za-z0-9_]+)"', txt):
        ids |= layout_ids(inc, seen)
    _cache[name] = ids
    return ids

# 每个 Java 文件：setContentView / inflate 的布局 + findViewById 的 id
files = sorted(glob.glob(os.path.join(JAVA, "*.java")))
problems = []
summary = []
for f in files:
    src = read(f)
    layouts = set(re.findall(r'setContentView\(R\.layout\.([A-Za-z0-9_]+)\)', src))
    layouts |= set(re.findall(r'inflate\(R\.layout\.([A-Za-z0-9_]+)', src))
    if not layouts:
        continue
    used = set(re.findall(r'findViewById\(\s*R\.id\.([A-Za-z0-9_]+)\s*\)', src))
    union = set()
    for l in layouts:
        union |= layout_ids(l)
    missing = sorted(used - union)
    summary.append((os.path.basename(f), sorted(layouts), len(used), len(missing)))
    for m in missing:
        problems.append((os.path.basename(f), sorted(layouts), m))

print("== 页面 / 布局 / 使用 id 数 / 缺失数 ==")
for s in summary:
    print(f"  {s[0]:24s} {','.join(s[1]):28s} 用{s[2]:>3d} 个 id, 缺失 {s[3]}")

print("\n== 布局里找不到的 id ==", len(problems))
for p in problems:
    print(f"  {p[0]}  布局{p[1]}  缺少 R.id.{p[2]}")
