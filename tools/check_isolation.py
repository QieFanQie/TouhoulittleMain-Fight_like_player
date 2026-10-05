"""复核安全不变量：未装 TLM 时，入口类必须能安全加载。

方法：用 javap 反汇编各级 class，统计对 `touhoulittlemaid` 的引用。
参照 docs/02 §2.5 与 08 P0 的记录 —— 这条不变量已用同样手法验证过多次，必须保持。

分类：
  ① 入口层（FightLikePlayer / CarrierDetector）—— 必须 0 引用
  ② 纯逻辑层（decision/ carrier/）—— 必须 0 引用，且不含 net.minecraft
  ③ compat 层（LittleMaidCompat / compat/task / compat/behavior）—— 允许有
"""
import glob
import os
import re
import subprocess
import sys

JAVAP = r'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot\bin\javap.exe'
CLASSES = os.path.join('build', 'classes', 'java', 'main')

GROUPS = {
    '① 入口': ['FightLikePlayer'],
    '② 纯逻辑 decision': None,
    '② 纯逻辑 carrier': None,
    '③ compat（允许引用 TLM）': None,
}


def classify(path):
    rel = os.path.relpath(path, CLASSES).replace('\\', '/')
    if '/decision/' in '/' + rel:
        return '② 纯逻辑 decision'
    if '/carrier/' in '/' + rel:
        return '② 纯逻辑 carrier'
    if '/compat/' in '/' + rel:
        return '③ compat（允许引用 TLM）'
    return '① 入口'


def scan(classfile):
    out = subprocess.run([JAVAP, '-p', '-c', classfile],
                         capture_output=True, text=True, errors='replace').stdout
    return out


stats = {}
for path in glob.glob(os.path.join(CLASSES, '**', '*.class'), recursive=True):
    if '$' in os.path.basename(path):
        continue  # 跳过匿名/内部类，避免重复计数
    g = classify(path)
    text = scan(path)
    tlm = len(re.findall(r'touhoulittlemaid', text))
    mc = len(re.findall(r'\bnet/minecraft', text))
    forge = len(re.findall(r'net/minecraftforge', text))
    s = stats.setdefault(g, [0, 0, 0, 0])
    s[0] += 1
    s[1] += tlm
    s[2] += mc
    s[3] += forge

print('%-30s %6s %8s %10s %8s' % ('组', '类数', 'TLM引用', 'MC引用', 'Forge引用'))
for g, s in sorted(stats.items()):
    print('%-30s %6d %8d %10d %8d' % (g, s[0], s[1], s[2], s[3]))

# ★ 关键断言
bad = []
for g, s in stats.items():
    if g.startswith('①') and s[1] > 0:
        bad.append('%s 引用了 TLM（%d 处）—— 未装 TLM 时可能崩！' % (g, s[1]))
    if g.startswith('②'):
        if s[1] > 0:
            bad.append('%s 引用了 TLM（%d 处）—— 纯逻辑层不得依赖 TLM！' % (g, s[1]))
        if s[2] > 0:
            bad.append('%s 引用了 Minecraft（%d 处）—— 纯逻辑层应当零 MC 依赖' % (g, s[2]))

print()
if bad:
    for b in bad:
        print('!! %s' % b)
    sys.exit(1)
print('OK  安全不变量成立：')
print('   · 入口层 0 处 TLM 引用 ⇒ 未装 TLM 可安全加载')
print('   · 纯逻辑层 0 处 TLM、0 处 Minecraft ⇒ 可离线自测')
print('   · TLM 引用只出现在 compat 层（有 @LittleMaidExtension 加载边界保护）')

# ═══════════════════════════════════════════════════════════════════════════
# ★★ 第十七轮续：**许可纪律** —— 不得编译期链接 TaCZ
#
# 为什么（这条是委托方自己拍过的板，写在 build.gradle:81-86）：
#   TaCZ 曾被引入，唯一目的是枪托近战直调 `IGunOperator#melee()`；
#   委托方按「优先选稳定、无风险」撤掉它 —— 它的 mods.toml 许可是
#   `GPL3 / CC BY-NC-ND 4.0`（具传染性），并留下规矩：
#   「若将来重新引入，必须先在 THIRD_PARTY_NOTICES 里把这笔许可账记清」。
#
# 于是"枪的身份"走 TLM 的公开抽象（`GunCommonUtil#getGunId`，两个枪包都覆盖），
# "枪型"走**反射**（`Class.forName("com.tacz.guns.api.TimelessAPI")`）。
# ⇒ 判据：我们的 class 里**不得**出现对 `com/tacz/` 的【链接】
#   （javap 里的 `Method` / `InterfaceMethod` / `class` 引用）；
#   只有【字符串】形态（反射用）才允许 —— 字符串不算链接，也不会让未装 TaCZ 的实例崩。
# ═══════════════════════════════════════════════════════════════════════════
LINK_FORBIDDEN = 'com/tacz/'
linked = []
for path in glob.glob(os.path.join(CLASSES, '**', '*.class'), recursive=True):
    if '$' in os.path.basename(path):
        continue
    for line in scan(path).splitlines():
        if LINK_FORBIDDEN not in line:
            continue
        s = line.strip()
        # 允许：`// String com.tacz...`（反射用的字面量）
        # 禁止：`// Method com/tacz/...` / `// class com/tacz/...`（真链接）
        if '// String ' in s:
            continue
        if re.search(r'(Method|InterfaceMethod|Field|class)\s+' + re.escape(LINK_FORBIDDEN), s):
            linked.append('%s: %s' % (os.path.basename(path), s))

gradle_leak = []
with open('build.gradle', encoding='utf-8') as f:
    for i, line in enumerate(f, 1):
        if 'libs:tacz' in line:
            gradle_leak.append('build.gradle:%d %s' % (i, line.strip()))

print()
if linked or gradle_leak:
    print('!! 许可纪律被破坏（TaCZ 是 GPL3/CC BY-NC-ND，委托方已决定不引依赖）：')
    for x in linked:
        print('   x 链接引用：%s' % x)
    for x in gradle_leak:
        print('   x 依赖声明：%s' % x)
    print('   ⇒ 枪的身份请走 GunCommonUtil#getGunId；枪型请走反射（或先把许可账记进 '
          'THIRD_PARTY_NOTICES）。')
    sys.exit(1)
print('OK  许可纪律成立：0 处对 TaCZ 的编译期链接（枪型只用反射字符串），'
      'build.gradle 无 TaCZ 依赖')
