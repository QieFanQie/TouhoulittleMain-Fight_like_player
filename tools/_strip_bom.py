"""去掉 .java 的 UTF-8 BOM。

★ 环境陷阱：PowerShell 5.1 的 `Set-Content -Encoding UTF8` 会写【带 BOM】的 UTF-8，
而 javac 会把 BOM 当成非法字符：
    error: illegal character: '\\ufeff'
⇒ 用 PS 原地改 .java 之后必须去 BOM。（与其同源的还有 tools/README 里记的 .ps1 需要 BOM 的那条 ——
   两个坑方向相反，都是 PS 5.1 的编码默认值造成的。）
"""
import io
import sys
import glob

targets = sys.argv[1:] or glob.glob('src/**/*.java', recursive=True)
fixed = 0
for p in targets:
    raw = io.open(p, 'rb').read()
    if raw.startswith(b'\xef\xbb\xbf'):
        io.open(p, 'wb').write(raw[3:])
        print('去 BOM：%s' % p)
        fixed += 1
print('共处理 %d 个文件，去除 %d 个 BOM' % (len(targets), fixed))
