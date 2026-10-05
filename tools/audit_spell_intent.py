"""★★ W32 审计：法术意图表的【完整性】。

为什么必须有这个脚本（它是 W32 想要的核心好处之一）：

  分类表现在是数据（`catalog/data/spell_intent.json`）。数据的风险是"**新版本加了法术，
  表里没有**"—— 那时运行期会静默落到关键词兜底，于是**没人知道**有一条法术其实没被复核过。

  ⇒ 本脚本把 jar 里的全部法术类枚举一遍，逐个查表：
       · 全都查得到  ⇒ OK
       · 有查不到的  ⇒ **失败**，并列出类名（照着补一条即可）

★ 判据：**"静默兜底"和"明确缺一条"必须能区分开**（docs/13 第 23/25/26 条是同一个病根）。
"""
import io
import json
import os
import re
import sys
import zipfile

TABLE = os.path.join('catalog', 'spell_intent.json')

TARGETS = [
    ('irons', r'libs\irons_spellbooks-1.20.1-3.16.3.jar',
     r'^io/redspace/ironsspellbooks/spells/.*/(\w+)\.class$'),
    ('goety', r'libs\goety-2.5.56.5.jar',
     r'^com/Polarice3/Goety/common/magic/spells/.*/(\w+)\.class$'),
]

if not os.path.exists(TABLE):
    print('!! 找不到 %s' % TABLE)
    sys.exit(1)

t = json.load(io.open(TABLE, encoding='utf-8'))

bad = 0
for section, jar, pat in TARGETS:
    table = t.get(section) or {}
    if not os.path.exists(jar):
        print('⚠ 跳过 %s：找不到 %s' % (section, jar))
        continue
    z = zipfile.ZipFile(jar)
    rx = re.compile(pat)
    names = {m.group(1) for n in z.namelist() if (m := rx.match(n))}
    names = {n for n in names if not n.startswith(('Abstract', 'ISpell', 'Base'))}
    missing = sorted(n for n in names if n not in table)
    extra = sorted(k for k in table if k not in names)
    print('== %s：jar 里 %d 个法术类；表里 %d 条' % (section, len(names), len(table)))
    if missing:
        print('   !! 表里【缺】%d 条 ⇒ 这些会静默落到关键词兜底（等于没复核过）：' % len(missing))
        for n in missing:
            print('        %s' % n)
        bad += len(missing)
    if extra:
        print('   ⚠ 表里有 %d 条在 jar 里找不到（版本变了？）：%s' % (len(extra), ', '.join(extra[:8])))
    if not missing:
        print('   OK  全部法术类都在表里')

# 取值合法性
VALID = {'irons': {'ATTACK', 'SUPPORT', 'SUMMON'},
         'goety': {'ATTACK_SINGLE', 'ATTACK_AREA', 'SUMMON', 'UTILITY', 'PROTECTION',
                   'HEALING', 'CONTROL', 'SERVANT_MGMT'}}
for section, allowed in VALID.items():
    for k, v in (t.get(section) or {}).items():
        if v not in allowed:
            print('!! %s.%s 的取值非法：%s（合法：%s）' % (section, k, v, sorted(allowed)))
            bad += 1

print()
if bad:
    print('!! 法术意图表有 %d 处问题 ⇒ 补一条到 %s 即可（照抄邻行的格式）' % (bad, TABLE))
    sys.exit(1)
print('OK  法术意图表完整且取值合法（jar 里每个法术类都有明确分类，不存在静默兜底）')
