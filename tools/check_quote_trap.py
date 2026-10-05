"""扫描「该用中文引号却写了 ASCII 双引号」的地方 —— 本项目已犯过 8 次的那种。

两类目标（2026-10-01 都真的踩到过）：

  ① Java 源码：字符串字面量里夹了 ASCII 双引号 ⇒ 编译错误。
  ② catalog JSON：中文串里夹了 ASCII 双引号 ⇒ **整份文件解析失败**
     （症状极不直观：9 个自测步骤同时报错，而报错只说 Unterminated object）。

判据：含中文的字符串字面量内部出现了未转义的 ASCII 双引号。
正确写法是用中文引号「」，或把内层引号转义（Java 里 \\" ，JSON 里 \\" ）。

★ 2026-10-02 补第三类（同一天又踩到，代价是一整轮构建）：
  ③ **注释里出现 ASCII `*/` ⇒ 块注释被提前结束**
     （真身：javadoc 里写「**只打这一种**/另一种」这种用 `/` 分隔的中文短语）
     ⇒ 之后所有中文都变成"非法字符"，报错还会指向**注释内部**的行号，
     看起来像"编码坏了"，实际上是注释提前结束。
     判据：一行里含 `*/`，但这一行**不是纯粹的注释结束行**（`*/` 之前还有别的非空白内容
     且不在行尾）⇒ 可疑。

输出：文件:行号: 内容；有命中 ⇒ 退出码 1。
"""
import glob
import io
import json
import re
import sys

SRC_ROOT = 'src'
CATALOG_GLOB = 'catalog/**/*.json'

bad = []
checked = 0


def strip_comment(line):
    """砍掉行尾的 `//` 注释 —— ★ 必须感知字符串状态（"http://x" 里的 // 不是注释）。"""
    in_str = False
    i = 0
    n = len(line)
    while i < n:
        c = line[i]
        if in_str:
            if c == '\\':
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            i += 1
            continue
        if c == '/' and i + 1 < n and line[i + 1] == '/':
            return line[:i]
        i += 1
    return line


def scan_java_line(line):
    """返回该行里"引号用错"的处数。

    两种形态（★ 第二种是 2026-10-01 实际踩到的，前面的版本漏了它）：
      ① 字面量【内部】夹了裸引号（如 "abc"def" —— 未转义、但恰好被当成内层引号）
      ② 字面量被裸引号【提前截断】：
         "★★ 默认就是"没有会话上下文"）" ⇒ 第一个 " 在【默认就是】后面就结束了字面量，
         紧接着的字符是中文/标识符 ⇒ Java 语法错误。
      判据②：一个字面量闭合之后，紧随（跳过空格）的字符必须是
        合法的后续记号（, ) ; + : ] } 等），否则就是"引号用错了"。
    """
    hits = []
    legal_after = set(',);]}+:*?&|<>/=[{!%^~-')
    i = 0
    n = len(line)
    while i < n:
        if line[i] != '"':
            i += 1
            continue
        j = i + 1
        body = []
        closed = False
        while j < n:
            c = line[j]
            if c == '\\':
                body.append(line[j:j + 2])
                j += 2
                continue
            if c == '"':
                closed = True
                break
            body.append(c)
            j += 1
        if not closed:
            break
        inner = ''.join(body)
        # ★ 先把【已转义】的引号去掉再判定 —— 否则 \" 会被误报
        probe = inner.replace('\\"', '').replace("\\'", '')
        # ★ 2026-10-02 补：\uXXXX 转义形式的中文也算“中文串”
        #   （真实踩到：脚本生成的源码里是 \u2605 这种写法，不含真正的 CJK 字符 ⇒ 旧判据漏判）
        cjk = re.search(r'[\u4e00-\u9fff]', probe) or re.search(r'\\u[0-9a-fA-F]{4}', probe)
        if cjk and '"' in probe:
            hits.append('字面量内部夹引号：' + inner[:60])
        else:
            # ② 闭合之后必须跟合法记号（否则就是被提前截断了）
            k = j + 1
            while k < n and line[k] == ' ':
                k += 1
            if k < n and line[k] not in legal_after:
                nxt = line[k]
                if re.match(r'[\u4e00-\u9fffA-Za-z_）】」]', nxt):
                    hits.append('字面量被提前截断（后面紧跟 %r）：%s' % (nxt, line.strip()[:70]))
        i = j + 1
    return hits


# ── ① Java 源码 ──
for path in glob.glob(SRC_ROOT + '/**/*.java', recursive=True):
    checked += 1
    text = io.open(path, encoding='utf-8', errors='replace').read()
    in_block_comment = False
    for lineno, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        # ★ ③ 注释里的 ASCII */ ⇒ 块注释提前结束（这一条与引号无关，但同一族：
        #   "在中文里用了 ASCII 标点"）。必须**在跳过注释行之前**判。
        if '*/' in line:
            head = line.split('*/')[0].strip()
            tail = line.split('*/', 1)[1].strip()
            # 合法形态只有两种：`*/` 独占一行，或 `... */`（注释结束正好在行尾）
            if tail:
                bad.append('%s:%d: %s\n        → 注释里有 ASCII */ ⇒ 块注释会被提前结束'
                           '（后面的中文会变成"非法字符"，报错行号还指不准）'
                           % (path, lineno, stripped[:100]))
        if stripped.startswith('/*'):
            in_block_comment = True
        if stripped.startswith('//') or stripped.startswith('*') or in_block_comment:
            if stripped.endswith('*/'):
                in_block_comment = False
            continue
        code = strip_comment(line)
        for h in scan_java_line(code):
            bad.append('%s:%d: %s\n        → %s' % (path, lineno, stripped[:100], h))

# ── ② catalog JSON ──
#   JSON 里没有"字面量扫描"的必要：**能解析成功就说明没有问题**。
#   所以这里的判据是"能不能解析 + 中文串里有没有裸引号"——
#   后者其实不可能在解析成功的文件里出现（JSON 不允许），
#   真正有价值的是【解析失败时给出人话提示】，所以这里直接做解析检查。
for path in glob.glob(CATALOG_GLOB, recursive=True):
    checked += 1
    raw = io.open(path, encoding='utf-8', errors='replace').read()
    try:
        json.loads(raw)
    except json.JSONDecodeError as e:
        hint = ''
        if 'Invalid control character' in str(e) or 'Unterminated' in str(e):
            hint = '　← 常见原因：中文串里写了 ASCII 双引号（请改用「」）'
        # 只显示第一处错误，但把行内容带出来（定位快）
        lines = raw.splitlines()
        snippet = lines[e.lineno - 1][:200] if 0 < e.lineno <= len(lines) else ''
        bad.append('%s:%d:%d: JSON 解析失败：%s%s\n        %s'
                   % (path, e.lineno, e.colno, e.msg, hint, snippet))

for b in bad:
    print(b)
print('已扫描 %d 个文件；可疑 %d 处' % (checked, len(bad)))
sys.exit(1 if bad else 0)
