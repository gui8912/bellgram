# -*- coding: utf-8 -*-
"""v4.2.0 批次 4：为本次修复补边界单测 + 版本号。"""
import io, os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
TEST = os.path.join(ROOT, "app", "src", "test", "java", "com", "saiga", "find", "messagefinder")
changes = []


def rp(rel):
    return os.path.join(ROOT, rel)


def append_before_last_brace(path, block, tag):
    with io.open(path, encoding="utf-8") as f:
        s = f.read()
    i = s.rfind("}")
    assert i > 0, tag
    s = s[:i] + block + s[i:]
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)
    changes.append((os.path.basename(path), tag))


def edit_text(path, old, new, tag, count=1):
    with io.open(path, encoding="utf-8") as f:
        s = f.read()
    n = s.count(old)
    assert n == count, "%s :: %s 期望 %d 处，实际 %d 处" % (os.path.basename(path), tag, count, n)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(s.replace(old, new, count))
    changes.append((os.path.basename(path), tag))


# ============================================================ PhoneNumbersTest
append_before_last_brace(
    os.path.join(TEST, "PhoneNumbersTest.java"),
    '''
    // ---- modified: v4.2 独立复审新增的回归用例 ----

    @Test
    public void isValidInput_rejectsUssdSupplementChars() { // modified
        // '*' / '#' 会被 stripSeparators 静默丢弃，夹在数字中间会把号码改写成另一个号码。
        // v5 的字符白名单修漏了这两个字符，这里把它们钉死。
        assertFalse(PhoneNumbers.isValidInput("1380013800*99"));
        assertFalse(PhoneNumbers.isValidInput("#10086#"));
        assertFalse(PhoneNumbers.isValidInput("138 0013 8000 #1234"));
    }

    @Test
    public void canonical_keepsLongInternationalNumberIntact() { // modified
        // v5 把输入上限放宽到 E.164 的 15 位后，canonical 仍无条件取末 11 位，
        // 两个不同的 13 位号码会被压成同一个值。
        assertEquals("1234567890123", PhoneNumbers.canonical("1234567890123"));
        assertEquals("9934567890123", PhoneNumbers.canonical("9934567890123"));
    }

    @Test
    public void matches_doesNotCollapseDifferentLongNumbers() { // modified
        assertFalse(PhoneNumbers.matches("1234567890123", "9934567890123"));
    }

    @Test
    public void matches_shortServiceNumberToleratesCountryPrefix() { // modified
        // 10086 / 95588 是最常监听的服务号；旧的 7 位门槛把它们挡在门外，
        // 短信侧返回 +8610086 时规则会静默失效。
        assertTrue(PhoneNumbers.matches("10086", "+8610086"));
        assertTrue(PhoneNumbers.matches("95588", "8695588"));
    }

    @Test
    public void matches_rejectsTooLongPrefixDifference() { // modified
        // 长度差超过 4 位不再做后缀匹配，避免短片段误命中长号码。
        assertFalse(PhoneNumbers.matches("01088886666", "886666"));
    }
''', "PhoneNumbers 新增 5 条边界用例")

# ============================================================ KeywordMatcherTest
edit_text(os.path.join(TEST, "KeywordMatcherTest.java"),
'''import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;''',
'''import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse; // modified: v4.2 新增用例
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;''', "KeywordMatcherTest 导入")

append_before_last_brace(
    os.path.join(TEST, "KeywordMatcherTest.java"),
    '''
    // ---- modified: v4.2 独立复审新增的回归用例 ----

    @Test
    public void parseKeywords_supportsCjkPunctuation() { // modified
        // 中文用户按习惯输入「报警、求助」，原实现整串会被当成一条永不命中的关键词。
        assertEquals(Arrays.asList("报警", "求助"), KeywordMatcher.parseKeywords("报警、求助"));
        assertEquals(Arrays.asList("报警", "求助"), KeywordMatcher.parseKeywords("报警。求助"));
    }

    @Test
    public void parseKeywords_stripsZeroWidthCharacters() { // modified
        // 从微信 / 网页复制粘贴常带 U+200B 零宽空格与 U+FEFF BOM。
        assertEquals(Arrays.asList("报警", "求助"), KeywordMatcher.parseKeywords("报警\\u200B求助"));
        assertEquals(Arrays.asList("报警", "求助"), KeywordMatcher.parseKeywords("\\uFEFF报警\\u200B求助"));
    }

    @Test
    public void parseKeywords_truncatesByCodePoint() { // modified
        // 29 个 'a' + 1 个 emoji(占 2 个 UTF-16 char) = 30 个 code point，
        // 截断边界正好落在 emoji 上，不能留下孤立高代理项。
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < KeywordMatcher.MAX_KEYWORD_LENGTH - 1; i++) {
            word.append('a');
        }
        word.append("\\uD83D\\uDE00").append("tail");
        String result = KeywordMatcher.parseKeywords(word.toString()).get(0);
        assertEquals(KeywordMatcher.MAX_KEYWORD_LENGTH,
                result.codePointCount(0, result.length()));
        assertFalse(Character.isHighSurrogate(result.charAt(result.length() - 1)));
    }
''', "KeywordMatcher 新增 3 条边界用例")

# ============================================================ 版本号
edit_text(rp("app/build.gradle"),
'''        // modified: v4.1.2 = 修复首次启动引导页闪退（versionCode 21 -> 22）
        versionCode 22
        versionName "4.1.2"''',
'''        // modified: v4.2.0 = 全量独立复审（30+ 项缺陷修复），versionCode 22 -> 23
        versionCode 23
        versionName "4.2.0"''', "版本号 4.2.0")

for lang in ("values", "values-zh-rCN"):
    edit_text(rp("app/src/main/res/%s/strings.xml" % lang),
'''<string name="current_app_version">4.1.2</string>''',
'''<string name="current_app_version">4.2.0</string> <!-- modified: 同步界面版本号 -->''',
"界面版本号 (%s)" % lang)

print("批次 4 完成，共 %d 处" % len(changes))
for name, tag in changes:
    print("  %-34s %s" % (name, tag))
