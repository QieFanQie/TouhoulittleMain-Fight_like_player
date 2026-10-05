# -*- coding: utf-8 -*-
"""审计 3：**瞬间指令的执行点不许挂在"有条件才跑"的循环上**。

## 为什么需要它（委托方 2026-10-05 实测：「瞬时指令有 bug，无法生效」）

在修之前，指令的每 tick 推进只有**一个**调用点：

    PlayerLikeCombat.tick(...)  →  DirectiveHolder.tick(maid, gameTime)

而那个脑行为被**门控在"她有攻击目标"**上：

    PlayerLikeCombat.create() → ctx.group(registered(LOOK_TARGET), present(ATTACK_TARGET))

后果（委托方三条实测全部落在这一条上）：
  · 非战斗时说「处死所有仆从」⇒ 那条瞬间指令**永远不执行**，
    60 tick 后被 `DirectiveBus#INSTANT_MAX_AGE_TICKS` 判成"放太久"作废；
  · 「把枪拿出来」（`switch_item`）同理；
  · 「只用某件物品」（`only_item`）的换手步骤也在那个行为里 ⇒ 非战斗时永不发生。

★ 这是同一条教训在项目里的**第三次**出现（见 docs/13 第 53 条）：
  **凡"每 tick 必须前进"的东西（引导法术 / 刀技 / 瞬间指令 / 换手伺服），
    都不能挂在"有条件才跑"的循环上。**

## 检查项（结构性，读源码即可判定）
  1. `DirectiveHolder.tick(` 必须由 `compat/event/DirectiveTicker.java` 调用；
  2. 它**不得**再出现在 `PlayerLikeCombat.java` 里（否则等于又把执行点挂回门控行为）；
  3. `DirectiveTicker` 必须挂在 `MaidTickEvent` 上，且必须在 `LittleMaidCompat` 里注册；
  4. `DirectiveHolder.itemServo(` 必须被同一个驱动器每 tick 调用（换手同理不能挂在战斗行为上）。
"""
import io
import os
import re
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

TICKER = os.path.join(BASE, 'src', 'main', 'java', 'com', 'touhoulittlemad', 'fightlikeplayer',
                      'compat', 'event', 'DirectiveTicker.java')
PLC = os.path.join(BASE, 'src', 'main', 'java', 'com', 'touhoulittlemad', 'fightlikeplayer',
                   'compat', 'behavior', 'PlayerLikeCombat.java')
COMPAT = os.path.join(BASE, 'src', 'main', 'java', 'com', 'touhoulittlemad', 'fightlikeplayer',
                      'compat', 'LittleMaidCompat.java')
GOETY_PICKER = os.path.join(BASE, 'src', 'main', 'java', 'com', 'touhoulittlemad',
                            'fightlikeplayer', 'compat', 'exec', 'goety', 'GoetyFocusOps.java')
IRONS_PICKER = os.path.join(BASE, 'src', 'main', 'java', 'com', 'touhoulittlemad',
                            'fightlikeplayer', 'compat', 'exec', 'irons', 'IronsSpells.java')


def read(p):
    with io.open(p, encoding='utf-8') as f:
        return f.read()


def strip_comments(java):
    """去掉注释 —— 否则"注释里提到 tick"会被误判成调用。"""
    java = re.sub(r'/\*.*?\*/', '', java, flags=re.S)
    return re.sub(r'//[^\n]*', '', java)


