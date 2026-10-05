"""把新实现的执行器写进 catalog/executors.json（覆盖率账本必须与代码同步）。

本轮新增（**零新依赖**，全部用 TLM 的公开 API + 原版 API）：
  · ★ 蓄力射击：弩 / 三叉戟 / 投掷任意物品 / 弹幕
    —— 读源码发现弓/弩/三叉戟/投掷/弹幕【完全同构】⇒ 一个执行器覆盖 5 个动作
  · ★ 枪械：TLM 的 GunCommonUtil 是公开抽象，**同时覆盖 TaCZ 与卓越前线** ⇒ 不需要引入 TaCZ 依赖
"""
import io
import json
import os

PATH = os.path.join('catalog', 'executors.json')
d = json.load(io.open(PATH, encoding='utf-8'))

CHARGED_NOTE = (
    '★★ 读源码发现这 5 个动作【完全同构】：起手 startUsingItem，松手 stopUsingItem + performRangedAttack。'
    '差别只在【蓄力时长】与【力度算法】。⇒ 一个执行器覆盖 5 个动作，只在 ShotKind 上分叉。'
    '★ 这正是「让几条路同样通畅」最省力的做法：**同构的东西只写一遍**。'
    '⚠️ 弹幕的【载体判据】仍未查证（W12），所以它现在拿不到候选；但执行器已就位 —— 补上判据它就能用。'
)

GUN_NOTE = (
    '★★ 用 TLM 的公开抽象 GunCommonUtil，**不需要引入 TaCZ 编译依赖**。'
    '⚠️ 为什么不用 TacInnerCompat.performGunAttack：它是**包内可见**（static int，非 public）'
    '⇒ 外部调不到（已实测）。'
    '★ GunCommonUtil 只认【主手】（内部取 maid.getMainHandItem()）⇒ 背包里的枪需要先换手，'
    '这就是 docs/09 §4.6.2「换武器是执行器前置步骤」的落点（ActionExecutors.isMainHandReady）。'
    '★ TaCZ 的其它条目（ADS/换弹/切模式/枪托近战…）仍缺执行器，见 tacz_gun 条目。'
)

new_executors = []
for e in d['executors']:
    if e['kind'] == 'bow':
        e['kind'] = 'charged_shot'
        e['status'] = 'implemented'
        e['handles'] = [
            'maid_native:bow_shot',
            'maid_native:crossbow_shot',
            'maid_native:trident',
            'maid_native:throw_any_item',
            'maid_native:danmaku',
        ]
        e['api'] = ('起手 startUsingItem(MAIN_HAND) → 蓄力 → stopUsingItem() + '
                    'performRangedAttack(target, 力度)；弩额外 setChargingCrossbow')
        e['evidence'] = ('MaidShootTargetTask.java:98-113（弓）· MaidShootTargetAnyItemTask.java:97,103（投掷）'
                         '· TaskDanmakuAttack.java:65（弹幕直接复用 MaidShootTargetTask）'
                         '· MaidTridentTargetTask.java:91-104（三叉戟，力度 0）'
                         '· MaidCrossbowAttack.java:37-63（弩，力度 1.0F）')
        e['note'] = {'zh_cn': CHARGED_NOTE}
        new_executors.append(e)
    elif e['kind'] in ('trident', 'crossbow'):
        continue          # 已被 charged_shot 覆盖
    else:
        new_executors.append(e)

gun_exec = {
    'kind': 'gun',
    'status': 'implemented',
    'handles': ['maid_native:gun_shot', 'tacz:shoot'],
    'api': 'GunCommonUtil.performGunAttack(maid, target, maid.getMainHandItem())',
    'evidence': ('GunShootTargetTask.java:68（TLM 自己就是这么调的）· GunCommonUtil.java:88'
                 '（★ 公开入口，内部同时处理 TaCZ 与卓越前线）'),
    'note': {'zh_cn': GUN_NOTE},
}

out = []
for e in new_executors:
    out.append(e)
    if e['kind'] == 'charged_shot':
        out.append(gun_exec)

d['executors'] = out
d['generatedFrom'] = ('M4：把清单里的动作与【服务端执行实现】对应起来。★ 这是数据（不是代码）—— '
                      '因此「哪些动作还没执行器」可以被离线断言。'
                      '本轮补齐蓄力射击 5 兄弟 + 枪械 2 动作（零新依赖）。')

with io.open(PATH, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(d, f, ensure_ascii=False, indent=2)
    f.write('\n')

print('executors.json 已更新，当前 %d 个执行器：' % len(d['executors']))
for e in d['executors']:
    print('  %-16s %-12s %d 个动作' % (e['kind'], e['status'], len(e.get('handles', []))))
