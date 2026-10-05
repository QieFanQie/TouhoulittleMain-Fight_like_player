"""把 v_x 从动作数据里抽出来，独立成 catalog/vectors.json（委托方要求）。

## 为什么分开

| 文件 | 描述 | 变更频率 | 复核者 |
|---|---|---|---|
| `data/*.json` | 这一招**是什么**（事实，来自勘测） | 低 | 勘测者 |
| `vectors.json` | 这一招**值多少**（设计判断） | 高（要反复复核/重打分/实测校准） | 评分员 |

混在一起 ⇒ "复核打分"要翻六个文件；分开 ⇒ **一次 diff 就能看清这轮改了哪些分数**。

## 迁移规则

① 每个 choice 动作的 `vector` → `vectors.json` 的 `vector`
② `vectorOverrides` → `vectors.json` 的 `overrides`
③ `vectorProvenance` → `vectors.json` 的 `provenance`
④ 把动作数据里的这三个字段**删掉**（避免两份真相）
   ★ 但 `CatalogLoader` 仍支持内联 vector（向后兼容）⇒ 漏迁不会静默丢分
⑤ non-choice 动作本来就没有向量，跳过
"""
import io
import json
import os
import glob

DATA = os.path.join('catalog', 'data')
OUT = os.path.join('catalog', 'vectors.json')

vectors = {}
stats = {'moved': 0, 'with_overrides': 0, 'stripped': 0}

for f in sorted(glob.glob(os.path.join(DATA, '*.json'))):
    d = json.load(io.open(f, encoding='utf-8'))
    touched = False
    for op in d['operations']:
        oid = op['id']
        has_vec = 'vector' in op
        has_over = 'vectorOverrides' in op
        if not has_vec and not has_over:
            continue
        entry = {
            'vector': op.get('vector'),
            'provenance': op.get('vectorProvenance', 'authored'),
        }
        if has_over:
            entry['overrides'] = op['vectorOverrides']
            stats['with_overrides'] += 1
        # 保留人类可读的 note 里关于打分的那部分（如果条目 note 提到了向量，摘一句）
        vectors[oid] = entry
        # 删掉三个字段
        for k in ('vector', 'vectorOverrides', 'vectorProvenance'):
            if k in op:
                del op[k]
                stats['stripped'] += 1
                touched = True
        stats['moved'] += 1
    if touched:
        with io.open(f, 'w', encoding='utf-8', newline='\n') as fh:
            json.dump(d, fh, ensure_ascii=False, indent=2)
            fh.write('\n')

doc = {
    'formatVersion': 1,
    'generatedFrom': ('由 tools/_extract_vectors.py 从 catalog/data/*.json 抽出；'
                      '权威来源是【本文件】—— 动作数据里的内联 vector 仅作向后兼容。'),
    'notes': {
        'zh_cn': ('★ 本表是"动作值多少"的权威。provenance 标记来源：'
                  'authored=人工判断 / agent=子代理打分（待人工复核）/ heuristic=按规则批量推导（已知有错）/ measured=实测反推。'
                  '★ 复核打分时【只看本文件】即可，不必翻六个动作数据文件。'
                  '★ 轴语义见 docs/09 §3.1；MOBILITY 是唯一有向轴（正=靠近，负=远离）。'),
    },
    'vectors': dict(sorted(vectors.items())),
}

with io.open(OUT, 'w', encoding='utf-8', newline='\n') as fh:
    json.dump(doc, fh, ensure_ascii=False, indent=2)
    fh.write('\n')

print('已抽出 %d 条向量 → %s' % (stats['moved'], OUT))
print('  其中带 overrides：%d 条' % stats['with_overrides'])
print('  从动作数据里删除字段：%d 处' % stats['stripped'])

# 统计 provenance 分布
from collections import Counter
c = Counter(v['provenance'] for v in vectors.values())
print('  provenance 分布：%s' % dict(c))
