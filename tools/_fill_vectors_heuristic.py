"""补齐剩余 v_x（W2）+ 标注 role / vectorProvenance。

★ 方法说明（诚实登记）：
本脚本按【规则】从 effects / duration / 名称关键词推导向量，产出的是
**启发式初值（vectorProvenance = "heuristic"）**，不是人工判断值，也不是实测值。
docs/09 §6 明确允许这条路（"可以先给启发式初值，再用实测校准"），
但必须【可审计】：provenance 字段就是为此存在的。

★ 同时对齐「动作单位」（docs/09 §2.4）：识别出**不是决策层选择**的动作 ——
maintenance（换弹/拉栓）与 stance（ADS/切射击模式），标 role 而非硬塞向量。
"""
import io
import json
import os
import re

DATA = os.path.join('catalog', 'data')

AXES = ["SINGLE_DAMAGE", "AREA_DAMAGE", "SELF_SURVIVAL", "CONTROL",
        "MOBILITY", "REINFORCE", "ALLY_CARE"]

# ── 1. role：不是"选择"的动作（关键词 → role）──
ROLE_RULES = [
    # maintenance：执行器自动完成的维护步骤
    (r'reload|换弹|拉栓|bolt|装填', 'maintenance'),
    (r'cancel_?reload|取消换弹', 'maintenance'),
    # stance：姿态 / 配置，作为契合度或状态而非动作
    (r'\baim\b|ads|瞄准', 'stance'),
    (r'fire_?select|fire_?mode|切换射击模式|射击模式', 'stance'),
    (r'\bzoom\b|倍镜|开镜', 'stance'),
    (r'\bcrawl\b|匍匐', 'stance'),
]

# ── 2. 关键词 → 轴贡献（用于 heuristic 向量）──
KEYWORD_RULES = [
    (r'guard|block|格挡|防御|盾|parry',            {"SELF_SURVIVAL": 0.6, "SINGLE_DAMAGE": -0.3}),
    (r'judgement|次元斩|sakura_end|樱花|void_slash|散华', {"SINGLE_DAMAGE": 0.8, "AREA_DAMAGE": 0.4, "SELF_SURVIVAL": -0.2}),
    (r'circle|圆|wave|刃|storm|雨|spiral|阵|heavy_rain|blistering', {"SINGLE_DAMAGE": 0.3, "AREA_DAMAGE": 0.8}),
    (r'beam|ray|光束|射线|激光|prisma',              {"SINGLE_DAMAGE": 0.7}),
    (r'heal|治疗|回复|恢复|haste|增益',              {"ALLY_CARE": 0.7}),
    (r'summon|召唤|仆从|servant',                    {"REINFORCE": 0.7, "SINGLE_DAMAGE": -0.1}),
    (r'dismiss|处死|kill|消灭|recall|召回',          {"REINFORCE": -0.3, "CONTROL": 0.2}),
    (r'step|trick|瞬步|闪|跳|跃|dodge|回避|air_trick|enemy_step|kick_jump',
                                                    {"MOBILITY": 0.8, "SELF_SURVIVAL": 0.3, "SINGLE_DAMAGE": -0.2}),
    (r'upperslash|上斩|rising|升|aerial|空|坠',      {"SINGLE_DAMAGE": 0.6, "MOBILITY": 0.3}),
    (r'combo|连击|slash|斩|rapid|快速|drive|幻影刃',  {"SINGLE_DAMAGE": 0.7}),
    (r'freeze|冰|slow|减速|眩晕|stun|root|束缚',     {"CONTROL": 0.6, "SINGLE_DAMAGE": 0.2}),
    (r'teleport|传送|位移|blink',                    {"MOBILITY": 0.8}),
    (r'wall|墙|terrain|地形',                        {"CONTROL": 0.4, "AREA_DAMAGE": 0.3}),
    (r'shield|护盾|barrier|壁垒',                    {"SELF_SURVIVAL": 0.7}),
]

