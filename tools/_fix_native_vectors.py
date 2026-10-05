"""诊断并修复 maid_native.json 的 vector 插入错位。

上一步用正则往 "carrier" 行后面插 vector，但有些条目把 carrier 写在
"kind": "atomic", "carrier": "...", "sourceMod": ... 这一行里，
正则就一路往后找到了【别的条目】的 carrier 行 ⇒ 插错位置、产生重复 vector 键。

本脚本：① 报告每个条目的 vector 情况；② 删除所有 vector 行；
③ 用 JSON 感知的方式重新写入（json.dumps，格式化归一但内容零丢失）。
"""
import io
import json
import os
import re

PATH = os.path.join('catalog', 'data', 'maid_native.json')

VECTORS = {
    'maid_native:melee_swing': {"SINGLE_DAMAGE": 0.7, "SELF_SURVIVAL": -0.1},
    'maid_native:sweep': {"SINGLE_DAMAGE": 0.4, "AREA_DAMAGE": 0.7},
    'maid_native:shield_block': {"SELF_SURVIVAL": 0.6, "SINGLE_DAMAGE": -0.3},
    'maid_native:extinguisher_extra': {"SINGLE_DAMAGE": 0.5},
    'maid_native:bow_shot': {"SINGLE_DAMAGE": 0.6, "SELF_SURVIVAL": 0.1},
    'maid_native:crossbow_shot': {"SINGLE_DAMAGE": 0.7, "SELF_SURVIVAL": 0.1},
    'maid_native:trident': {"SINGLE_DAMAGE": 0.7, "MOBILITY": 0.2},
    'maid_native:danmaku': {"SINGLE_DAMAGE": 0.5, "AREA_DAMAGE": 0.4, "CONTROL": 0.2},
    'maid_native:throw_any_item': {"SINGLE_DAMAGE": 0.4},
    'maid_native:gun_shot': {"SINGLE_DAMAGE": 0.6, "AREA_DAMAGE": 0.2},
    'maid_native:slashblade_generic_slash': {"SINGLE_DAMAGE": 0.7},
}

# ① 先看现状
raw = io.open(PATH, encoding='utf-8').read()
print('修复前：vector 出现 %d 次，条目 %d 个'
      % (raw.count('"vector"'), raw.count('"id": "maid_native:')))

# ② 删掉所有位于顶层的 vector 行（简单文本行删除，随后由 json 重建）
text = re.sub(r'^\s*"vector"\s*:\s*\{[^}]*\},\s*$\n?', '', raw, flags=re.M)
io.open(PATH, 'w', encoding='utf-8', newline='\n').write(text)

# ③ JSON 感知地写回
d = json.load(io.open(PATH, encoding='utf-8'))
missing = []
for op in d['operations']:
    v = VECTORS.get(op['id'])
    if v is None:
        missing.append(op['id'])
        continue
    # 保持键的固定顺序：按 NeedAxis 的顺序
    order = ["SINGLE_DAMAGE", "AREA_DAMAGE", "SELF_SURVIVAL", "CONTROL",
             "MOBILITY", "REINFORCE", "ALLY_CARE"]
    op['vector'] = {k: v[k] for k in order if k in v}

with io.open(PATH, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(d, f, ensure_ascii=False, indent=2)
    f.write('\n')

print('修复后：')
d2 = json.load(io.open(PATH, encoding='utf-8'))
for op in d2['operations']:
    print('  %-44s %s' % (op['id'], op.get('vector', '(无)')))
if missing:
    print('未覆盖（无 v_x 定义）：%s' % missing)
