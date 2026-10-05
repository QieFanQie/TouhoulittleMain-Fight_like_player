"""列出清单里实际用到的 carrier 与 sourceMod —— 编制 carriers.json 的依据。"""
import json
import glob
import os
import collections

carriers = collections.Counter()
mods = collections.Counter()
kinds = collections.Counter()
reach = collections.Counter()
with_params = []

for f in sorted(glob.glob(os.path.join('catalog', 'data', '*.json'))):
    d = json.load(open(f, encoding='utf-8'))
    for o in d['operations']:
        carriers[o['carrier']] += 1
        mods[o['sourceMod']] += 1
        kinds[o['kind']] += 1
        reach[o['reach']] += 1
        if 'params' in o:
            with_params.append((o['id'], sorted(o['params'].keys())))

print('--- carrier ---')
for k, v in sorted(carriers.items()):
    print('  %-26s %d' % (k, v))
print('--- sourceMod ---')
for k, v in sorted(mods.items()):
    print('  %-26s %d' % (k, v))
print('--- kind ---')
for k, v in sorted(kinds.items()):
    print('  %-26s %d' % (k, v))
print('--- reach ---')
for k, v in sorted(reach.items()):
    print('  %-26s %d' % (k, v))
print('--- 带 params 的条目（族的参数名） ---')
for oid, ps in with_params:
    print('  %-34s %s' % (oid, ps))
