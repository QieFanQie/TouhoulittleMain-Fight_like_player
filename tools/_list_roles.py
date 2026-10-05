"""列出每条动作的 role / reach（用于核对子代理提出的 role 问题）。"""
import io
import glob
import json
import os

for f in sorted(glob.glob(os.path.join('catalog', 'data', '*.json'))):
    d = json.load(io.open(f, encoding='utf-8'))
    print('=== %s ===' % os.path.basename(f))
    for o in d['operations']:
        role = o.get('role', 'choice')
        if role != 'choice':
            print('  %-40s role=%-12s reach=%s' % (o['id'], role, o.get('reach')))
    for o in d['operations']:
        if o.get('role', 'choice') == 'choice' and o.get('reach') != 'RA':
            print('  [choice 非 RA] %-32s role=choice reach=%s' % (o['id'], o.get('reach')))
