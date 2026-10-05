"""检查 README 与 docs/*.md 里的相对 Markdown 链接是否都指向真实存在的文件。"""
import os
import re
import glob

ROOT = os.path.abspath('.')
LINK = re.compile(r'\[[^\]]*\]\(([^)#\s]+)(?:#[^)]*)?\)')

broken = []
checked = 0

for md in ['README.md', 'THIRD_PARTY_NOTICES.md', 'catalog/README.md'] + sorted(glob.glob('docs/*.md')):
    if not os.path.exists(md):
        continue
    base = os.path.dirname(md)
    text = open(md, encoding='utf-8').read()
    for target in LINK.findall(text):
        if target.startswith(('http://', 'https://', 'mailto:')):
            continue
        checked += 1
        resolved = os.path.normpath(os.path.join(base, target))
        if not os.path.exists(resolved):
            broken.append((md, target, resolved))

print('检查链接数：%d' % checked)
if broken:
    print('断链 %d 个：' % len(broken))
    for md, target, resolved in broken:
        print('  %-44s -> %s' % (md, target))
else:
    print('断链 0 个 ✅')
