"""W20：给铁魔法的"施放任意法术"族补上【按意图参数化】的向量。

## 为什么不能用「学派」（SchoolType）

铁魔法有 9 个学派（`SchoolRegistry.java:63-71`：fire/ice/lightning/holy/ender/blood/
evocation/nature/eldritch），看起来是天然的参数。**但调研已查证学派是"装饰性"的**：

> 学派只影响 4 件事：① `<SCHOOL>_SPELL_POWER` 属性；② `getCastSound()` 施法音；
> ③ `getTargetingColor()` 指示圈颜色；④ 卷轴台的 focus 材料与 `ModTags.<SCHOOL>_FOCUS`。
> —— `_research/ironspells-gap-analysis.md` §1.9

⇒ 按学派打分是**错的**：`fireball`（火）与 `frost_bolt`（冰）在战术上是同一件事（攻击），
而 `heal`（神圣）与 `fireball`（火）才是真正不同的两件事。
**学派影响"好看/属性"，不影响"该不该放"。**

## 用什么：可从公开 API 推导的【战术意图】

| 类别 | 判定 | 是否闭包可推导 |
|---|---|---|
| `SUMMON` | `spell.getRecastCount(level, entity) > 0` | ★ **是**（public 且吃 LivingEntity ⇒ R-A；3.16.x 新增 recast 法术自动覆盖） |
| `SUPPORT` | 属于走 `Utils.preCastTargetHelper` 的 22 个法术（支援/治疗/增益） | ⚠️ 需一份 22 条的名单（调研已给） |
| `ATTACK` | 其余全部 | ★ 是（"不是前两类"） |

⇒ 3 个类别里有 2 个是**闭包推导**的，符合 Q14「闭包枚举优先」的原则，
且**新增法术不需要改清单**（只要它落进这三类之一）。

★ 这样 `heal` 终于会走到 `HEAL_SURVIVAL`，而不是和 `fireball` 同分。
"""
import io
import json
import os

PATH = os.path.join('catalog', 'data', 'ironspells.json')

# 三个意图类别 → 向量（设计判断值）
INTENT_VECTORS = {
    # 攻击：单体为主（AoE 与单体的细分留作后续 WIP）
    "ATTACK":  {"SINGLE_DAMAGE": 0.7, "MITIGATION_SURVIVAL": -0.1},
    # 支援：治疗/增益/护盾类 —— ★ 按委托方要求，回血走 HEAL_SURVIVAL
    "SUPPORT": {"HEAL_SURVIVAL": 0.6, "ALLY_CARE": 0.7},
    # 召唤：多段召唤（recast 机制）
    "SUMMON":  {"REINFORCE": 0.8, "SINGLE_DAMAGE": -0.2},
}

INTENT_PARAM = {
    "ref": "① spell.getRecastCount(level, entity) > 0 ⇒ SUMMON（闭包，自动覆盖新增 recast 法术）；"
           "② 属于 Utils.preCastTargetHelper 的 22 个法术 ⇒ SUPPORT（名单见 _research §4.4 附近）；"
           "③ 其余 ⇒ ATTACK",
    "values": ["ATTACK", "SUPPORT", "SUMMON"],
    "count": 3,
    "note": {
        "zh_cn": "★★ 按【战术意图】而不是【学派】分类。理由（已查证）：铁魔法学派只影响"
                 "属性/施法音/指示圈颜色/卷轴台材料（_research §1.9）—— 即 fireball(火) 与 "
                 "frost_bolt(冰) 战术上同类，而 heal(神圣) 与 fireball(火) 才是真正不同的两件事。"
                 "★ 用学派打分是错的。"
    },
}

d = json.load(io.open(PATH, encoding='utf-8'))
changed = []

for op in d['operations']:
    if op['id'] not in ('irons:cast_spell', 'irons:recast'):
        continue
    params = op.setdefault('params', {})
    if 'spellIntent' not in params:
        params['spellIntent'] = INTENT_PARAM
    op['vectorOverrides'] = INTENT_VECTORS
    # 族默认向量：取 ATTACK（最常见的意图）
    op['vector'] = dict(INTENT_VECTORS['ATTACK'])
    op['vectorProvenance'] = 'authored'
    note = op.setdefault('note', {})
    if isinstance(note, dict):
        note['zh_cn'] = (note.get('zh_cn', '') +
            " ★★ W20 已补：本族按【spellIntent】参数化（ATTACK/SUPPORT/SUMMON），"
            "因此 heal 与 fireball 不再同分。★ 刻意【不用学派】（学派只影响属性/音效/颜色）。")
    changed.append(op['id'])

with io.open(PATH, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(d, f, ensure_ascii=False, indent=2)
    f.write('\n')

print('W20 已处理：%s' % changed)
print('意图 → 向量：')
for k, v in INTENT_VECTORS.items():
    print('  %-9s %s' % (k, json.dumps(v, ensure_ascii=False)))
