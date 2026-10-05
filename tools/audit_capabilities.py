"""★ 能力键对账：清单声明的 `capability` 判据，游戏侧必须真的投喂。

为什么必须有这个脚本（2026-10-01 实测「铁魔法法术全部无法释放」的根因）：

  carriers.json 里 `irons:spellbook_or_scroll` 的判据写的是 capability 键
  `irons:isSpellContainer`，设计上"由适配器算好"——
  但 MaidSnapshot.capabilities() 当时只产出 `attr:*` 与 `enchant:*`，
  这个键【从来没被喂过】⇒ 载体规则永不命中 ⇒ 铁魔法一个候选都进不了。
  日志里连一行 irons 都查不到，因为"没进候选"是完全静默的。

判据：把 carriers.json 里所有 capability 键逐个拿去源码里找，
      找不到生产者就报错（`attr:` / `enchant:` 是动态拼接，登记为已知动态）。
"""
import glob
import io
import json
import os
import re
import sys

CARRIERS = os.path.join('catalog', 'carriers.json')

# 已知动态前缀：由 MaidSnapshot#capabilities 用 "prefix + 注册名" 拼出来，不会以字面量出现
KNOWN_DYNAMIC = {'attr:', 'enchant:'}


def collect_capability_keys(node, acc):
    if isinstance(node, dict):
        if node.get('kind') == 'capability' and node.get('key'):
            acc.add(node['key'])
        for v in node.values():
            collect_capability_keys(v, acc)
    elif isinstance(node, list):
        for v in node:
            collect_capability_keys(v, acc)


src_text = {}
for p in glob.glob(os.path.join('src', 'main', 'java', '**', '*.java'), recursive=True):
    src_text[p] = io.open(p, encoding='utf-8', errors='replace').read()
blob = '\n'.join(src_text.values())

d = json.load(io.open(CARRIERS, encoding='utf-8'))
keys = set()
collect_capability_keys(d.get('rules'), keys)

print('=== 能力键对账（carriers.json 的 capability 判据 ← 游戏侧是否投喂）===')
print('   清单里的 capability 键：%d 个' % len(keys))

missing = []
for k in sorted(keys):
    if any(k.startswith(pref) for pref in KNOWN_DYNAMIC):
        print('   OK   %-34s （已知动态前缀，由适配器拼接）' % k)
        continue
    hit = [p for p, t in src_text.items() if '"%s"' % k in t]
    if hit:
        print('   OK   %-34s ← %s' % (k, ', '.join(os.path.basename(h) for h in hit)))
    else:
        print('   FAIL %-34s 【没有任何生产者】⇒ 这条规则永远不会命中' % k)
        missing.append(k)

print()
if missing:
    print('!! %d 个能力键没有生产者：%s' % (len(missing), ', '.join(missing)))
    print('   修法二选一：① 在 MaidSnapshot#capabilities 里真的算出来并加进去；')
    print('               ② 承认做不到 ⇒ 把该规则的 match 改成 unresolved（fail-closed，显式登记缺口）。')
    print('   ★ 绝不能就这样放着：它是一条【静默失效】的能力（观感是"这个模组的动作全都不出"）。')
    sys.exit(1)

print('OK  所有 capability 键都有生产者 ⇒ 不存在"清单声明了却没人喂"的死路')
