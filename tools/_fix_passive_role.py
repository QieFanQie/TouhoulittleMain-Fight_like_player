"""M4 用源码纠正的建模错误：sweep / extinguisher_extra 不是独立动作。

## 证据（读 TLM 源码）

`EntityMaid#doHurtTarget`（EntityMaid.java:914-946）一次调用【同时】做三件事：

    914: public boolean doHurtTarget(Entity target) {
    926:     boolean result = super.doHurtTarget(target);   // ① 近战
    929:     this.doSweepHurt(target);                      // ② 横扫（条件性，内部自判）
    941:     if (task instanceof IAttackTask at && at.hasExtraAttack(this, target)) {
    943:         return result && at.doExtraAttack(this, target);   // ③ 额外伤害（灭火器）

而 `doSweepHurt`（:948-967）的条件是**TLM 内部自己判**的：
    canSweep = mainHandItem.canPerformAction(ToolActions.SWORD_SWEEP)
    sweepingDamageRatio = EnchantmentHelper.getSweepingDamageRatio(this)
    if (canSweep && sweepingDamageRatio > 0) { ... }

⇒ **女仆无法"只横扫不近战"**，也无法"不横扫只近战" —— 这三件事是**同一次调用**。

## 结论

`maid_native:sweep` 与 `maid_native:extinguisher_extra` 应标为 **role = passive**
（引擎自动附带，不是决策层可选的独立动作）。

★ 这也与**玩家侧**一致：玩家的横扫不是独立操作，是**左键命中时的被动效果**。

## 附带

melee 的向量吸收横扫的群伤价值（因为它俩绑在一起）。
"""
import io
import json
import os

SCHEMA = os.path.join('catalog', 'schema', 'operation.schema.json')
NATIVE = os.path.join('catalog', 'data', 'maid_native.json')

# ① schema 的 role 增加 passive
s = json.load(io.open(SCHEMA, encoding='utf-8'))
role = s['$defs']['operation']['properties']['role']
if 'passive' not in role['enum']:
    role['enum'].append('passive')
    role['description'] = (role['description'].replace(
        'stance=持续姿态/配置，作为契合度乘数或状态而非动作（ADS 瞄准 / 切射击模式）。',
        'stance=持续姿态/配置，作为契合度乘数或状态而非动作（ADS 瞄准 / 切射击模式）；'
        'passive=由引擎【自动附带】的效果，不是独立选择（横扫、灭火器附伤 —— 它们与近战是同一次调用，'
        '见 EntityMaid#doHurtTarget）。'))
    print('schema：role 增加 passive')

with io.open(SCHEMA, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(s, f, ensure_ascii=False, indent=2)
    f.write('\n')

# ② 两条动作改为 passive
d = json.load(io.open(NATIVE, encoding='utf-8'))
changed = []
for op in d['operations']:
    if op['id'] == 'maid_native:sweep':
        op['role'] = 'passive'
        op['note'] = {'zh_cn':
            '★★ M4 用源码纠正：横扫【不是独立动作】。EntityMaid#doHurtTarget（:914-946）'
            '一次调用就完成 近战(:926) + 横扫(:929) + 额外伤害(:941)，且横扫条件由 TLM 内部自判'
            '（canPerformAction(SWORD_SWEEP) 且有横扫附魔）。⇒ 女仆无法"只横扫不近战"。'
            '★ 这也与玩家侧一致：玩家的横扫是左键命中时的被动效果，不是独立操作。'
            '⇒ 标为 role=passive，其群伤价值并入 maid_native:melee_swing。'}
        changed.append('maid_native:sweep -> role=passive')
    elif op['id'] == 'maid_native:extinguisher_extra':
        op['role'] = 'passive'
        op['note'] = {'zh_cn':
            '★★ M4 用源码纠正：灭火器附伤【不是独立动作】。它由 EntityMaid#doHurtTarget(:941) '
            '在近战成功后自动调用 IAttackTask#hasExtraAttack/doExtraAttack —— 与近战是同一次调用。'
            '⇒ 标为 role=passive（引擎自动附带）。★ 按 Q16 决策：不做任何特殊处理，'
            '它只是一条普通数据记录。持有灭火器时近战会自然附伤。'}
        changed.append('maid_native:extinguisher_extra -> role=passive')
    elif op['id'] == 'maid_native:melee_swing':
        # 吸收横扫的群伤价值（因为它俩绑定）
        old = dict(op.get('vector', {}))
        op['vector'] = {
            "SINGLE_DAMAGE": 0.7,
            "AREA_DAMAGE": 0.25,
            "MITIGATION_SURVIVAL": -0.1
        }
        op['note'] = {'zh_cn':
            '★ M4：本条的向量【吸收了横扫的群伤价值】—— 因为 doHurtTarget 一次就做近战+横扫，'
            '两者不可分开选择（见 maid_native:sweep 的说明）。⇒ AREA_DAMAGE 取 0.25（横扫的期望值，'
            '实际是否触发取决于刀上有没有横扫附魔与 SWORD_SWEEP 能力）。'}
        changed.append('maid_native:melee_swing vector %s -> %s' % (old, op['vector']))

with io.open(NATIVE, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(d, f, ensure_ascii=False, indent=2)
    f.write('\n')

print('已修正：')
for c in changed:
    print('  - %s' % c)
