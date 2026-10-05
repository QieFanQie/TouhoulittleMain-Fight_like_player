"""M2 一次性迁移：把 maid_native.json 里过粗的 carrier 拆成「按能力」的载体。

原因：原先把 近战/弓/弩/三叉戟/弹幕/投掷 全部归到 maid:body，
会让「拿着弓的女仆」同时拿到近战动作 —— 载体解析必须按能力拆分。

本脚本做【文本级】替换，只改 carrier 的值，保留原文件的排版与注释式 note。
"""
import io
import os
import re

PATH = os.path.join('catalog', 'data', 'maid_native.json')

# id -> 新的 carrier
MAPPING = {
    'maid_native:melee_swing': 'maid:weapon',
    'maid_native:sweep': 'maid:weapon',
    'maid_native:shield_block': 'maid:shield',
    'maid_native:extinguisher_extra': 'maid:extinguisher',
    'maid_native:bow_shot': 'maid:bow',
    'maid_native:crossbow_shot': 'maid:crossbow',
    'maid_native:trident': 'maid:trident',
    'maid_native:danmaku': 'maid:danmaku',
    'maid_native:throw_any_item': 'maid:throwable',
    # gun_shot / slashblade_generic_slash 不动
}

text = io.open(PATH, encoding='utf-8').read()

changed = []
for oid, new_carrier in MAPPING.items():
    idx = text.find('"id": "%s"' % oid)
    if idx < 0:
        raise SystemExit('找不到 id：%s' % oid)
    m = re.compile(r'"carrier"\s*:\s*"([^"]+)"').search(text, idx)
    if not m:
        raise SystemExit('id %s 之后找不到 carrier' % oid)
    old = m.group(1)
    if old == new_carrier:
        continue
    text = text[:m.start(1)] + new_carrier + text[m.end(1):]
    changed.append((oid, old, new_carrier))

io.open(PATH, 'w', encoding='utf-8', newline='\n').write(text)

print('已改 %d 条：' % len(changed))
for oid, old, new in changed:
    print('  %-36s %-14s -> %s' % (oid, old, new))
