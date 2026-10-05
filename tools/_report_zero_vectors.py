"""报告"已评分但价值为 0"的动作（它们此前被误当成"未打分"而丢弃）。"""
import io
import json
import os

vd = json.load(io.open(os.path.join('catalog', 'vectors.json'), encoding='utf-8'))

zero = []
only_over = []
for aid, e in vd['vectors'].items():
    v = e.get('vector')
    has_over = bool(e.get('overrides'))
    if v is not None and not v and not has_over:
        zero.append((aid, e.get('reason', '')[:50]))
    if v is None and has_over:
        only_over.append(aid)
    if v is None and not has_over:
        print('!! 既无 vector 也无 overrides：%s' % aid)

print('★ 明确评为【全 0】的动作（%d 条）—— 它们是"已评分"，不是"缺打分"：' % len(zero))
for aid, why in zero:
    print('   %-44s %s' % (aid, why))

print()
print('★ 只有 overrides、无族默认的动作（%d 条）：%s' % (len(only_over), only_over))
