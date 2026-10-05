"""轴迁移的收尾修正 + M1 自测的轴名同步。

① `slashblade:trick_dodge`（回避）按委托方规则「躲避 ⇒ 负」应取负 MOBILITY。
   ⚠️ 垂直位移（trick_up / trick_down）在"靠近/远离"这个有向语义下**没有明确归属**，
   已在 docs/09 §8.0b 登记为待确认项，此处暂不擅自翻转。
② M1 自测里引用了旧轴名 SELF_SURVIVAL ⇒ 改为 MITIGATION_SURVIVAL。
"""
import io
import json
import os
import re

# ① 回避类 ⇒ MOBILITY 取负
p = os.path.join('catalog', 'data', 'slashblade.json')
d = json.load(io.open(p, encoding='utf-8'))
flipped = []
for op in d['operations']:
    if op['id'] in ('slashblade:trick_dodge',) and 'vector' in op:
        v = op['vector']
        if v.get('MOBILITY', 0) > 0:
            v['MOBILITY'] = -v['MOBILITY']
            flipped.append(op['id'])
if flipped:
    with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
        f.write('\n')
print('MOBILITY 取负（躲避类）：%s' % flipped)

# ② M1 自测轴名同步
t = os.path.join('src', 'main', 'java', 'com', 'touhoulittlemad',
                 'fightlikeplayer', 'decision', 'SpringSelfTest.java')
src = io.open(t, encoding='utf-8').read()
n = src.count('SELF_SURVIVAL')
src = src.replace('SELF_SURVIVAL', 'MITIGATION_SURVIVAL')
io.open(t, 'w', encoding='utf-8', newline='\n').write(src)
print('SpringSelfTest 轴名替换：%d 处 SELF_SURVIVAL -> MITIGATION_SURVIVAL' % n)
