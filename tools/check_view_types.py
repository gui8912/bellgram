# -*- coding: utf-8 -*-
"""布局-代码一致性检查：findViewById 类型与 XML 控件类型是否兼容"""
import re, glob, os, sys

ROOT = r"D:\Workbuddy\2026-11-05-22-34-04\PriorityAlerts-v3\app\src\main"
LAYOUTS = os.path.join(ROOT, "res", "layout")
JAVA = os.path.join(ROOT, "java")

# ---- 1. 收集每个布局里 id -> 控件类名 ----
id2type = {}          # id -> set(类型)
file_id2type = {}     # (文件, id) -> 类型
for f in glob.glob(os.path.join(LAYOUTS, "*.xml")):
    txt = open(f, encoding="utf-8").read()
    for m in re.finditer(r"<([A-Za-z0-9_.]+)([^>]*?)/?>", txt, re.S):
        tag, attrs = m.group(1), m.group(2)
        mid = re.search(r'android:id="@\+?id/([A-Za-z0-9_]+)"', attrs)
        if not mid:
            continue
        name = mid.group(1)
        short = tag.split(".")[-1]
        id2type.setdefault(name, set()).add(short)
        file_id2type[(os.path.basename(f), name)] = short

# ---- 2. 扫描 Java 里的 findViewById 赋值 ----
JAVA_TYPE_ALIAS = {
    "Button": {"Button", "AppCompatButton", "MaterialButton", "TextView"},
    "ImageButton": {"ImageButton", "ImageView"},
    "ImageView": {"ImageView", "CircleImageView", "ShapeableImageView", "AppCompatImageView"},
    "TextView": {"TextView", "AppCompatTextView", "MaterialTextView", "Button", "EditText",
                 "AutoCompleteTextView", "SwitchCompat", "Switch", "CheckBox", "RadioButton"},
    "EditText": {"EditText", "AppCompatEditText", "TextInputEditText"},
    "AutoCompleteTextView": {"AutoCompleteTextView", "MaterialAutoCompleteTextView", "EditText"},
    "LinearLayout": {"LinearLayout"},
    "RelativeLayout": {"RelativeLayout"},
    "FrameLayout": {"FrameLayout"},
    "CoordinatorLayout": {"CoordinatorLayout"},
    "ScrollView": {"ScrollView", "androidx.core.widget.NestedScrollView", "NestedScrollView"},
    "NestedScrollView": {"NestedScrollView", "androidx.core.widget.NestedScrollView"},
    "ViewPager": {"ViewPager"},
    "View": None,     # None 表示任意类型都合法
}

def compatible(declared, actual):
    allowed = JAVA_TYPE_ALIAS.get(declared, {declared})
    if allowed is None:
        return True
    return actual in allowed

problems = []
for f in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
    src = open(f, encoding="utf-8").read()
    # 形如 XxxClass var = findViewById(R.id.yyy)  /  (XxxClass) findViewById(...)
    for m in re.finditer(
            r"([A-Za-z_][A-Za-z0-9_.<>]*(?:<[^>]*>)?)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"
            r"(?:\([A-Za-z_][A-Za-z0-9_.]*\)\s*)?(?:[A-Za-z_][A-Za-z0-9_.]*\.)?findViewById\(\s*R\.id\.([A-Za-z0-9_]+)\s*\)",
            src):
        declared = m.group(1).split("<")[0].split(".")[-1]
        vid = m.group(3)
        types = id2type.get(vid)
        if not types:
            continue
        for t in types:
            if not compatible(declared, t):
                line = src[:m.start()].count("\n") + 1
                problems.append((os.path.basename(f), line, vid, declared, t))

    # 显式强转：(Xxx) findViewById(R.id.yyy)
    for m in re.finditer(r"\(\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\)\s*findViewById\(\s*R\.id\.([A-Za-z0-9_]+)\s*\)", src):
        declared = m.group(1).split(".")[-1]
        vid = m.group(2)
        types = id2type.get(vid)
        if not types:
            continue
        for t in types:
            if not compatible(declared, t):
                line = src[:m.start()].count("\n") + 1
                problems.append((os.path.basename(f), line, vid, declared, t))

print("== findViewById 类型不匹配 ==", len(problems))
for p in problems:
    print(f"  {p[0]}:{p[1]}  R.id.{p[2]}  声明为 {p[3]}  实际 {p[4]}")

# ---- 3. 检查 (XxxLayout)object 这类 PagerAdapter 强转 ----
print("\n== Adapter 视图强转 ==")
for f in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
    src = open(f, encoding="utf-8").read()
    for m in re.finditer(r"\((RelativeLayout|LinearLayout|FrameLayout|ConstraintLayout|CoordinatorLayout)\)\s*(object|view)\b", src):
        line = src[:m.start()].count("\n") + 1
        print(f"  {os.path.basename(f)}:{line}  -> ({m.group(1)}) {m.group(2)}")