# ── 3. effects → 轴贡献（基础）──
EFFECT_BASE = {
    'DAMAGE':        {"SINGLE_DAMAGE": 0.6},
    'HEAL':          {"ALLY_CARE": 0.6},
    'SUMMON':        {"REINFORCE": 0.6},
    'DISPLACE':      {"MOBILITY": 0.6},
    'TELEPORT':      {"MOBILITY": 0.6},
    'APPLY_EFFECT':  {"CONTROL": 0.4},
    'REMOVE_EFFECT': {"ALLY_CARE": 0.3},
    'SET_AI_STATE':  {"REINFORCE": 0.2},
    'TERRAIN':       {"AREA_DAMAGE": 0.3},
    'DESPAWN':       {"REINFORCE": -0.3},
}

# ── 4. 已知的照顾目标：治疗类打在自己/主人身上 ⇒ 不是 CONTROL ──
ALLY_IDS = {'irons:cast_spell', 'irons:recast', 'goety:cast_focus'}


def clamp(v):
    return max(-1.0, min(1.0, round(v, 2)))


def derive(op):
    """按规则推导 heuristic 向量。"""
    text = (op.get('id', '') + ' ' +
            json.dumps(op.get('displayName', {}), ensure_ascii=False) + ' ' +
            json.dumps(op.get('summary', {}), ensure_ascii=False)).lower()

    acc = {}
    for e in op.get('effects', []):
        for k, v in EFFECT_BASE.get(e.get('type'), {}).items():
            acc[k] = acc.get(k, 0.0) + v

    for pat, contrib in KEYWORD_RULES:
        if re.search(pat, text, re.I):
            for k, v in contrib.items():
                acc[k] = acc.get(k, 0.0) + v

    # 长承诺 / 引导 ⇒ 暴露代价
    d = op.get('duration', {}) or {}
    kind = d.get('kind', 'INSTANT')
    if kind == 'CHANNEL':
        acc['SELF_SURVIVAL'] = acc.get('SELF_SURVIVAL', 0.0) - 0.3
    elif kind == 'COMMIT' and (d.get('ticks') or 0) >= 20:
        acc['SELF_SURVIVAL'] = acc.get('SELF_SURVIVAL', 0.0) - 0.2

    # 完全推不出东西 ⇒ 给一个最小的单点值（保证它不是零向量而被丢弃）
    if not acc:
        acc['SINGLE_DAMAGE'] = 0.3

    return {k: clamp(acc[k]) for k in AXES if k in acc and abs(acc[k]) > 1e-9}


summary = []
for fn in sorted(os.listdir(DATA)):
    if not fn.endswith('.json'):
        continue
    path = os.path.join(DATA, fn)
    d = json.load(io.open(path, encoding='utf-8'))
    touched_vec = 0
    touched_role = 0

    for op in d['operations']:
        text = (op.get('id', '') + ' ' +
                json.dumps(op.get('displayName', {}), ensure_ascii=False)).lower()

        # role
        for pat, role in ROLE_RULES:
            if re.search(pat, text, re.I):
                if op.get('role') != role:
                    op['role'] = role
                    touched_role += 1
                break

        # vector：只给 choice 且还没有向量的动作
        if op.get('role', 'choice') != 'choice':
            op.pop('vector', None)      # 维护/姿态动作不需要战术向量
            op.pop('vectorOverrides', None)
            continue
        if 'vector' in op or 'vectorOverrides' in op:
            op.setdefault('vectorProvenance', 'authored')
            continue
        v = derive(op)
        op['vector'] = v
        op['vectorProvenance'] = 'heuristic'
        touched_vec += 1

    with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
        f.write('\n')
    summary.append((fn, touched_vec, touched_role))

print('%-24s %10s %8s' % ('file', 'new vector', 'role set'))
for fn, v, r in summary:
    print('%-24s %10d %8d' % (fn, v, r))
