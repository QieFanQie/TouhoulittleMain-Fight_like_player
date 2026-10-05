"""轴模型迁移（7 轴 → 8 轴），按委托方的语义修订。

三处变更：
① `SELF_SURVIVAL` 一个轴 ⇒ **拆成两点**：
     `HEAL_SURVIVAL`（回血生存）—— 靠恢复生命活下去
     `MITIGATION_SURVIVAL`（非回血生存）—— 格挡/闪避/减伤/位移规避
   迁移判据：动作是【治疗/恢复】类 ⇒ HEAL_SURVIVAL；否则 ⇒ MITIGATION_SURVIVAL。
   负值（暴露代价）一律归 MITIGATION_SURVIVAL —— "施法时无法格挡"是免伤需求，不是回血需求。

② `MOBILITY` ⇒ **唯一的有向轴**：正 = 前进/突进/追踪；负 = 后撤/躲避/拉开距离。
   ⇒ 现有走位动作的符号按语义翻转（后撤/撤退 由 + 变 −）。

③ `REINFORCE` 收窄为**不含回血**的强化（回血归 HEAL_SURVIVAL）。
   现有 REINFORCE 值里没有纯回血动作，故数值不动，只更新说明。

★ 这些是【评分维度】不是【归类】—— 一个动作可以同时在多条轴上有值。
"""
import io
import json
import os
import re

DATA = os.path.join('catalog', 'data')

NEW_ORDER = ["SINGLE_DAMAGE", "AREA_DAMAGE", "HEAL_SURVIVAL", "MITIGATION_SURVIVAL",
             "CONTROL", "MOBILITY", "REINFORCE", "ALLY_CARE"]

# 治疗/恢复类动作 ⇒ HEAL_SURVIVAL
HEAL_PAT = re.compile(r'heal|治疗|回复|恢复|regen|吸血|lifesteal|恢复生命|回血', re.I)

# ★ 有向位移：这些是【远离】类，MOBILITY 取负
RETREAT_IDS = {
    'fight_like_player:retreat',     # 后撤
    'fight_like_player:disengage',   # 撤退&脱战
}
# 这些是【靠近】类，保持正
ADVANCE_IDS = {
    'fight_like_player:charge',      # 突进
}

report = []

for fn in sorted(os.listdir(DATA)):
    if not fn.endswith('.json'):
        continue
    path = os.path.join(DATA, fn)
    d = json.load(io.open(path, encoding='utf-8'))
    changed = []

    for op in d['operations']:
        oid = op['id']
        text = oid + ' ' + json.dumps(op.get('displayName', {}), ensure_ascii=False)

        def migrate(vec, label):
            out = {}
            is_heal = bool(HEAL_PAT.search(text))
            for k, v in vec.items():
                if k == 'SELF_SURVIVAL':
                    if v > 0 and is_heal:
                        out['HEAL_SURVIVAL'] = v
                    elif v > 0:
                        out['MITIGATION_SURVIVAL'] = v
                    else:
                        # 负值 = 暴露 ⇒ 免伤需求
                        out['MITIGATION_SURVIVAL'] = out.get('MITIGATION_SURVIVAL', 0.0) + v
                elif k == 'MOBILITY':
                    if oid in RETREAT_IDS and v > 0:
                        out['MOBILITY'] = -v
                    else:
                        out['MOBILITY'] = v
                else:
                    out[k] = v
            return {k: out[k] for k in NEW_ORDER if k in out and abs(out[k]) > 1e-9}

        if 'vector' in op:
            old = dict(op['vector'])
            new = migrate(old, 'vector')
            if new != old:
                op['vector'] = new
                changed.append('%s: %s -> %s' % (oid, old, new))
        if 'vectorOverrides' in op:
            vo = {}
            touched = False
            for key, vec in op['vectorOverrides'].items():
                nv = migrate(vec, 'override')
                if nv != vec:
                    touched = True
                vo[key] = nv
            if touched:
                op['vectorOverrides'] = vo
                changed.append('%s: vectorOverrides 迁移' % oid)

    if changed:
        with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(d, f, ensure_ascii=False, indent=2)
            f.write('\n')
        report.append((fn, changed))

print('轴迁移（7 -> 8）：')
for fn, ch in report:
    print('  %s  (%d 处)' % (fn, len(ch)))
    for c in ch[:6]:
        print('     - %s' % c)
    if len(ch) > 6:
        print('     ... 另有 %d 处' % (len(ch) - 6))
