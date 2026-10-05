"""把 goety / irons 执行器写进覆盖率账本。"""
import io
import json
import os

PATH = os.path.join('catalog', 'executors.json')
d = json.load(io.open(PATH, encoding='utf-8'))

for e in d['executors']:
    if e['kind'] == 'goety_cast':
        e['status'] = 'implemented'
        e['api'] = '((Spell) IWand.getSpell(wand)).mobSpellResult(maid, wand)'
        e['evidence'] = ('IWand.java:59-62（getSpell 是 default 方法）· CryologerServant.java:600'
                         '（Goety 自己的仆从就这么调 mobSpellResult）· DarkWand.java:763'
                         '（★ MagicResults 硬门控在 Player ⇒ 白烟，所以必须绕过物品路径）')
        e['note'] = {'zh_cn':
            '★★ 为什么不走物品路径：DarkWand.MagicResults(:763) 的第一行是 '
            '`if (spell != null && caster instanceof Player)` —— 女仆走 else ⇒ '
            'failParticles(:132) + FIRE_EXTINGUISH，**白烟 + 灭火音且不施法**。'
            '⇒ 直接调 mobSpellResult，完全不经过物品路径 ⇒ 天然没有白烟。'
            '⚠️ 已知边界：**光束类法术（AbstractBeam）在当前实现下不存活** —— '
            '它们靠 isUsingItem() 活着，而这需要 startUsingItem；'
            '手上凡拿着 goety:dark_wand，这一步就会触发 onUseTick(:449→:488→MagicResults) ⇒ 白烟回来。'
            '⇒ 要支持它们必须【自备一把 onUseTick 空实现的 IWand 物品】（调律师的 TunerWand 就是它）。'
            '登记 W35。'}
    elif e['kind'] == 'irons_cast':
        e['status'] = 'implemented'
        e['handles'] = ['irons:cast_spell', 'irons:cast_scroll']
        e['api'] = 'spell.onCast(Level, int spellLevel, LivingEntity, CastSource.MOB, MagicData)'
        e['evidence'] = ('javap 实测 irons_spellbooks-1.20.1-3.16.3：'
                         'AbstractSpell#onCast(Level,int,LivingEntity,CastSource,MagicData) · '
                         'ISpellContainer#isSpellContainer/get/getActiveSpells · SpellSlot#getSpell/getLevel')
        e['note'] = {'zh_cn':
            '★ **不走 canBeCastedBy**（那是 Player 签名）⇒ 不检查"是否已学习"、不扣蓝 —— '
            '与 Q7「白嫖」的决策一致，也正是 ISS 自己 mob 路径的做法（CastSource.MOB）。'
            '⚠️ 当前实现取【第一个可用法术槽】，尚未按局势挑法术。'
            '⚠️ **recast（多段施法）未实现**（需跟踪施法会话）⇒ 登记 W36。'
            '⚠️ 支援类法术可能打到敌人身上（Utils.preCastTargetHelper 被 mixin 短路成"女仆锁定目标"），'
            '这是万法皆通时代就存在的已知缺陷，见 docs/01 §6.3。'}

d['generatedFrom'] = ('M4：动作 → 服务端执行实现的映射。★ 这是数据（不是代码）—— '
                      '因此「哪些动作还没执行器」可以被离线断言。'
                      '本轮补齐：蓄力射击 5 兄弟 + 枪械 2 + Goety 施法 + 铁魔法施法。')

with io.open(PATH, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(d, f, ensure_ascii=False, indent=2)
    f.write('\n')
print('已更新')
