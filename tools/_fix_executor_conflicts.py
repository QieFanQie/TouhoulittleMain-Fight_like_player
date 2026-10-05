"""修 executors.json 的两处声明冲突 + 记录一处**重要发现**。

## 冲突（校验脚本抓到）
· `tacz:shoot` 同时被 `gun`(implemented) 与 `tacz_gun`(planned) 声明
· `maid_native:danmaku` 同时被 `charged_shot`(implemented) 与 `danmaku`(blocked) 声明
⇒ 后者会把 implemented 覆盖成 planned（覆盖率账本因此失真）。已移除重复声明；
  `danmaku` 这个 kind 随之清空 ⇒ 删除（它的价值只是"载体判据未查证"，那是 W12，不是执行器问题）。

## ★ 重要发现：TLM 的兼容助手**不能**被我们的任务复用

`compat/slashblade/SlashBladeCompat.java:24-25`：

    public static void swingSlashBlade(EntityMaid maid, ItemStack itemInHand) {
        if (SlashBladeCompat.isSlashBladeItem(itemInHand)
            && maid.getTask().getUid().equals(TaskAttack.UID)) {     // ★★ 写死要求 TLM 的 attack 任务

⇒ 它被**硬门控在 `touhou_little_maid:attack` 这个任务 UID 上**。
而我们的任务是 `fight_like_player:player_like_combat` ⇒ **调用它会直接变成 no-op**。

⇒ 这解释了为什么 `slashblade` 只能继续 `blocked`：
  **不是"懒得做"，而是 TLM 现成的拔刀剑支持压根不对外开放给别的任务。**
  要支持就必须自己实现 `AttackManager.doSlash`（需要拔刀剑编译依赖）。
"""
import io
import json
import os

PATH = os.path.join('catalog', 'executors.json')
d = json.load(io.open(PATH, encoding='utf-8'))

out = []
removed = []
for e in d['executors']:
    if e['kind'] == 'danmaku':
        removed.append('danmaku（handles 已并入 charged_shot）')
        continue
    if e['kind'] == 'tacz_gun':
        e['handles'] = [h for h in e.get('handles', []) if h != 'tacz:shoot']
        e['note'] = {'zh_cn':
                     '★ TaCZ 侧【开火】已由 `gun` 执行器覆盖（用 TLM 的公开 GunCommonUtil）。'
                     '本条目剩下的是 TaCZ 的其它操作（ADS/换弹/切射击模式/枪托近战…），'
                     '它们多数是 role=stance/maintenance（不进候选集），少数仍缺执行器。'
                     '⚠️ 它们需要读枪的物品数据（枪型/射击模式）⇒ 关联 W23。'}
    if e['kind'] == 'slashblade':
        e['note'] = {'zh_cn':
                     '★★ 为什么【必须】blocked（实测证据，不是"懒得做"）：'
                     'TLM 的 `compat/slashblade/SlashBladeCompat.java:24-25` 里，'
                     '`swingSlashBlade(maid, stack)` 被**硬门控**在'
                     '`maid.getTask().getUid().equals(TaskAttack.UID)` 上 —— '
                     '即它只对 TLM 自己的 `attack` 任务生效。'
                     '我们的任务是 `fight_like_player:player_like_combat` ⇒ **调用它等于 no-op**。'
                     '⇒ 要支持拔刀剑，必须自己实现 `AttackManager.doSlash`（需给构造加拔刀剑编译依赖），'
                     '并且 S1 的 B4 风险（专用服务器 NoClassDefFoundError）仍未验证。'}
    out.append(e)

d['executors'] = out
with io.open(PATH, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(d, f, ensure_ascii=False, indent=2)
    f.write('\n')

print('已移除冲突/清空条目：%s' % removed)
print('当前 %d 个执行器：' % len(d['executors']))
for e in d['executors']:
    print('  %-16s %-12s %s' % (e['kind'], e['status'], e.get('handles', [])))