def main():
    problems = []
    for p in (TICKER, PLC, COMPAT):
        if not os.path.exists(p):
            problems.append('缺少文件：%s' % os.path.relpath(p, BASE))
    if problems:
        print('\n'.join('  x ' + p for p in problems))
        return 1

    ticker = strip_comments(read(TICKER))
    plc = strip_comments(read(PLC))
    compat = strip_comments(read(COMPAT))

    # 1) 驱动器必须调用 tick 与 itemServo
    #   ★ 用**正则**而不是字面串：Java 里一个调用可以跨行写
    #     （`...ExtinguishServo` 换行再 `.tick(...)`）—— 字面匹配会漏判，第一版就漏了。
    def has_call(src, cls, method):
        return re.search(cls + r'\s*\.\s*' + method + r'\s*\(', src) is not None

    if not has_call(ticker, 'DirectiveHolder', 'tick'):
        problems.append('DirectiveTicker 没有调用 DirectiveHolder.tick(...)')
    if not has_call(ticker, 'DirectiveHolder', 'itemServo'):
        problems.append('DirectiveTicker 没有调用 DirectiveHolder.itemServo(...)')
    # ★ 只查"调用"，不查名字 —— 行为层里仍会有 OffhandStance.forget / ExtinguishServo.forget
    #   （卸载时清状态，那是另一件事，不该被判成"又挂回去了"）
    for servo in ('OffhandStance', 'ExtinguishServo'):
        if not has_call(ticker, servo, 'tick'):
            problems.append('DirectiveTicker 没有推进伺服 %s.tick（状态类伺服必须每 tick 跑）' % servo)
        if has_call(plc, servo, 'tick'):
            problems.append('PlayerLikeCombat 里又出现了 %s.tick —— 非战斗时它会停（举盾/灭火失效）'
                            % servo)
    if 'MaidTickEvent' not in ticker:
        problems.append('DirectiveTicker 没有挂在 MaidTickEvent 上')
    if 'isClientSide' not in ticker:
        problems.append('DirectiveTicker 没有做客户端侧守卫（会在客户端也跑一遍）')

    # 2) 行为层不许再执行瞬间指令（否则又回到"只在有目标时跑"）
    # ★★ 第十七轮续：在途动作推进（tickInFlight）也必须挂在驱动器上
    #   （挂在"她有攻击目标"的行为里 ⇒ 目标一没就停摆：法术不结算、弹匣不再开火）
    if not has_call(ticker, 'ActionExecutors', 'tickInFlight'):
        problems.append('DirectiveTicker 没有调用 ActionExecutors.tickInFlight(...)'
                        ' —— 在途动作会在"她不在战斗"时停摆')
    if has_call(plc, 'ActionExecutors', 'tickInFlight'):
        problems.append('PlayerLikeCombat 里又出现了 tickInFlight(...)'
                        ' —— 会被推进两次（射速翻倍）或又被门控')
    if not has_call(plc, 'PlayerLikeCombat', 'noteInFlightCompleted') \
            and 'void noteInFlightCompleted(' not in plc:
        problems.append('PlayerLikeCombat 没有 noteInFlightCompleted(...)（完成信号无处可去）')
    if not has_call(ticker, 'PlayerLikeCombat', 'noteInFlightCompleted'):
        problems.append('DirectiveTicker 报完成时没有回调 '
                        'PlayerLikeCombat.noteInFlightCompleted(...)')

    if has_call(plc, 'DirectiveHolder', 'tick'):
        problems.append('PlayerLikeCombat 里又出现了 DirectiveHolder.tick(...)'
                        ' —— 瞬间指令会被门控在"她有攻击目标"上（这正是修掉的那个 bug）')
    if 'equipToHand' in plc:
        problems.append('PlayerLikeCombat 里又出现了换手实现（equipToHand）'
                        ' —— 换手必须由指令伺服负责，否则非战斗时"拿出来"永不发生')

    # 3) ★★ 第十七轮续：**召唤位**必须判在"挑法术"那一步
    #    （清单里"召唤"不是独立动作：只有 cast_focus / cast_spell 的 **override** 带 REINFORCE，
    #      它们的**默认向量是 0** ⇒ 动作级过滤会把火球一起挡掉）。
    for picker in (GOETY_PICKER, IRONS_PICKER):
        if not os.path.exists(picker):
            problems.append('缺少文件：%s' % os.path.relpath(picker, BASE))
            continue
        src = strip_comments(read(picker))
        # ★ 用正则（同一个调用可以跨行写 —— 字面匹配会漏判，我第一版就漏了）
        if re.search(r'ServantSlots\s*\.\s*summonAllowed\s*\(', src) is None:
            problems.append('%s 没有调用 ServantSlots.summonAllowed(...)'
                            ' —— 召唤位这道门会失效（文档承诺过的那条）'
                            % os.path.basename(picker))

    # 4) 必须被注册
    if 'new com.touhoulittlemad.fightlikeplayer.compat.event.DirectiveTicker()' not in compat:
        problems.append('LittleMaidCompat 没有注册 DirectiveTicker（驱动器不会生效）')

    print('=== 瞬间指令接线审计 ===')
    print('  驱动器            : %s' % ('OK' if not problems else '有问题'))
    print('  MaidTickEvent 挂钩 : %s' % ('OK' if 'MaidTickEvent' in ticker else '缺'))
    print('  行为层不再执行瞬间指令 : %s'
          % ('OK' if 'DirectiveHolder.tick(' not in plc else '★ 又挂回去了'))
    print('  换手由伺服负责      : %s'
          % ('OK' if 'equipToHand' not in plc else '★ 行为层又有换手了'))
    print()
    if problems:
        print('!! 问题：')
        for p in problems:
            print('   x ' + p)
        return 1
    print('OK  瞬间指令与换手伺服都挂在"每 tick 必跑"的驱动器上（不再依赖她在战斗）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
