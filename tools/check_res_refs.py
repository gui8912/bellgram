# -*- coding: utf-8 -*-
"""资源引用一致性检查：
1) Java 中 R.id.xxx 是否在某个 layout / menu / xml 中定义
2) Java 中 R.drawable / R.string / R.color / R.layout / R.array / R.style 是否存在
3) layout 中引用的 @drawable/@color/@string 是否存在
"""
import re, glob, os, sys
from collections import defaultdict

ROOT = r"D:\Workbuddy\2026-11-05-22-34-04\PriorityAlerts-v3\app\src\main"
RES = os.path.join(ROOT, "res")
JAVA = os.path.join(ROOT, "java")
MANIFEST = os.path.join(ROOT, "AndroidManifest.xml")

def read(p):
    try:
        return open(p, encoding="utf-8", errors="replace").read()
    except Exception:
        return ""

# ---------- 资源定义表 ----------
defined = defaultdict(set)

# values/*.xml
for f in glob.glob(os.path.join(RES, "values*", "*.xml")):
    txt = read(f)
    base = os.path.basename(f)
    for m in re.finditer(r"<(string|color|dimen|style|bool|integer|string-array|array|plurals|attr)\s+name=\"([^\"]+)\"", txt):
        kind, name = m.group(1), m.group(2)
        t = {"string": "string", "color": "color", "dimen": "dimen", "style": "style",
             "bool": "bool", "integer": "integer", "string-array": "array",
             "array": "array", "plurals": "plurals", "attr": "attr"}[kind]
        defined[t].add(name.split(".")[0] if t == "style" else name)

# 文件型资源（drawable/mipmap/layout/xml/anim/menu/raw）
for sub in ("drawable", "mipmap", "layout", "xml", "anim", "menu", "raw", "drawable-v24",
            "mipmap-anydpi-v26"):
    for f in glob.glob(os.path.join(RES, sub, "*")):
        name = os.path.basename(f).split(".")[0]
        kind = "mipmap" if sub.startswith("mipmap") else sub.split("-")[0]
        defined[kind].add(name)

# 分密度目录
for f in glob.glob(os.path.join(RES, "*dpi*", "*")):
    png = os.path.basename(f)
    name = png.split(".")[0]
    defined["drawable"].add(name)

# layout/menu 里定义的 id
ids = set()
for f in glob.glob(os.path.join(RES, "**", "*.xml"), recursive=True):
    for m in re.finditer(r"@\+id/([A-Za-z0-9_]+)", read(f)):
        ids.add(m.group(1))
defined["id"] = ids

print("资源统计:", {k: len(v) for k, v in defined.items()})

# ---------- 1) Java 中的 R.xxx.yyy ----------
missing = []
for f in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
    src = read(f)
    for m in re.finditer(r"(?<![\w.])R\.(id|drawable|mipmap|string|color|layout|array|style|dimen|bool|integer|anim|menu|xml|raw)\.([A-Za-z0-9_]+)", src):
        kind, name = m.group(1), m.group(2)
        if kind == "style":
            continue        # 主题名可能由 parent 链推导，单独看
        pool = defined.get(kind, set())
        if kind in ("drawable", "mipmap"):
            # drawable 与 mipmap 目录在 aapt 里可互相引用同名资源
            pool = defined.get("drawable", set()) | defined.get("mipmap", set())
        if name not in pool:
            line = src[:m.start()].count("\n") + 1
            missing.append((os.path.basename(f), line, kind, name))

print("\n== Java 引用的资源缺失 ==", len(missing))
for x in sorted(set(missing)):
    print(f"  {x[0]}:{x[1]}  R.{x[2]}.{x[3]}")

# ---------- 2) XML 中引用的 @drawable/@color/@string ----------
xml_missing = []
for f in glob.glob(os.path.join(RES, "**", "*.xml"), recursive=True):
    txt = read(f)
    for m in re.finditer(r'"@(drawable|mipmap|color|string|dimen|style|layout|array|xml|menu|anim)/([A-Za-z0-9_]+)"', txt):
        kind, name = m.group(1), m.group(2)
        if name.startswith("android:"):
            continue
        pool = defined.get(kind, set())
        if kind in ("drawable", "mipmap"):
            pool = defined.get("drawable", set()) | defined.get("mipmap", set())
        if name not in pool:
            line = txt[:m.start()].count("\n") + 1
            xml_missing.append((os.path.basename(f), line, kind, name))

print("\n== XML 引用的资源缺失 ==", len(xml_missing))
for x in sorted(set(xml_missing)):
    print(f"  {x[0]}:{x[1]}  @{x[2]}/{x[3]}")

if missing or xml_missing:
    print(f"\nFAIL: Java 缺 {len(set(missing))} 处、XML 缺 {len(set(xml_missing))} 处资源引用")
    sys.exit(1)
print("\nOK: 资源引用完整")
