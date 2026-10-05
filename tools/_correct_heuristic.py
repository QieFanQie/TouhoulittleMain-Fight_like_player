"""对启发式填充的【定向纠错】。

抽样检查发现三类错误，逐类修正：

① role 漏判：tacz:draw / inspect / attachment / use_item_interact 是维护或姿态，
   不是战术选择（它们被兜底成了 SINGLE 0.3，会污染评分空间）。
② ★ goety:cast_focus 被推导出 MOBILITY=1.0 等荒谬值 —— 因为它是
   "施放【任意】聚晶"，真实价值完全取决于聚晶本身。
   ⇒ 正解与 SA / 枪型同构：按【聚晶类别】参数化。
   而调律师已经有 FocusClassifier + FocusCategory ⇒ domain.ref 直接指向它。
③ tacz:grenade（手雷）应有 AREA —— 启发式只给了 SINGLE。
"""
import io
import json
import os

DATA = os.path.join('catalog', 'data')

# ① 补 role
EXTRA_ROLE = {
    'tacz:draw': 'maintenance',            # 拔枪：执行器的前置步骤（等价于"换武器"）
    'tacz:inspect': 'maintenance',         # 检视：纯表现
    'tacz:attachment': 'maintenance',      # 配件：配置
    'tacz:use_item_interact': 'maintenance',
}

# ② goety:cast_focus —— 改成按【聚晶类别】参数化
FOCUS_CATEGORIES = {
    # 类别名取自调律师的 focus/FocusCategory（见 _research/tuner-reference.md）
    'ATTACK_SINGLE':  {"SINGLE_DAMAGE": 0.7, "SELF_SURVIVAL": -0.1},
    'ATTACK_AREA':    {"SINGLE_DAMAGE": 0.3, "AREA_DAMAGE": 0.9, "SELF_SURVIVAL": -0.3},
    'SUMMON':         {"REINFORCE": 0.8, "SINGLE_DAMAGE": -0.2},
    'UTILITY':        {"MOBILITY": 0.5, "SELF_SURVIVAL": 0.3},
    'PROTECTION':     {"SELF_SURVIVAL": 0.8, "ALLY_CARE": 0.3},
    'HEALING':        {"ALLY_CARE": 0.7, "SELF_SURVIVAL": 0.4},
    'CONTROL':        {"CONTROL": 0.7, "SINGLE_DAMAGE": 0.2},
    'SERVANT_MGMT':   {"REINFORCE": 0.4, "CONTROL": 0.2},
}

# ③ 手雷
GRENADE = {"SINGLE_DAMAGE": 0.5, "AREA_DAMAGE": 0.8, "SELF_SURVIVAL": -0.1}

report = []

for fn in sorted(os.listdir(DATA)):
    if not fn.endswith('.json'):
        continue
    path = os.path.join(DATA, fn)
    d = json.load(io.open(path, encoding='utf-8'))
    changed = []

    for op in d['operations']:
        oid = op['id']

        if oid in EXTRA_ROLE:
            op['role'] = EXTRA_ROLE[oid]
            op.pop('vector', None)
            op.pop('vectorProvenance', None)
            changed.append('%s role=%s' % (oid, EXTRA_ROLE[oid]))

        if oid == 'goety:cast_focus':
            op.pop('vector', None)
            op['vectorProvenance'] = 'authored'
            op['vectorOverrides'] = FOCUS_CATEGORIES
            params = op.setdefault('params', {})
            params['focusCategory'] = {
                "ref": "goety-tuner 的 focus/FocusClassifier + FocusCategory（按聚晶类别分类）",
                "values": sorted(FOCUS_CATEGORIES.keys()),
                "count": len(FOCUS_CATEGORIES),
                "note": {"zh_cn": "★★ 聚晶有 118+ 个，逐个写向量不现实；但【类别】是有界的。"
                                   "⇒ 与 SA（9 种）/枪型（7 种）同构的 A 档参数化："
                                   "按类别覆写向量，类别由调律师已有的 FocusClassifier 在运行期判定。"
                                   "★ 清单因此不必知道有哪 118 个聚晶。"},
            }
            changed.append('%s → 按聚晶类别参数化（%d 类）' % (oid, len(FOCUS_CATEGORIES)))

        if oid == 'tacz:grenade':
            op['vector'] = GRENADE
            op['vectorProvenance'] = 'authored'
            changed.append('%s 补 AREA' % oid)

    if changed:
        with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(d, f, ensure_ascii=False, indent=2)
            f.write('\n')
        report.append((fn, changed))

print('纠错：')
for fn, ch in report:
    print('  %s' % fn)
    for c in ch:
        print('     - %s' % c)
