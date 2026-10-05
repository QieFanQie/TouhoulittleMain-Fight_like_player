"""把 .ps1 重写为带 UTF-8 BOM。

★ 环境陷阱：本机只有 PowerShell 5.1，它读 .ps1 时【默认按 ANSI 解码】。
含中文的脚本会因此被解成乱码，且乱码会吃掉字符串引号 ⇒ 报
"The string is missing the terminator"。
加 BOM 后 5.1 才会按 UTF-8 读。这与 docs/02 §2.4 记录的几个编码陷阱是同一类。
"""
import io
import sys

for path in sys.argv[1:]:
    text = io.open(path, encoding='utf-8-sig').read()
    with io.open(path, 'w', encoding='utf-8-sig', newline='\r\n') as f:
        f.write(text.replace('\r\n', '\n'))
    print('已加 BOM：%s（%d 字符）' % (path, len(text)))
