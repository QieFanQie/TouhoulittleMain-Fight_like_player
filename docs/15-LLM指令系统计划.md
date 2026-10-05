# 15 · LLM 指令系统 计划文档（★ 后续按本文执行）

> **本文回答什么问题**：委托方第十二轮提出的「LLM 动态指挥」的**正式形态** ——
> LLM 不再只调几个标量旋钮，而是**下令**：从一张**指令表**里选指令，影响整个体系。
> **什么时候读**：动手做这一块之前**先读本文**，做完一段回来更新「进度」列。
>
> ★ 本文是**计划**（未实现部分明确标注）。已实现的部分会写「✅ 已完成」。
> ★ 分工已定（委托方 2026-10-02 明确）：**评分类 → JEV**（现有 `Advisor` 那一套继续用）；
> **指令类 → LLM**（本文这一套）。

---

## 0. 一句话目标

> **玩家/LLM 用"自然语言级别的指令"管理女仆的战斗方式，而不是逐个动作下命令。**
> LLM 看到「正在施行的持续指令 + 可实行的全部指令」，决定**下达哪些、取消哪些**；
> 我们负责把每条指令**强制执行**（过滤候选、约束步法、改写执行前置……）。

---

## 1. 为什么它必须**独立于既有通道**（委托方明确要求）

| 既有机制 | 它解决什么 | 为什么不能塞进它 |
|---|---|---|
| 弹簧 + 最近邻（决策层） | "这一步做什么" | 指令是**约束**，不是"再做一件事"—— 塞进动作空间会变成"她可以选择不听" |
| `TuningBus`（旋钮） | 打分与节奏的**连续量** | 指令是**离散的、有语义的开关**（"仅用魔法"），不是 0~2 的倍数 |
| 步法（并发伺服） | 脚下 | 指令要能**约束**步法（"保持 8 格"），但方向相反：指令在上，步法在下 |

⇒ **新增第 5 层：`DirectiveBus`（指令总线）**，位置：
<pre>
  LLM / 玩家 / 女仆对话
        │  下达 / 取消
        ▼
   DirectiveBus（每女仆一份）· 瞬间队列 + 持续集合（带 TTL）
        │  查询（每 tick）
        ▼
  ① 候选集过滤  ② 执行器前置  ③ 步法约束  ④ 法术/聚晶挑选  ⑤ 瞬间动作直调
        ▼
   决策层（弹簧 + 最近邻）—— 【不感知指令的存在】，它只是在一个变小的候选集里选
</pre>
★ 关键性质：**决策层不需要知道指令**。指令的作用方式是"改它能看见的世界"
（候选集变小、步法被约束），而不是"在决策里插一段 if"。这样两者的耦合面只有一个：`DirectiveBus` 的查询接口。

---

## 2. 指令表（我生成的候选全集 —— 委托方可以增删）

### 2.1 持续指令（sustained：下达后一直生效，直到取消 / TTL 到期）

| id | 中文 | 参数 | 强制手段（落点） | 可观测判据 |
|---|---|---|---|---|
| `magic_only` | 仅使用魔法攻击 | — | ① 候选集过滤：只保留 `goety:cast_focus` / `irons:cast_*` | `/flp whyfull` 里近战动作显示 `DROPPED_BY_DIRECTIVE`；日志不再出现 `maid_native:melee_swing` |
| `melee_only` | 仅使用近战/刀技 | — | ① 只保留近战、刀技 | 同上（反向） |
| `ranged_only` | 仅远程 | — | ① 只保留弓/弩/枪/远程法术 | —— |
| `keep_distance` | 保持距离攻击 | `min`（格，默认 8） | ③ 步法：目标距离 < min 时强制 retreat；① 同时禁用"贴身类"动作 | `/flp why` 的步法显示 `指令约束：keep_distance(8)`；实测距离长期 ≥ min |
| `close_in` | 贴身缠斗 | `max`（格，默认 3） | ③ 步法强制接近；① 禁用远程 | 距离长期 ≤ max |
| `hold_position` | 守点不动 | `radius`（默认 4） | ③ 步法 hold（限制在出生点/指令下达点 radius 内） | 位置漂移 < radius |
| `no_retreat` | 禁止后退 | — | ③ 步法禁用 retreat/disengage 方向 | 不再出现 `disengage` 被选中 |
| `no_item_switch` | 不换手 | — | ② 执行器前置：跳过 `ensureCarrierInHand` ⇒ 候选集只由主手物品决定 | 日志不再出现 `★ 换手：…` |
| `focus_category` | 只用某类聚晶 | `category`（如 `SUMMON`） | ④ 聚晶挑选：只在该类别里挑 | 日志里 `goety 施法 …（SUMMON·…）` |
| `ban_focus` | 禁用某颗聚晶/法术 | `label`（类名） | ④ 从挑选池里排除 | 该法术不再出现 |
| `conserve_ammo` | 省弹药（点射） | `shots`（默认 2） | ⑤ 枪械由"一梭子"变"点射"（覆盖 `gun.magazineShots`） | 日志 `枪械：打满 N 发 ⇒ 收枪` 的 N 变成 shots |
| `protect_owner` | 优先护主 | — | ① 优先护友类动作（过滤出 `ALLY_CARE` 高的动作并加偏置 **走独立通道**，不动弹簧） | `/flp why` 显示指令约束；她更常挡在主人与敌人之间 |
| `no_summons` | 不召唤 | — | ① 排除召唤类动作用 ④ 排除召唤类聚晶 | —— |

### 2.2 瞬间指令（instant：下达后立刻执行一次，不进持续集合）
| id | 中文 | 参数 | 强制手段 | 可观测判据 |
|---|---|---|---|---|
| `interrupt` | 中断当前动作 | — | ⑤ `ActionExecutors.abort(maid)` | 日志 `看门狗/中断`，在途状态被清 |
| `disengage` | 脱战撤离 | `ticks`（默认 60） | ⑤ 清 `ATTACK_TARGET` + 强制 retreat N tick | 日志显示目标被清、她后退 |
| `reload_now` | 立刻换弹 | — | ⑤ 枪械：进入换弹分支（TaCZ 自己触发） | 日志出现换弹相关行 |
| `recall_servants` | 召回全部仆从 | — | ⑤ `GoetyServantOps.recallServants` | 仆从被拉回 |
| `dismiss_servants` | 清理临时仆从 | — | ⑤ `dismissTemporaryServants` | 临时仆从消失 |
| `stance_guard` | 立刻举盾 | — | ⑤ 副手姿态：强制举盾 N tick（覆盖 `OffhandStance` 的自动判断） | 副手进入 `startUsingItem` |
| `stance_attack` | 立刻收盾进攻 | — | ⑤ 强制放下盾 | 副手停止使用 |
| `heal_now` | 立刻治疗 | — | ⑤ 若持有治疗类法术/药水，强制选它（一次性偏好） | 日志显示治疗法术被选 |
| `drop_focus` | 换掉当前聚晶 | `category`? | ⑤ 强制下一次 `cast_focus` 换聚晶 | 日志 `装聚晶（调换）` |

> ★ **两条护栏**（每一条都来自本项目的教训）：
> 1. **TTL 必须有上限**（持续指令默认 2 分钟、上限 10 分钟）—— 否则就是又一个"永久卡死"
>    （见 [13 §26](13-更正记录与教训.md)：逐条堵漏永远堵不完，所以"自动过期"是必须的）；
> 2. **指令必须可解**（`/flp directive clear` + 脱战自动清 + 女仆死亡/卸载清）——
>    否则玩家会看到"她为什么突然不用魔法了"这种查不出来的现象。
> 3. ★★ **瞬间指令还要有「保鲜期」**（第 15 轮补，60 tick = 3 秒）：
>    瞬间指令的执行点在 `PlayerLikeCombat.tick` 最前面，而**那个行为只在"她有攻击目标"时运行**
>    ⇒ 她没在打架时下的"举盾"会一直躺在队列里，**直到她下次进战斗才突然生效**
>    （观感上就是"几分钟前说的话突然执行"）。⇒ 放太久即作废，并 INFO 说明原因。
>    ★ 判据仍是那句：**"瞬间 = 立刻"，晚 3 秒就不叫立刻了**。

---

## 3. LLM 看到什么 / 输出什么

### 3.1 发给 LLM 的内容（prompt 的一部分，**指令表自动生成**）

```json
{
  "role": "你是一名战斗女仆的【战术指挥官】。你不能指定她这一秒做什么动作，
           只能从下面的指令表里下达/取消指令来管理她的战斗方式。",
  "directives_available": [
    {"id":"magic_only","kind":"sustained","zh":"仅使用魔法攻击","params":{},
     "enforce":"过滤候选集：只保留施法类动作",
     "base_on":["spells_available","equipment.has_wand","equipment.has_spellbook_or_scroll"]},
    {"id":"keep_distance","kind":"sustained","zh":"保持距离攻击","params":{"min":"格数，默认8"},
     "enforce":"步法在距离<min时强制后撤，并禁用贴身动作",
     "base_on":["self.distance_to_target","self.gait"]},
    {"id":"interrupt","kind":"instant","zh":"中断当前动作","params":{},"enforce":"中止在途动作",
     "base_on":["self.in_flight_action"]}
  ],
  "directives_active": [
    {"id":"magic_only","params":{},"remaining_ticks":1800,"issued_by":"llm"}
  ],
  "scene": {
    "self": {"health_pct":0.62,"on_fire":false,"in_flight_action":"goety:cast_focus",
             "gait":"保持 8 格","distance_to_target":6.4,
             "main_hand":"goety:wand","off_hand":"minecraft:shield"},
    "equipment": {"has_melee_weapon":true,"has_wand":true,"has_spellbook_or_scroll":false,
                  "has_gun":false,"has_bow":false,"has_shield":true,"has_extinguisher":false,
                  "inventory_items":7},
    "spells_available": [
      {"label":"FireballSpell","category":"ATTACK_SINGLE","source":"WAND"},
      {"label":"HealSpell","category":"HEALING","source":"BAG","blocked_by_your_directive":true}
    ],
    "servants": 2,
    "owner": {"health_pct":0.34,"distance":5.1},
    "battle": {"has_target":true,"enemies_nearby":3,"nearest_enemy_distance":6.4,
               "target_health_pct":0.8}
  },
  "recent_behaviour": { "...BehaviorStats 的 200 tick 滑窗..." },
  "how_to_answer": "只输出 JSON：{\"issue\":[{id,params?}],\"cancel\":[id],\"why\":\"一句话依据\"}"
}
```
★ `directives_available` **由代码从指令表生成**（不是手写进提示词）⇒ 加一条指令不用改提示词，
也不会出现"提示词里写了、代码里没有"这种漂移。
★ `base_on` 同样自动生成（见 §3.4）—— 它告诉模型「下这条之前先看哪些字段」。
★ `scene` 是**第 14 轮核实后补上的**（此前只有 `recent_behaviour` 那点统计量），见 §3.4。

### 3.2 LLM 的输出（容错解析，复用 `LlmAdvisor.extractFirstJsonObject` 的括号配对法）

```json
{"issue":[{"id":"keep_distance","params":{"min":8}},{"id":"interrupt"}],
 "cancel":["magic_only"],
 "why":"她一直在贴身挨打，先拉开再打断当前动作"}
```
解析规则（与旋钮补丁同一条纪律）：
① 抠第一个配对 `{...}`；② **白名单外的 id 丢弃并登记**；③ 参数按键校验并钳制；
④ `cancel` **先于** `issue` 应用（"换指令"要能一步完成）；
⑤ 解析失败 ⇒ **这一轮不下达任何指令**（不是"下达一条默认的"）。

### 3.3 与旋钮的关系（委托方 2026-10-02 的裁决）

> 「对于旋钮评分，是我错了，我觉得**评分类的交给 jev 可以胜任**。」

⇒ **旋钮继续由 JEV（`Advisor`）管**；LLM 的职责是**指令**。
`LlmBridge` 里那套"从回复里抠旋钮补丁"的能力**保留但不再是重点**（默认 `observe`，且文档标注"评分类已归 JEV"）。
★ 这样一来两半的边界干净：**JEV 管连续量（旋钮），LLM 管离散开关（指令）**。

### 3.4 ★★ 核实：每条指令的「依据」，模型拿得到吗？（第 14 轮）

> 委托方问：「我想知道指挥 llm 能得知什么信息，你**核实一下**：
> **每个指令的下达所需要的信息，llm 都能获取到相关内容吗？**」

**核实结论（第 13 轮末的真实状态）：拿不到 —— 缺得还不少。**
当时它手里只有 `BehaviorStats`（动作直方图 / 受伤 / 需求均值）+ 指令表 + 旋钮。
逐条对照之后，下面这些指令**它没有依据去下、也没有依据去撤**：

| 指令 | 它需要知道 | 第 13 轮末有吗 |
|---|---|---|
| `magic_only` / `melee_only` / `ranged_only` | 她手上/包里到底有没有法杖、法术书、枪、近战武器 | ❌ 完全没有 |
| `focus_category` / `ban_focus` | 她**实际拥有**哪些法术（标签 + 类别） | ❌ 完全没有 |
| `conserve_ammo` / `reload_now` | 她有没有枪 | ❌ 完全没有 |
| `stance_guard` / `stance_attack` | 她有没有盾 | ❌ 完全没有 |
| `keep_distance` / `close_in` | 现在与目标几格、当前步法 | ❌ 只有"需求轴"，没有距离 |
| `no_summons` / `recall_servants` / `dismiss_servants` | 她有几个仆从 | ❌ 完全没有 |
| `protect_owner` | 主人的血量与距离 | ❌ 完全没有 |
| `interrupt` | 她此刻是不是正在引导/蓄力 | ❌ 完全没有 |
| `heal_now` | 她有没有治疗类法术 | ❌ 完全没有 |
| `disengage` | 自身血量（有 ✓）、周围敌人（❌） | ◐ 一半 |
| `hold_position` / `no_retreat` / `no_item_switch` | 基本不需要额外信息 | ✓ |

**★ 处置（三件事，全部落地）**

1. **新增 `decision/thinking/CombatScene`（纯逻辑）**：把"她此刻的处境"显式建模，
   并给出**权威键集合 `KEYS`**；游戏侧由 `compat/think/CombatSceneBuilder` 取真值
   （装备判定**复用手上的口径方法**，不另造一套：`OffhandStance.isShield`、
   `ExtinguishServo.isExtinguisher`、`GoetyExecutors.hasAnyWand`、`IronsExecutors.hasAnySpell`…）。
   ⇒ `LlmAdvisor.userContent(...)` 多了一个 `scene` 字段，进 prompt。
2. **每条指令声明 `needs`，并由自测逐条断言**（`DirectiveSpec.Spec.needs()`）：
   - 21 条**每条都必须声明**（漏了 ⇒ 自测失败）；
   - 声明的键**必须真实存在于 `CombatScene.KEYS`**（声明了没人产出 ⇒ 自测失败）；
   - `CombatScene` 必须**真的产出每一个键**（键名写错 ⇒ 自测失败）；
   - 这些键名必须**在那段要发出的 JSON 里搜得到**（"类里有、prompt 里没有"⇒ 自测失败）。
   ⇒ 这就是委托方那句话的**固化形式**：以后再加指令，要么给依据，要么自测当场红。
3. **顺手补掉两个"半个指令"的真漏洞**：
   - ★ `focus_category` / `ban_focus` **此前只管得住 Goety 聚晶，管不住铁魔法**
     （过滤只写在 `GoetyFocusOps.add(...)` 里）⇒ 玩家下了"只用治疗"，她的铁魔法攻击法术照样入选。
     现在 `IronsSpells.options(...)` 在**枚举阶段**同样过滤（与 goety 同一条纪律），
     并给铁魔法三档粗意图加了类别别名（见 §3.8）。
   - ★ 场景里的法术池**不走指令过滤**，被挡住的那几颗**另行标注**
     （`blocked_by_your_directive`）。理由：若池子也被指令过滤，她下完"只用治疗"之后
     模型看到的池子就只剩治疗 ⇒ 它会以为"她本来就没攻击法术"⇒
     **把自己刚下的指令当成既成事实，越走越偏**。

**★ 仍然缺的（明确登记，不装作没有）**
- 弹药/弹夹余量：TaCZ 的 `GunCommonUtil`（TLM 自带的兼容层）**只暴露** `isGun` / `performGunAttack` / `getGunId`，
  没有弹药查询 ⇒ 场景只能给"有没有枪 + 当前是否正在射击"，给不了"还剩几发"。
- 铁魔法的类别是**三档粗意图**（`ATTACK`/`SUPPORT`/`SUMMON`），细分不了单体/范围/治疗/护盾。

### 3.6 ★★ 半命题指令（第十五轮，委托方提议）

> 委托方原话：「既然 llm 已经可以获得背包里可用物品列表了（**可以吗？**），那可以加一条
> 专门限制使用某种物品（物品 id）的指令。进一步，我们可以开发：**半命题式指令**，
> 即可以填入物品/怪物 id 的指令（比如**强制把仇恨目标局限在某种生物上/某个生物上**）」

**★ 先回答那个括号里的问题：在此之前不能。** 第 15 轮之前 `scene` 里只有
`equipment.has_melee_weapon` / `has_wand` / … 这几个**布尔** —— 模型知道"她有近战武器"，
但**不知道是哪一件**，所以填不出参数。⇒ 本轮加了 `equipment.items`
（`[{id,count,where}]`，**有上限 12 条**，超出部分只报 `equipment.items_truncated` 的件数；
`where` 是 `MAINHAND`/`OFFHAND`/`INVENTORY`/… 的槽位名）。

新增五条指令（都在持续类里）：

| id | 含义 | 参数 | 判据落点 |
|---|---|---|---|
| `only_item` | 只允许用某件物品 | `item`（文本） | 候选集：只留**由它承载**的动作；不依赖物品的动作（通用挥击）**只在主手是它**时保留 |
| `ban_item` | 禁用某件物品 | `item`（文本） | 候选集：排除由它承载的动作；它**正在主手**时连挥击也挡（★ 仅当挡下之后还有得做） |
| `focus_entity` | 只打某一类生物 | `entity`（文本） | **任务层的 `canAttack`**（最上游，见 §3.7） |
| `focus_one` | 只打指定的那一只 | `entity`（UUID） | 同上 |
| `ban_entity` | 这一类不打 | `entity`（文本） | 同上 |

**★ 参数写法（四处都认，判据只有一份）**：
物品 ⇒ 注册名（`minecraft:iron_sword`）/ 简写（`iron_sword`）/ 标签（`#forge:tools/swords`）/
类型名（`SwordItem`、`IGun`）/ 能力名（`maid:weapon`）；
生物 ⇒ 注册名 / 简写 / 实体标签（`#minecraft:raiders`）/ UUID。
空参数 ⇒ **什么都不匹配**（fail-closed：写错不会变成"打所有东西"）。

### 3.7 ★★ 目标筛选为什么落在「任务的 canAttack」（取证结论）

女仆的攻击目标是脑记忆 `ATTACK_TARGET`（她覆写了 `getTarget()` 直读它 ⇒ 两者恒等，
`Mob.target` 字段对她来说是死字段）。而写/清这条记忆的三条路径**都要过
`EntityMaid#canAttack` → 当前任务的 `IAttackTask#canAttack`**：
① 找目标的谓词 `findFirstValidAttackTarget`；② `StartAttacking` 内的 `Mob#canAttack`；
③ `StopAttackingIfTargetInvalid` 的保留条件。
⇒ **一个 `default` 方法覆写，三处同时生效**（`TaskPlayerLikeCombat#canAttack`），
不需要 mixin、不需要事件。

★ 两个必须记住的机制（决定了"看起来不生效"会不会发生）：
- `StartAttacking` 的门是 **`absent(ATTACK_TARGET)`** ⇒ 她**已有目标时根本不会重选**
  —— 这正是"你让她只打 B，她继续打 A"的来源；
- `StopAttackingIfTargetInvalid` 的 `erase()` **是无条件执行的** ⇒ 我们**不必**手动清目标，
  返回 false 之后它下一 tick 自己会清掉，然后 `StartAttacking` 才会去选匹配的那个。

⚠️ **副作用与处置**：`MaidSnapshot.countNearbyEnemies` 用 `maid.canAttack(e)` 数敌人。
如果那条路也带指令，那么"只打僵尸"时**"附近敌人"会悄悄变成"附近僵尸数"**，
同时污染态势偏置与 LLM 看到的 `scene`（最难查的一类错：看起来正常，只是数字含义变了）。
⇒ 拆成两个概念：**"允许打吗"（含指令）** vs **"本来能打吗"（中立事实，`canAttackIgnoringDirectives`）**。

### 3.9 拔刀剑 SA / 幻影剑 取证（第十六轮，委托方问「能不能放」）

> 委托方问：「我想确认一下拔刀剑的 SA 和幻影剑是否能正常放，还是说不行。」
> 「关于拔刀剑动作的问题，你可以和我说一下之所以卡住，是差在哪了吗？」

**取证结论（CFR 反编译源码 + javap + 游戏日志三方对照）**

| 动作 | 状态 | 证据 |
|---|---|---|
| 13 条**斩击**（`combo_a/b/c`、`combo_a_ex`、`rapid_slash`、`upperslash(_jump)`、`rising_star`、`judgement_cut`、`aerial_rave_a/b`、`aerial_cleave`、`maid_native:slashblade_generic_slash`） | ✅ **能放，真伤害** | `SlashBladeExecutors:69-76` → `AttackManager.doSlash:112-140` 生成 `EntitySlashEffect`；该实体自 tick 结算伤害（`:287,:325-339`）。★ 但它是"一次即时斩击"，**不是该招的帧编排**（W41） |
| **SA（`slashblade:slash_art`）** | ✅ **第十六轮已修好**（此前是"触发了但零效果"） | 病灶见下 §「卡住差在哪」；解法 = **自己每 tick 替她推那一帧**。落地：`decision/SlashArtTimeline`（纯逻辑判据 + 自测）· `compat/exec/slashblade/SlashBladeChannel`（驱动器）· `compat/event/SlashBladeTicker`（挂 TLM `MaidTickEvent`） |
| **幻影剑（`slashblade:summoned_sword`）** | ✅ **第十六轮已实现**（此前连执行器都没有 ⇒ 解析期即丢） | 照官方 `SummonedSwordArts:101-145`：刀 **BEWITCHED** + **力量附魔 > 0** + **耀魂 ≥ `SUMMON_SWORD_COST`** ⇒ 扣耀魂 ⇒ 朝目标（或视线 40 格命中点）生成 `EntityAbstractSummonedSword`。★ 自身伤害是 `hurt(src, (int)getDamage())`（**不乘攻击力**，1.9.65 javap 实测）⇒ 与玩家按同一个键的效果**完全一致**；★ **刻意不抄参考实现的"补一刀"**（那是叠加：连段类会变约 1.85 倍，而且伤害变成两套账） |

**★「卡住」差在哪（逐条，这才是问题的答案）**
1. **缺逐帧驱动器**（根本原因）：原版的动作靠 `Item#inventoryTick` 每 tick 推进
   （`resolvCurrentComboState` + `ComboState.tickAction`）；
2. **Mob 的持有物不跑 `inventoryTick`**：javap 全量扫描 1.20.1+Forge 的 joined-srg jar ——
   `ItemStack.inventoryTick` 全库唯一调用者是**玩家的 `Inventory`**，
   TLM 与拔刀剑的 jar 都不引用它；而 `ItemSlashBlade` 还额外要求 `isSelected && 主手` ⇒ **双重门**；
3. combo 前置由**输入路由**（`ComboCommands`/`progressCombo`）推进，我们绕开 ⇒ `comboSeq` 卡住；
4. 动画事件广播**硬门控 `ServerPlayer`**（`BladeMotionEventBroadcaster:38-40`），
   玩家姿态靠 `playeranimator` ⇒ **女仆连"刀动"都没有**；
5. SSA（`SuperSlashArts#releaseSSA(ServerPlayer)`）、瞬步、格挡挂 `InputCommandEvent`/`IInputState`
   ⇒ 纯服务端做不到。

**★★ 最终解法（第十六轮已实施）—— 参考实现给出的最后一块拼图**
不是"复刻 `tickAction` 那两行"，也不是"绕开状态机自己算伤害"，而是
**把物品自己那一帧推一下**（`SlashBladeProvider` 就是这么做的）：
```java
// 起手
maid.startUsingItem(MAIN_HAND);                       // 蓄力
// 到 getFullChargeTicks + SlashArts.getJustReceptionSpan(maid)/2 时：
stack.releaseUsing(level, maid, timeLeft);            // ← 松手那一帧的结算
maid.releaseUsingItem();
// 连段期间，每 tick：
stack.getItem().inventoryTick(stack, level, maid, 0, /*isSelected*/ true);
```
★ 这一行 `inventoryTick` 内部就会做 `resolvCurrentComboState` + `ComboState#tickAction`
（伤害/位移/特效都在里面），并且顺带把物品自己的记账也做了 ——
**我们不需要碰拔刀剑的内部，只需要别让它"没人推"**。
- 判据落在纯逻辑层 `decision/SlashArtTimeline`（松手时刻 / 松手成功 / **每 tick 都要推** / 4 种收尾原因），
  由自测套件第 22 步钉住；
- 驱动器挂在 **TLM 的 `MaidTickEvent`** 上（`compat/event/SlashBladeTicker`）——
  ★ 必须独立于我们的行为层（行为层只在"有仇恨目标"时跑，而刀技是跨 tick 的动作）；
- 超预算（200 tick）强制收尾 ⇒ 不许她无限举着刀。

**★ 仍然做不到的**
- **女仆的拔刀剑挥刀动画**：`BladeMotionEventBroadcaster` 硬门控 `ServerPlayer`，
  参考实现也没做（全 jar 0 命中）⇒ 只有实体特效（刀光/幻影剑/次元斩），没有动作动画；
  要做只能自己发网络包 + 自己写渲染 —— 纯服务端模组做不到。
- **不需要客户端**：实体类特效（`EntitySlashEffect`/`EntityJudgementCut`/`EntityAbstractSummonedSword`/`EntityDrive`
  都是服务端生成、客户端渲染）⇒ 幻影剑与次元斩的**视觉**已经出来了（见 `SlashBladeExecutors#summonSword`）。

⚠️ **与既有结论相矛盾的证据**：docs/12 的 W41 曾写"`inventoryTick` 对 Mob 是否成立**尚未定论**"
⇒ 现在**确定为不成立**（上面的 javap 证据）。

### 3.11 ★★ 对话上下文：让她自己"知道"（第十六轮下半）

> 委托方实测：「我观察到了 SA，但女仆的对话 llm 似乎**不知道自己有拔刀剑能释放 SA**，
> 乃至于**什么 SA**；同时她**也不知道自己能否释放幻影剑**。」

**★ 根因是机制，不是提示词**：我们此前只把战斗场景喂给**指挥 LLM**（`llm.*`，见 §3.1）。
TLM 的**对话 LLM** 拿到的是 TLM 自己注册的上下文 —— 其中 `equipment` 类**只报物品名与数量**，
它**不认识"这是不是妖刀、配了什么 SA、能不能放幻影剑"**。
⇒ 用 TLM 的公开 API `ILittleMaid#registerAIMaidContext(GameContextRegister)` 注册我们自己的（`FlpMaidContexts`）：

| 类别 | 类型 | 内容 |
|---|---|---|
| `flp_combat_now` | ★ **prompt 类**（每回合自动注入） | `flp_blade`（刀名/SA 是哪种/耀魂/此刻在放什么）· `flp_phantom_sword`（**能否放 + 缺哪一条**）· `flp_directives`（当前生效的指令） |
| `flp_combat_detail` | 工具类（按需查） | `flp_action_ids`（**她能做的全部动作 id**）· `flp_items`（全量物品）· `flp_directive_ids`（指令 id 与中文名） |

**★ 关于"实时性"（委托方的第二个观察）**：TLM 的 `getValue(maid)` 是**每次取值时现算**的
⇒ 数据本身永远实时。区别只在**什么时候去取**：
- `isPromptContext=false`（TLM 自己的 `equipment` 就是这类）⇒ **只有模型主动调 `query_game_context` 才取**；
  调过一次之后如果它"记着上次的结果"，那是**模型自己的记忆过期**，不是数据过期；
- `isPromptContext=true` ⇒ 每回合都进 prompt —— 适合"每个回合都必须知道"的少量关键事实。
⇒ 所以**两类都注册**：关键的少量事实走 prompt 类，长清单走工具类。

### 3.12 ★★ 动作白名单 `only_actions`（"接下来几秒只做这些动作"）

> 委托方：「（动作有 id 吗？有的话，可以进一步写：**接下来几秒只进行这些动作**）」

**★ 有 id**：清单里每条动作都有自己的 id（`slashblade:slash_art`、`goety:cast_focus`…），
由 `flp_action_ids` 上下文报给模型。
`only_actions{actions, seconds}`：`actions` = 逗号分隔的 id 白名单，`seconds` = 持续秒数
（★ **接在已有的 TTL 机制上** —— 时长是 TTL 的属性，不是动作的属性，
解释这个约定的**唯一出口**是 `DirectiveSpec#ttlTicksFrom`）。
★ 与其它"只允许 X"的指令同一条护栏：**白名单一个都匹配不上 ⇒ 这条自动失效**（她照常打），
绝不把候选集清空。

### 3.13 ★★★ 死锁 bug：`only_item` 会把候选集清空（委托方报"完全不奏效"）

> 委托方：「半命题的指令似乎有 bug，相关测试似乎**完全不奏效**。」

**核实：确实是真 bug，而且是静默的。** 上一版是"逐个动作问 `itemGate`"，于是：
<pre>
她下 only_item(minecraft:iron_sword)，剑在【背包】里、主手是法杖
  ⇒ 由剑承载的动作：一条都没有（清单里没有"由剑承载"的动作）
  ⇒ 不依赖物品的动作：主手不是剑 ⇒ 全被挡
  ⇒ 候选集 = 空 ⇒ 没有动作可选 ⇒ 她【站着不动】
  ⇒ 换手前置只在"选中动作"后才跑 ⇒ 剑永远到不了手上 ⇒ 死锁
</pre>
**★ 我为什么漏了它**：我给「禁用 X」那一侧写了"挡完没得做就回退"的护栏
（`ban_item` 的两趟），却**没给对称的「只用 X」那一侧写** ——
而后者才是**必然**会清空的那一侧（"只用 X"本来就要求 X 在手上，而 X 往往在背包里）。
★ 更糟的是**测试没抓住**：上一版的自测只测了 `itemGate` 这个**谓词**，
**没有测"整表会不会被清空"** —— 谓词全对，组合起来是死锁。

**修法（`DirectiveFilter.itemPlan`，纯逻辑 + 断言）**：
1. 过滤结果为空 ⇒ **一律回退成不过滤**（她照常行动），并给出可读原因；
2. 同时返回 `ItemStatus`：`NEED_HAND`（她身上有、但不在手上）⇒ 调用方**主动把东西换到主手**
   （`equipToHand`，与盾的姿态伺服同一套机制），下一 tick 过滤自然生效；
   `ABSENT`（她身上根本没有）⇒ 明确告诉玩家"这条暂时不生效"；
3. ★ **不变量：`allowed` 永不为空**（除非输入本来就空）—— 由自测断言。

### 3.15 ★★ 击杀账本 · 惰上下文 · 备忘录（第十六轮下半）

> 委托方：「把**上一次对话到这次对话期间杀了什么，有多少**也纳入女仆 llm 的知晓内容。」
> 「**惰上下文**：……这次的是直接的输入，但此前的都作为可调用但不主动调用的惰上下文。」
> 「**备忘录**：允许女仆对话 llm 自行编辑备忘录……通过指令写入/删去等操作。」

**★ 为什么不用 TLM 自己的击杀记录（取证）**：`MaidKillRecordManager` 的字段
（`totalCount/slimeCount/…`）**全是 private 且无 getter**；而且 TLM 自己的归因只有一句
`source.getEntity() instanceof EntityMaid` ⇒ **她的仆从/刀气/法术杀的它一个都不记**。
⇒ 我们自己听 `LivingDeathEvent`（★ 必须挡客户端，否则计数翻倍 —— `HurtListener` 的先例），
按**归因阶梯**判：她就是凶手 → 她的仆从（`GoetyServantOps.myServants` 名单）→
她名下的投射物/实体（顺 `Projectile#getOwner` / `OwnableEntity#getOwner` 找，**一命中女仆就停**，
绝不继续上溯 —— 女仆的 `getOwner()` 返回的是**玩家**）。

**★★「一次对话」的边界（取证结论）**：TLM 的 `UserPromptContexts#appendContext` 在
`MaidAIChatManager#normalChat` 里被调用，**每条玩家消息恰好一次**，它会遍历所有
**prompt 类**上下文并调 `getValue(maid)` ⇒ **我们的 prompt 上下文的 `getValue` 被调用那一刻
就是"上次对话 → 这次对话"的分界**：在里面结算本窗口击杀并翻页即可
（零 tick 延迟 —— tick 轮询做不到，因为 `addContext` 与发 HTTP 是同一 tick 同步完成的）。

**★ 惰上下文的机制保证（委托方的猜测成立）**：全树只有两处调 `GameContextRegister#getContext` ——
`query_game_context` 工具（只接受 `isPromptContext==false` 的类别）与
`UserPromptContexts#appendContext`（只遍历 prompt 类）⇒
**`false` 的类别只在模型主动来问时取用，不进任何缓存的 prompt**。
⇒ 注册形态：

| 类别 | 内容 |
|---|---|
| prompt（每回合自动进） | `flp_blade`（刀/SA/耀魂）· `flp_phantom_sword`（**能否放 + 缺哪条**）· `flp_directives` · **`flp_kills_now`（本窗口击杀，并在此翻页）** · **`flp_memo`** |
| 工具（按需、惰） | `flp_action_ids` · `flp_items` · `flp_directive_ids` · **`flp_kills_past`（以前各窗口）** · **`flp_items_past`（上次及以前的物品清单）** · `flp_memo_full` |

**★ 备忘录**：新工具 `flp_memo`（read/add/remove/clear），存在
`maid.getPersistentData()`（NBT 键 `flp:memo`，**随存档保存**）。
★ 取舍：TLM 的 `TaskData` 更"正规"（有 codec 与同步），但它的 codec **必须编码成 `CompoundTag`**
（否则 CCE），且存档重载后会无条件推同步包给客户端；备忘录是我们自己的私事，用 NBT 更省事也够稳。

### 3.16 ★ 立即切换物品（`switch_item`，委托方要求）

`switch_item{item, interrupt=1}`：把指定物品换到主手；**默认先中断当前动作**
（否则换手会与在途的引导/枪械/刀技打架 —— 我们自己的守卫就会挡住它）；找不到就**明确说没有**。

### 3.17 ★★ 向量数值调整（委托方第 2 条）—— 取证与本次改动

> 委托方：「如何调整向量数值，需要**在不使得向量因为太短而方向无意义**的情况下，
> 提升女仆的**攻击性和连贯度**（比如**奖励连段式的攻击**）」

**取证结论（确定性回放，见 `_scratch/analysis_findings.md`）**：
1. 判据是**加权欧氏、无归一化** ⇒ 长度**有意义**，但不是越长越好：
   某轴 u 乘 k 使距离变小 ⟺ `u < 2p/(k+1)`；`p[SINGLE]` 上限 = 1.0 ⇒ 已 ≥0.667 的动作再抬该分量**只会更远**；
2. ★★ **"不主动、串不起来"的机械原因**：真实回放里 **p[MITIG] 中位 1.96、91% 顶到上限 2.0**
   （该轴权重 2.0、λ 仅 0.01、没有释放通路），而 **27/53 条动作带负 `MITIGATION_SURVIVAL`**
   ⇒ **攻击在喂养防守需求** ⇒ 距离被它主导，`disengage` 成了 357/400 拍的最近邻第一名；
3. 平局率 9%（余量仅 ~12%）：整体缩放 ×0.6 时第一名/第二名距离差 0.0188 **< τ=0.05（方向失效）**；
4. `cooldown.scale` 只改出手密度、**且毁掉连贯度**（合法连段对 12→5、平局率 9%→41%）
   ⇒ **调节奏之前必须先有连段奖励**。

**本次改动（数据，`catalog/vectors.json`）**：
- **B：27 条动作的负 `MITIGATION_SURVIVAL` ×0.5**（保留符号）—— 断掉"攻击喂养防守轴"那条路；
- **A：9 条【纯单体】攻击的 `SINGLE_DAMAGE` → 0.85**（★ 只动 `AREA_DAMAGE < 0.15` 的，
  带范围成分的一律不碰 —— 见下面的教训）；
- ★ **不改**那 4 条模长为 0 的 goety 管理动作（它们走维护通道，抬它们等于让它们来抢攻击位）。

**下一步（已设计、未实施）—— 「奖励连段」**：
`SpringConfig` 加 `comboBonus`（默认 0~0.5，进 `TuningBus` 可调可复原）·
`ActionSelector.select` 加 `lastChosenId` 与"声明后继"表 · `DecisionCycle` 把**已有**的
`lastChosenId` 传进去 · `ActionSpec`/`CatalogLoader` 补读清单里**早就写好但没人读**的 `comboDeps`
（`combo_b_finish←combo_b`、`rising_star←rapid_slash`、`upperslash_jump←upperslash`）。
★ 硬约束：**`comboBonus` 必须 > τ=0.05**，否则被平局带 + LRU 吃掉（实测 0.03 零效果、0.12 有效）。

### 3.14 ★★ 参考实现取证：万法皆通（`touhou_little_maid_spell` 1.9.0）怎么做

★ 它**就装在测试实例里**（`versions\测试\mods\[车万女仆：万法皆通] touhou_little_maid_spell-1.9.0-…-all.jar`，
bootstrap jar，真类在嵌套 `META-INF/maidspell/maidspell-mod.jar`）。它的 slashblade 包只有 6 个类。

| 问题 | 它怎么做（证据） | 我们该怎么用 |
|---|---|---|
| **SA 怎么触发** | **老老实实走拔刀剑的状态机，但是由它自己"替她推"**：`initiateCasting` 先 `startUsingItem` 蓄力，`targetUseTime = state.getFullChargeTicks(maid) + SlashArts.getJustReceptionSpan(maid)/2`；到点后 `releaseUsingItem()`（`SlashBladeProvider:139-150,174-187,237`）。连段则是**临时往 `ItemSlashBlade.INPUT_STATE` 塞 `InputCommand.L_CLICK`+`ON_GROUND`，调 `state.progressCombo(maid)`，`finally` 还原**（`:325-366`）。★ 全 jar **一次都没调** `ComboState.tickAction` / `resolveCurrentComboState` | ★★ **这正是我们 SA 零效果的解法**：把 SA 从"调一次 `doChargeAction`"改成**逐 tick 驱动物品路径**（`use()` / `releaseUsingItem`）+ 连段用"塞输入"技巧。**不要**去自己调 `tickAction` |
| **逐帧挂在哪** | `MaidSpellEventHandler.onMaidTick(MaidTickEvent)` → `SpellBookManager.tick(maid)` → provider `processContinuousCasting`（每 tick） | ★ **TLM 自己的 `MaidTickEvent`** 比我们的 `ServerTickEvent` 更合身（跟着她的任务/在场状态走） |
| **伤害谁结算** | ★ **实体本来就打真伤害**：`EntitySlashEffect` 每 2 tick 调 `AttackManager.areaAttack(...,alreadyHits)` → `AttackHelper.attack`，**女仆走的是"非玩家"分支**，伤害 = 攻击力 × comboRatio × scale（1.9.65 javap 实测：`areaAttack` + `alreadyHits` 都在）。**它另外还补一刀**：命中确认后 `invulnerableTime=0; target.hurt(damageSources().mobAttack(maid), 攻击力×0.85); invulnerableTime=0`（`:46-82`）⇒ **是在实体伤害之上叠加（连段类约 1.85 倍）** | ★★ **别抄这个补刀**（我们已有自己的结算 ⇒ 会变三倍）。只抄它的**门闩思想**：用 `getAlreadyHits()/getHitEntity()` 确认"这刀真蹭到了" |
| **幻影剑** | **它从不 new `EntityAbstractSummonedSword`、也从不碰 `slashblade:summoned_sword`**。它生成的是 **`EntityDrive`（继承 `EntityAbstractSummonedSword`）**，来自 `Drive.doSlash`/`WaveEdge.doSlash` 那 6 招 | ★ 基础幻影剑自带伤害是 `hurt(src, (int)getDamage() * scale)`，**不乘攻击力**（1.9.65 javap：`getDamage:()D` + `hurt`）⇒ 要做"能打死人"的幻影剑，**要么给足 `setDamage`，要么让它走 `EntityDrive`/`EntitySlashEffect` 这类按攻击力结算的实体** |
| **前置/消耗** | 它那条路**不查耀魂、不查附魔/BEWITCHED**（只在 `isBroken()||isSealed()` 时不出手） | 若照官方语义做幻影剑：`SummonedSwordArts:101-122` 要求 **BEWITCHED + 力量附魔 > 0 + 耀魂 ≥ `SUMMON_SWORD_COST`** |
| **动画** | 全 jar 搜 `BladeMotionEventBroadcaster` / `playeranimator` = **0 命中** —— **它压根没做动画**，女仆只有原版 `swing` + 实体特效 | ★ **"女仆的拔刀剑挥刀动画"连参考实现都没有**：`BladeMotionEventBroadcaster` 硬门控 `ServerPlayer`，要做只能自己发网络包 + 自己写渲染 ⇒ **当作"不做"处理，并明说做不到** |

### 3.8 类别别名（铁魔法 ⇒ 八档细类别）

`focus_category` 的序号是按 Goety 的八档定义的；铁魔法只有三档意图，
故 `DirectiveFilter.categoryIndex` 加了别名，**归并方向永远偏宽松**
（宁可多放行一个法术，也不要因为分类不准把她整个封死 —— docs/13 第 22/25 条）：

| 铁魔法意图 | 归入 | 后果 |
|---|---|---|
| `SUMMON` | `SUMMON`(2) | 精确，无歧义 |
| `ATTACK` | `ATTACK_SINGLE`(0) | `focus_category=0` 放行它们；`=1`（仅范围）**不**选中它们（不冒充范围系） |
| `SUPPORT` | `HEALING`(5) | 要求"仅治疗"时护盾/buff 也会放行（宽松一侧） |

---

## 4. 第三段：车万女仆自己的对话 LLM → 单向下令（委托方提议）

委托方设想：
> 「玩家和女仆对话 → 女仆根据实际情况下令 → 指挥 llm 决定具体指令操作 → 产生具体影响。」

**加工成可实现的三段式**（关键是"单向"）：
<pre>
① 玩家 ↔ 女仆【对话】（TLM 自己的对话 LLM，我们完全不碰）
        │  女仆的对话层调用一个【我们注册的工具/tool】
        ▼
② 我们收到一句"意图"（自然语言，例如"用魔法打，别贴太近"）
        │  交给【指令 LLM】翻译成指令表里的条目
        ▼
③ DirectiveBus 落地 ⇒ 产生具体影响（候选集/步法/…）
</pre>

★ **为什么必须是"它调用我们"**：我们不碰 TLM 的对话实现（不耦合、不打架），
只在它暴露的**工具注册点**上加一个工具；TLM 那边更新也不影响我们（工具签名是最小面）。

### 4.1 取证结果（第 14 轮，`javap` 实测 TLM 1.5.3 + CFR 反编译对照）

| 事实 | 值 |
|---|---|
| 注册入口 | `ILittleMaid.registerAITool(ToolRegister)`（默认方法，我们的 `LittleMaidCompat` 覆写它） |
| 工具基接口 | `ai.agent.tool.ITool<T>`：`id()` · `summary(EntityMaid)` · `parameters(ObjectParameter, EntityMaid)` · `codec()` · `onCall(String, T, LLMCallback)` · `onCallAsync(...)`（默认） · `invocationSummary(T)` |
| 注册方法 | `ToolRegister.register(ITool<?>)` |
| 参数构造 | `ObjectParameter.create()` + `addProperties(name, Parameter[, required])`；有 `String/Number/Integer/Bool/Array/Null` 六种；★ `setDescription` / `addEnumValues` 都能用 |
| 入参解析 | TLM 用 `tool.codec().parse(JsonOps.INSTANCE, json)`；**解析失败整条调用就失败** ⇒ ★ 可选字段一律 `optionalFieldOf(name, 默认值)` |
| 执行线程 | ★ `LLMCallback` 第 194 行用 `MinecraftServer#execute` 派发工具批次 ⇒ `onCall` **在服务器主线程**跑（`addToolResult` 会断言这一点） |
| 结果回话 | `callback.addToolResult(文本, toolCallId)`（必须是可读的一句，模型靠它决定下一步） |
| 聊天气泡摘要 | 覆写 `invocationSummary(T)`（玩家在气泡里看到的那行） |
| 技能（skill） | 文件 `skill.md`，`---` YAML front matter（`name` / `description` / 可选 `metadata.tlm-type: knowledge`）+ 正文；**数据包位置必须在 `data/touhou_little_maid/skills/<名字>/skill.md`**（`SkillsDataReloadListener` 只认 `touhou_little_maid` 命名空间，名字限 `[a-z0-9\-_]+`），也可放 `config/touhou_little_maid/skills/` |
| 技能怎么被用 | 模型看到 `<available_skills>`（只有名字与 description）⇒ 调 `use_skill` 才拿到正文（★ 所以 description 要写"什么时候该看这篇"） |

### 4.2 我们落地的东西（M5）

- `compat/ai/FlpDirectiveTool`：工具 id `flp_directive`，**四个字段**：
  `action`（issue/cancel/cancel_all/list）· `directive`（★ **枚举值 = 全部 21 条指令 id**，
  照抄 TLM 自己的 `UseSkillTool` 做法 ⇒ **模型在 schema 层面就编不出不存在的 id**）·
  `value`（那一个数字）· `text`（那一个文本，目前只有 `ban_focus` 用）。
  ★ 参数翻译交给纯逻辑层 `DirectiveSpec.translate`，而"四字段够不够表达每条指令"**由自测逐条断言**。
- `data/touhou_little_maid/skills/flp-combat-command/skill.md`：随包发布的技能书
  —— 指令表、参数范围、**"什么时候用哪个"对照表**、★ **"先看她有没有那件东西再下指令"**、以及回话方式。
- 开关：`llm.toolDirectives`（默认开）。关掉时工具仍在（模型看得见），
  但调用会被**明确拒绝并说明原因**（不是装作没听见）。
- ★ 边界不变：**对话模型只能"下指令"，不能指定她这一秒做什么动作** —— 动作仍由决策层选。

---

## 5. 里程碑（按本文执行）

| 里程碑 | 内容 | 验收（可离线/可实机） | 进度 |
|---|---|---|---|
| **M1 指令骨架** | `Directive`（record：id/kind/params/ttl/source）+ `DirectiveBus`（每女仆一份：持续集合 + 瞬间队列 + TTL + 冲突规则）；**纯逻辑层**，可离线断言 | `DirectiveSelfTest`：下达/取消/过期/未知 id 丢弃/cancel 先于 issue/TTL 上限 | ✅ 已完成 | ★ 落地物：`decision/DirectiveSpec`（21 条指令表 + 自动生成的 LLM 提示词片段 + `needs`/`translate`）· `decision/DirectiveBus`（下达/取消/TTL/互斥/瞬间队列）· `DirectiveSelfTest`（**107 项断言**，自测套件第 21 步） |
| **M2 强制执行** | 五个落点：① `CarrierResolver` 之后加一层"指令过滤"（新 `DropReason.DIRECTIVE`）② `ensureCarrierInHand` 支持 `no_item_switch` ③ 步法约束（`keep_distance`/`close_in`/`hold_position`/`no_retreat`）④ 聚晶/法术挑选过滤 ⑤ 瞬间指令队列（在 `PlayerLikeCombat.tick` 开头顶端消费） | `GameLoopSelfTest`：每条指令至少有 1 条断言（例如 `magic_only` ⇒ 候选集里没有近战）；实机：`/flp directive` 下达后观感成立 | ✅ 已完成 | ★ 落地物：`decision/DirectiveFilter`（分类 + 候选集过滤 + 聚晶过滤 + 步法约束）；五个落点：① `PlayerLikeCombat#applyDirectives` ② `ActionExecutors#handAlreadyFits`（no_item_switch）③ `applyDirectiveGait` + `ActionExecutors#gait` 锚点 ④ `GoetyFocusOps#add` ⑤ `Phase.MAGAZINE` 受 conserve_ammo 约束 + 瞬间队列由 `DirectiveHolder#tick` 消费 |
| **M3 命令与可见性** | `/flp directive list` · `/flp directive set <女仆> <id> [k=v…]` · `/flp directive clear [女仆] [id]`；`/flp why` 与 `whyfull` 显示"被指令挡下"的原因 | 手工验证 + 文档章节 | ✅ 已完成 | ★ 落地物：`/flp directive [list]` · `set <女仆> <id> [值]` · `set <女仆> <id> text <文本>` · `clear <女仆> [id|all]`；被指令挡下的动作写进 `/flp whyfull` 的近期结果环 |
| **M4 LLM 接入** | ① 指令表自动进 prompt（`directives_available` + `directives_active`）② 输出解析（复用容错 JSON）③ `applyMode` 默认 `observe` | `ThinkSelfTest`：prompt 含指令表；解析：未知 id 丢弃/cancel 先应用；实机：`/flp tune llm now` 后 `/flp directive list` 能看到它的决定 | ✅ 已完成 | ★ 落地物：`LlmAdvisor`（**指令优先**的默认提示词 + `parseOrders` 白名单/钳制 + `systemPrompt()` 自动追加指令表）· `LlmBridge`（`pendingOrders` + **cancel 先于 issue** + `State`）· `/flp tune llm [now]`。★ 第 15 轮又补上 `scene`（§3.4）与 `base_on` |
| **M5 女仆对话单向下令** | 取证 TLM 的 tool 注册 API ⇒ 注册一个"下达战斗指令"工具 ⇒ 对话层下令 → 落地 | 实机：对女仆说"用魔法打，别贴太近" ⇒ `/flp directive list` 出现 `magic_only` + `keep_distance` | ✅ 已完成 | ★ 取证见 §4.1；落地物：`compat/ai/FlpDirectiveTool`（`flp_directive`，`directive` 是**枚举参数**）· `LittleMaidCompat#registerAITool` · 随包技能书 `data/touhou_little_maid/skills/flp-combat-command/skill.md` · 开关 `llm.toolDirectives` · 自测：`DirectiveSpec.translate` 逐条断言（7 项） |
| **M6 打磨** | 指令冲突规则（同一维度互斥：`magic_only` vs `melee_only`）、过期提示、脱战自动清、（可选）玩家语音/聊天命令 | 自测 + 实机 | ✅ 已完成 |

---

## 6. 风险与对策（每条都有先例）

| 风险 | 对策 |
|---|---|
| 指令把候选集清空 ⇒ 她**什么都不做** | ★ 空候选集必须走**回退动作**（`docs/08 §8.2` 的"回退必须永远存在"）+ 日志写明"是指令挡下了全部候选" |
| 持续指令忘了取消 ⇒ 永久生效 | TTL（默认 120 s / 上限 600 s）+ 脱战自动清 + `/flp directive clear` |
| LLM 下达互相矛盾的指令 | 同一"维度"互斥表（魔法/近战/远程三选一；距离上下界取交集并钳制） |
| LLM 编造不存在的指令 id | 白名单 + **丢弃并登记**（与旋钮补丁同一条纪律） |
| 指令与旋钮打架（例如 `conserve_ammo` vs `gun.magazineShots`） | **指令优先**（越具体的层优先），并在 `/flp why` 里显示"谁在起作用" |
| 对话层的意图 → 指令翻译错 | 让 LLM 回一句"我理解成 X" ⇒ 女仆转述给玩家（可纠正） |

---

## 7. 与其他文档的关系

| 想知道 | 去哪 |
|---|---|
| 为什么"评分类给 JEV、指令类给 LLM" | [11 §5.1c](11-感知层与思维层设计.md) · 本文 §3.3 |
| 决策层/候选集/丢弃原因 | [09](09-动作空间与评分体系.md) §4 · `/flp whyfull` |
| 为什么"永久卡死"是本项目最怕的事 | [13 §22/25/26/53](13-更正记录与教训.md) |
| 下一轮实际做了什么 | [12 §0](12-项目参考.md)（每轮追加一节） |

---

## ★ 3.18 第十七轮：指令独占、处死仆从（Goety + 铁魔法）、枪械对账

委托方本轮交了 7 条（详见 [13](13-更正记录与教训.md) §1 的第 70–74 条），落地情况：

| # | 委托方原话 | 结论 / 落点 |
|---|---|---|
| 1 | 「指定只使用**指令后，女仆仍然可以使用做出动作」 | **真 bug 且已修**：`defaultActions()` 读的是**未过滤**的 `c.mainResolution().candidates()` ⇒ 弹簧死区里指令被绕过。现在读 `mainCandidates(c)`。★ 另查出一条**配置**原因：实例 `[llm] applyMode = "observe"` ⇒ **动态指挥 LLM** 那条路的指令只记日志不执行（女仆对话工具那条路不受它影响）—— 已把实例改成 `apply` |
| 2 | 「`only_item` 可以填不止一个参数吗」 | **可以**（本轮新增）：逗号分隔、命中任一件即可（`ItemRef.matches` 按 `[,，;；\s]+` 拆分） |
| 3 | 「枪械部分 bug 挺多，可能是名单串了」 | **四条必错全部复现并修**：① 卓越前线**没有载体规则**（新增 `superbwarfare:gun`）② `maid_native:gun_shot` 与 `tacz:shoot` **重复登记**（`maid:gun`/`tacz:gun` 判据相同 ⇒ 一把 TaCZ 枪两个开火 id）⇒ 按**枪包**拆开 ③ `pillagersgun:gun` 的 reason **串名**（写成卓越前线）④ `DirectiveFilter` 分类表**注释与实现不一致**（最长前缀 vs 第一条命中）+ `tacz:fire_mode` 这条 id 根本不存在。另修：非 TaCZ 分支不可达、`cooldownTicks` 未声明、`isMainHandReady` 死代码、射速未除 `MAID_GUN_ATTACK_SPEED` |
| 4 | 「潜行施法则杀死它们所有…把这处死仆从**主动 ban 了**，仅有上层指令可以运行」 | ★ 两个环：`GoetyChannel#tick` 只"清"蹲姿**不复核**（而 `Spell#isShifting` 每次现算，TLM 的 `MaidClimbTask` 每 tick 重写 flag）；`GoetyExecutors` 判定异常时 **fail-open**。⇒ 现在**每 tick + 派发 `SpellResult` 前两道闸**（用**法术自己**的 `isShifting` 复核，判不出来一律 fail-closed）；那条动作改成 **`role=directive`**（永不进候选，只有上层瞬间指令能触发），并由 `GameLoopSelfTest §6e` 钉住 |
| 5 | 「为处死所有仆从的指令添加一个**铁魔法**的部分（现在有吗）」 | **此前没有**（只处理 Goety 的 `IOwned`）。新增 `compat/exec/irons/IronsServantOps`：按 `IMagicSummon#getSummoner()`/`OwnableEntity#getOwner()` 认归属，走官方 `onUnSummon()`（`javap` 取证：粒子 + `setRemovalReason(DISCARDED)`） |
| 6 | 「当前的处死所有仆从指令**无法运作**」 | 两个原因：① 它调的 `dismissTemporaryServants` 里有 `!isLimitedLife() ⇒ continue` ⇒ **只清限时仆从**；② 只枚举 Goety 一家。⇒ 新增 `dismissAllServants`（不看是否限时）+ 铁魔法那一半 |
| 7 | 「提示词工程上保证：**做不到的事就反馈**」 | **两半**：提示词那半（skill.md 新增「五点五」节）+ **运行时那半**（`DirectiveHolder#feasibility`：下达回话里直接附"她名下一个仆从都没有 ⇒ 这条指令不会有任何效果"这类现状） |

★ 纪律（[13](13-更正记录与教训.md) §2 的 AM/AO/AP）：**清 ≠ 确认清了**（判据紧贴使用点、fail-closed）；**复用玩家侧入口前先比作用域**；**同一个判据不得写成两条规则**。

---

## ★ 3.19 第十七轮续：「不管让她使用什么，都用的火箭筒」

委托方第二天补的一句实测，查了**四条链**（前三条我此前从没测过）：

| # | 链 | 现象 | 修法 |
|---|---|---|---|
| 1 | **查询串匹配太严** | `ItemQuery` 之前的判据（`ItemRef#matchesOne`）要求**逐字符相等**：`AK47` 的大小写、漏 `tacz:`、写 `rpg` 而真实 id 是 `tacz:rpg7` ⇒ **一个都不匹配** | 新增纯逻辑 `carrier/ItemQuery`：**分级匹配** FULL（注册名全名）/ EXACT（标签·类型名·能力）/ BARE（简写、大小写）/ LOOSE（子串，≥3 字符）+ **含糊显式化**；`ItemRef.matches` 改为**转发**（一处定义，杜绝两处口径） |
| 2 | **不匹配 ⇒ 静默回退** | `itemPlan` 的护栏是"过滤为空 ⇒ 回退成不过滤"（第 68 条加的，防的是她站着不动）⇒ 她**继续用手上那把枪**（火箭筒） | 护栏保留（不许清空候选集），但**把真相说出去**（见 3） |
| 3 | **没人告诉她 id 错了** | `issue()` 回话只说"已下达" ⇒ 模型以为生效了 | `DirectiveHolder#itemFeedback`：下达当下回「她那件是 `tacz:rpg7`（在 INVENTORY，命中强度 LOOSE）」/「**她身上没有**能匹配「rifle」的东西 ⇒ 这条指令不会有任何效果。她身上有（前 8 件）：…」/ 含糊时「还匹配到 …⇒ 建议写全 id」 |
| 4 | **她可能看不到枪的 id** | `equipment.items` 只列 12 条且**按槽位顺序** ⇒ 背包一满，枪被截断，模型只能猜 | 物品清单改成**战斗相关优先**（枪 0 > 法杖·法术书 1 > 拔刀剑 2 > 近战 3 > 盾 4 > 其它 5），同优先级保持"手上的优先"；★ 并修正 `equipment.items_truncated` 的口径（它原来算的是**重复件数**，不是"没列出来的种类数"） |

★ 另外修掉一处**命令**上的门槛：`/flp directive set <女仆> only_item tacz:ak47`
以前会因为"第三个参数只吃数字"**直接语法报错**（必须多写 `text` 字），
而观感与"指令没生效"一模一样 ⇒ 现在新增"任意值"分支，且四种写法**语义等价**。

**回答委托方那句「是 LLM 认知问题还是指令问题」**：**两边都有，而且它们叠在一起才出现这个现象** ——
认知侧：她（以及模型）看不到/看不清枪的 id（截断 + 只能靠猜）；指令侧：猜错时**静默回退**到"照常打"，
且回话不告诉任何人错了。⇒ 现在的口径是：**放宽能放宽的，说不清的说清，做不到的照实说**。

---

## ★ 3.20 第十七轮续（二）：**瞬间指令的执行点挂错了地方**

委托方交来 5 条，其中第 3/4/5 条指向同一个根因，第 5 条（「我怀疑瞬时指令有 bug」）**猜对了**：

| # | 委托方原话 | 结论 |
|---|---|---|
| 1 | 「指定只使用/拿出来没有实际生效」 | ① 匹配太严 + 静默回退 + 无反馈（上一轮已修）；② **换手步骤挂在"只在有攻击目标时运行"的行为里** ⇒ 非战斗时"拿出来"永不发生 ⇒ 搬进 `DirectiveHolder#itemServo`（挂 `MaidTickEvent`） |
| 2 | 女仆能获得**可选聚晶名称** | 新增惰上下文 `flp_spells`（聚晶：中文名 + 类别 + 来源 + 被哪条指令挡着；法术书：中文名 + 等级 + 意图 + 法术 id） |
| 2.1 | 同上的**铁魔法法术名称** | 同上（一个上下文里两半都列） |
| 2（WIP） | 进一步要**聚晶的文字介绍** | 新增惰上下文 `flp_spell_descriptions`：Goety 取 `item.<focus>.info`、铁魔法取 `spell.<id>.guide`（lang 键，服务端可解析；★ 有上限、键不存在就跳过；⚠️ 语言取服务端当前语言） |
| 3 | 「统计有什么召唤物起效、**实际清理完全不起效**」 | ① 结果没有出口（统计在下达那一刻、清理在执行那一刻，后者只写日志）⇒ 新增 `Entry#lastInstantResult`，`/flp directive list` 直接显示；② `dismissAllServants` **第一只抛异常就整批中断** ⇒ 逐只 try/catch + 报失败数 |
| 4 | 「把物品放在手上的指令不生效」 | 同上（`switch_item` 是瞬间指令，挂在被门控的行为上 ⇒ 从不执行） |
| 5 | 「我怀疑瞬时指令有 bug」 | ★★ **确认**：唯一调用点在 `PlayerLikeCombat#tick`，而那个行为门控在 `present(ATTACK_TARGET)` 上；非战斗时指令躺 60 tick 后被判"放太久"作废。⇒ 新增 `compat/event/DirectiveTicker`（挂 `MaidTickEvent`，与战斗无关），行为层那两处删除；并新增结构性审计 `tools/check_instant_wiring.py`（第 17 步）钉住接线 |

★ 纪律（[13](13-更正记录与教训.md) §2 的 AS/AT）：**"每 tick 必须前进"的东西不能挂在"有条件才跑"的循环上**（第 3 次出现）；
**用户点的按钮必须留下可读结果**，批处理要**逐项隔离**。

### 3.20.1 按新纪律清点：还有哪些"每 tick 要前进"的东西挂在门控行为上

| 东西 | 原挂载点 | 不打架时会怎样 | 处置 |
|---|---|---|---|
| 瞬间指令（`interrupt`/`disengage`/`reload_now`/`recall_servants`/`dismiss_servants`/`stance_guard`/`stance_attack`/`heal_now`/`switch_item`） | `PlayerLikeCombat#tick` | **全部作废**（60 tick 后判"放太久"） | ★ 已搬进 `DirectiveTicker` |
| 换手伺服（`only_item` / `ban_item`） | 同上 | "拿出来"永不发生 | ★ 已搬进 `DirectiveTicker#itemServo` |
| 副手姿态伺服（举盾） | 同上 | 举盾状态不维持 ⇒ `stance_guard` 也看不出效果 | ★ 已搬进 `DirectiveTicker` |
| 灭火器伺服 | 同上 | 她跟着你跑时着火也不灭 | ★ 已搬进 `DirectiveTicker` |
| **在途动作推进 `tickInFlight`**（引导法术 / 弹匣 / 蓄力射击 / 刀技起手） | 同上 | 目标没了之后通道**停摆**（法术不结算、弹匣不再推进） | ★ **已搬进驱动器**（见 §3.24）：推进每 tick 照跑；**完成信号**回行为层开承诺门（`PlayerLikeCombat#noteInFlightCompleted`），接线由审计脚本钉住 |

**为什么 `tickInFlight` 本次不改**（登记为已知缺口，而不是悄悄留着）：
它属于**承诺门控的权威来源** —— 行为层拿到 `Progress.COMPLETED` 时要调 `Loops#notifyCompleted`
打开承诺门。若把它也搬进驱动器，就必须同时改造"完成信号"的传递
（否则同 tick 两次调用会**双倍推进**射击节奏，或者完成信号被驱动器吃掉、行为层永远收不到），
而那条链路是第十五/十六轮刚调稳的部分。⇒ 本次先保证**用户点的那几个动作**（指令/换手/举盾/灭火）
在任何时候都能跑；`tickInFlight` 的迁移与"完成信号改队列"一起做才安全。
★ 已有的兜底：`GoetyChannel` 的**陈旧判定**（`STALE_TICKS = 40`）保证"没人推进的通道"不会永久占住
"正在引导"这个守卫（见该类注释里第二道修法）；`SpellEntityCleaner` 每 20 tick 清扫无主光束。

---

## ★ 3.21 第十七轮续（三）：**枪的身份**（"正视枪械问题"那一刀）

委托方 2026-10-05 原话：「**这部分的实现就是有 bug，不是什么别的 bug 引起的**」——
核实：**对**。根因一句话：**TaCZ 的所有现代枪共用一个物品，注册名是 `tacz:modern_kinetic_gun`，
真正的身份在 NBT 的 GunId 里**，而我们一直拿注册名当身份。

| 委托方现象 | 根因 | 修法 |
|---|---|---|
| 「我让她拿火箭筒，她拿了一个不存在的东西 `tacz:modern_kinetic_gun`」 | 物品清单**按 id 去重** ⇒ 五把枪挤成一行；回话/日志报的也是那个共用名 | `MaidSnapshot#itemId` → **身份 id**：枪走 TLM 的 `GunCommonUtil#getGunId`（**两个枪包都覆盖**），其余是注册名 |
| 「我让她只用机枪，却还是用冲锋枪扫射」 | `only_item(机枪 / mg / ak47)` **永远匹配不上**那个共用名 ⇒ ABSENT 静默回退 ⇒ 照常用手上那把 | ① 身份 id 修好（上面）；② 枪的**别名**（枪型 `mg`、枪型中文「机枪」、人读名「M249 机枪」）进 `PossessedItem.aliases`/`ItemRef`，`ItemQuery` 按同一套分级匹配 |
| 「且还会拿出别的物品如三叉戟」 | 回退 ⇒ 候选集"不过滤" ⇒ 三叉戟那类动作照做；换手谓词比的又是共用名 | 同上（过滤与换手都回到"身份 id"这一套） |
| 「评分上两把枪一样」⇒ 用机枪还是冲锋枪没区别 | `MaidSnapshot#params` **恒为空**（W23）⇒ `vectors.json` 里那 7 组**按枪型的** override 一行都没生效 | `params` 喂 `gunId` + `gunType`（枪型走**反射** TaCZ 的 `TimelessAPI.getCommonGunIndex(gunId).getType()`） |
| 她自编指令 id `no_summon` + 谎报"我用机枪打他们" | 提示词工程缺口 | skill.md：**id 只能从 `Combat directive ids` 抄**、**被拒要照实说**、**只汇报已生效的指令**；清单里给出 `id + name + [枪型]` |

★ **为什么枪型走反射而不是引依赖**：`build.gradle:81-86` 记着委托方 2026-10-01 的决定 ——
TaCZ 的许可是 `GPL3 / CC BY-NC-ND 4.0`（具传染性），**撤掉依赖**，并立规矩
「若将来重新引入，必须先在 THIRD_PARTY_NOTICES 里把这笔许可账记清」。
⇒ 本轮**不重新引入**：身份用 TLM 的公开抽象，枪型用反射（不链接、不打包、未装即降级）。
★ 并且新增**脚本级**判据（`tools/check_isolation.py` 的"许可纪律"一节）：
**0 处对 TaCZ 的编译期链接**、`build.gradle` 无 TaCZ 依赖 —— 只写在注释里的规矩不算规矩。

★ 顺带关掉的 W23 一半：枪的 `gunType` 现在真的喂进去了（7 组 override 生效）；
`fireMode`/`ammo` 仍未做（需要 `IGun` 实例，登记为 W23 剩余）。

---

## ★ 3.22 第十七轮续（四）：only_item 的真根因、指令"悄悄消失"、提示词投递

### 3.22.1 指令的完整生命周期（**一页**，用来终结"到底谁改了它"的猜测）

| 阶段 | 代码位置 | 说明 |
|---|---|---|
| ① 下达 | `DirectiveHolder#issue` ← 命令 / 女仆对话工具 / 动态指挥 LLM | 记下 `source`（player / chat / llm）；回话里**回显参数**；`feasibility` 附现状 |
| ② 在force | `DirectiveBus`（持续指令进 `sustained`，瞬间指令进 `instantQueue`） | 持续指令有 TTL（`only_item` = 2400 tick）；同互斥组会**自动取消**同组旧指令 |
| ③ 执行（瞬间） | `DirectiveTicker#onMaidTick` → `DirectiveHolder#tick` → `applyInstant` | **每 tick 都跑**（与战斗无关）；结果写进 `Entry#lastInstantResult`（`/flp directive list` 可见） |
| ③ 执行（持续） | 行为层每 tick 读总线：候选集过滤（`DirectiveFilter`）/ 步法 / 姿态 / 聚晶池 / **指令伺服换手** | 过滤读的是**当前**状态；换手由 `DirectiveHolder#itemServo` 负责（每 tick、与战斗无关） |
| ④ 结束 | TTL 到期（`bus.tick`）/ 被 `cancel` / 互斥组被覆盖 | ★ `cancel` 现在**留痕**（日志 + "最近取消"），且**动态指挥撤不掉玩家/对话下达的指令** |

★ 这张表就是为了回答"**它到底是怎么没的**"：以前 cancel 完全静默，现在四种结束方式**每一种都有出口**。

### 3.22.2 委托方第 1 条的两个 bug（确认是两个，不是同一个）

| bug | 真根因 | 修法 |
|---|---|---|
| **`only_item` 完全不起效** | `DirectiveFilter#itemPlan` 的两条回退支（`!mainMatches && result.size()==ids.size()` 与 `result.isEmpty()`）**把未过滤的整表原样还回去** ⇒ 她要的东西在背包里（最常见状态）时等于**没下过这条指令** | 换手已由**指令伺服**保证（与候选集无关）⇒ **两条回退特例删除**；新规则 2 条：东西在她身上 ⇒ 一律过滤（不在主手 ⇒ `NEED_HAND`，伺服去换）；她身上真没有 ⇒ 才回退 + 说明 |
| **"不在战斗快速自动消失"** | 复核：TTL = 2 分钟；唯一的 `forget`（清总线）**零调用点** ⇒ 只剩两条 LLM 通路的 `cancel`；而 `cancel` **一行日志都不写** | `cancel` 全留痕（日志 + `/flp directive list` 的"最近取消：X（来自 chat）"）+ **动态指挥不许撤玩家/对话下达的指令** |

### 3.22.3 提示词审计（委托方第 3、4 条）：**不是"堆叠"，是"投递"与"冲突"**

取证（`javap` TLM 的 `PapiReplacer` / `SkillLoader.getSkillSummary` / `UseSkillTool`）：
1. **技能不是每回合全量注入的**：`${available_skills}` 里只有 `<name>` + `<description>`；
   正文只在模型调 `use_skill` 时返回 ⇒ **我们那 200 行正文等于"看运气"**；
2. TLM 内置系统提示词有**硬禁令**：`Zero Tool Reporting`（不许向玩家汇报工具结果）、
   `Roleplay Immersion & Absolute Bans`（不许说时间数字、不许说 "schedule"/"work mode" 等系统词）；
   ⇒ 我们 skill 里"把工具回话照实告诉玩家"**与它直接冲突** —— 这解释了两件事：
   她为什么**嘴上说"我用机枪打他们"**却什么都没下发（宿主提示词**要求**她不许汇报工具结果），
   以及观感上的"降智"（两条提示词互相打架，模型只能二选一）。

修法：① 新增**每回合上下文** `flp_command_rules`（四条硬规矩，约 5 行）；
② skill 的 `description` 里加"下任何指令前必须先 `use_skill`"；
③ skill 正文的汇报要求改成**符合人格**的说法（用她自己的口吻说真话，不提"工具/指令/系统"）。

### 3.22.4 女仆 LLM 相关工程的对照审计（委托方第 4 条）

| 计划项 | 状态 | 证据 |
|---|---|---|
| 女仆对话可直接下令（工具通道） | ✅ | `FlpDirectiveTool`（`flp_directive`，四字段）+ `llm.toolDirectives` 开关 |
| 每回合上下文（她必须随时知道的少量事实） | ✅ | `FlpMaidContexts` 的 `CATEGORY_NOW`：刀/SA、幻影剑、生效指令、击杀窗口、备忘录、攻击名单、**（本轮）四条硬规矩** |
| 惰上下文（按需查，省 token） | ✅ | `CATEGORY_DETAIL`：动作 id、物品清单、指令 id、往日击杀/物品、备忘录、附近怪物、**（上轮）聚晶与法术 + 法术介绍** |
| 提示词工程（skill） | ✅ 但有**投递**问题 | `data/touhou_little_maid/skills/flp-combat-command/skill.md`（带 front-matter；TLM 只自动带 name+description） |
| 动态指挥 LLM（慢变量 + 指令） | ✅ | `LlmAdvisor` + `LlmBridge`（`llm.applyMode`；本实例已设 `apply`）；★ 本轮加"不许撤玩家/对话的指令" |
| 备忘录 / 怪物名单 / 法术池 | ✅ | `MaidMemory` + `/flp memo`；`MaidAttackList` + `/flp monsterlist`；`flp_spells` / `flp_spell_descriptions` |
| **她的"说真话"能不能落地** | ⚠️ 本轮才对齐 | 与 TLM 的 `Zero Tool Reporting` 冲突 ⇒ 现在改成"用她自己的口吻说真话" |

★ 结论：机制都在，**缺的是"投递"与"与宿主提示词对齐"**这两件事 —— 本轮补上。

---

## ★ 3.23 第十七轮续（五）：指令下"拿着枪发呆" + 召唤法术的向量与冷却

### 3.23.1 「指令下只拿着枪发呆」（委托方：酒狐 + "接下来用火箭筒迎敌"）

**真根因**：`only_item` 的过滤用的是 `Resolution#evidenceOf(id)` ——
**解析器记录的"哪件物品让这个动作可用"**，而同一载体的多件物品里**只保留一件**
（按槽位优先级）⇒ 会出现"她手上正是她要的那把枪，而证据落在**另一把枪**上"
⇒ `itemGate` 判"这个动作不靠她要的东西" ⇒ 动作被挡 ⇒ **候选集空 ⇒ 发呆**。

**修法**：过滤依据改成**载体规则判据**（"她要的那件能不能承载这个动作"），
并补一条护栏：**她要的东西已经在手上、却一个动作都过滤不出来 ⇒ 回退 + 如实说明**
（否则"不许发呆"的护栏自己会变成发呆）。

### 3.23.2 铁魔法召唤类法术的向量与"用一次就很难再用"

| 问题 | 事实（取证） |
|---|---|
| 向量 | `irons:cast_spell` 的 SUMMON override = **`{SINGLE_DAMAGE: -0.2, REINFORCE: 0.8}`**（`catalog/vectors.json`）；`AGENT` 来源、confidence=medium |
| 这条轴有用吗 | ★ **REINFORCE 从来没有来源**：`ContextBias`（世界→需求）不产出它、`FitnessCalculator` 给它**常量 1.0** ⇒ 0.8 是死重；召唤只在"需求≈0"的**死区**里被偶然选中（最近邻最小范数），不是"她觉得需要增援" |
| 文档承诺的"召唤位满 ⇒ 过滤" | ★ **没有实现**（那一行就是常量 1.0）⇒ 已补齐，但**位置换了**：做成 `decision/ServantSlots`（上限 4），由**挑法术的池子**（Goety 聚晶 / 铁魔法法术）按类别过滤 —— ★ 动作级过滤是**错的**（只有 `cast_focus`/`cast_spell` 的 **override** 带 REINFORCE，它们的默认向量是 0 ⇒ 动作级根本判不出来，硬做会把火球一起挡掉）。接线由 `check_instant_wiring.py` 用正则盯住 |
| 为什么"用一次就很难再用" | ★ 主因是**铁魔法自己的冷却**：`javap SummonVexSpell` → `DefaultConfig.setCooldownSeconds(150.0)` = **150 秒**；我们按 label 记进 `SpellCooldownLedger`（与玩家一致，**这是设计如此**）。次因是"她已经有召唤物了"—— 本轮**只把"召唤位"这道门补上**（`ServantSlots`：满了就不再往池子里放召唤法术） |
| 仆从数算不算铁魔法召唤物 | ★ 原来**不算**（`servantCount` 只数 Goety 的 `IOwned`）⇒ 恼鬼/骷髅对世界**不可见**（"召唤位满"永不成立）。已补上 `IronsServantOps.countSummons` |

★ 现在 `/flp why` 的情境一行里会带上「仆从 N」，可以直接看出她名下的召唤物数量。

### 3.23.3 ★ 召唤的最终模型（委托方拍板）：**增量 + 消耗**

> 委托方 2026-10-05：「召唤算法没必要那么精致，**维度上有增量和消耗就好了**。」

⇒ 一句话模型（不再有"拟合/因子/情境调参"那一套）：

| 侧 | 落点 | 规则 |
|---|---|---|
| **增量** | `p[REINFORCE]`（唯一的需求来源） | `0.5 × (上限 − 已有仆从) / 上限`；**仅当「有目标」且「她真能施法」**（游戏侧喂的 `goety:can_cast` / `irons:can_cast`）时给；满了 ⇒ 0 |
| **消耗** | 动作层 + 执行器（本来就有） | 法术自己的 `cooldownTicks` + 冷却账本（铁魔法恼鬼 = **150 秒**）+ 法力 |
| **上限** | `decision/ServantSlots`（唯一判据，CAP=4） | ① 算增量 ② 挑法术的池子过滤（满了就不进池子，免得死区里 p≈0 时靠最小范数被偶然选中） |

★ 两道**走过弯路**的记录（都在代码注释里）：
1. 把"召唤位"做成 `FitnessCalculator` 的**因子**是**方向错的** —— 因子是乘法，
   而 `p[REINFORCE]==0` 时乘 0 会让动作向量**更靠近原点** ⇒ **满位反而更想召唤**；
2. 直接把增量调到 0.6 会把**所有**动作的加权距离一起放大 ⇒ 相对间距被压缩 ⇒
   更多动作落进 `tieWindow`（`PipelineSelfTest §7` 的"恒定局势收敛"红了）。
   ⇒ 解法不是动度量（那是需要实测校准的大改），而是**问一句语义**：
   **"她根本放不出法术时，还算什么增量？"** ⇒ 加"能施法"这道门，权重取 0.5。
   ★ 这也让"增量/消耗"这个模型自洽：**没有消耗的载体（放不出来）时，增量无从谈起**。

---

## ★ 3.24 第十七轮续（六）：召唤 = 增量 + 消耗；在途动作推进搬出"有条件才跑"的行为

### 3.24.1 召唤的最终模型（按委托方拍板，见 §3.23.3）

```text
增量（唯一的需求来源）  p[REINFORCE] = 0.5 × (CAP − 已有仆从) / CAP
                        ★ 仅当「有目标」且「她真能施法」（goety:can_cast / irons:can_cast）
消耗                    法术自己的 cooldownTicks + 冷却账本（ISS 恼鬼 150 秒）+ 法力（本来就有）
上限（唯一判据）        decision/ServantSlots（CAP=4）→ ① 算增量 ② 挑法术的池子过滤
```

★ 让前后两版修法都"差一点"的那个问题，最终不是靠调参解决的，而是靠**问对语义**：
**"她根本放不出法术时，还算什么增量？"** ⇒ 加"能施法"这道门之后，
`PipelineSelfTest §7` 的"恒定局势收敛"立刻恢复（6 个周期选中同一个动作）。

### 3.24.2 在途动作推进（`tickInFlight`）搬进驱动器

它是"**每 tick 必须前进的东西不能挂在有条件才跑的循环上**"的**第四个实例**
（前三个：引导法术、刀技、瞬间指令/换手伺服）。

* 原来：只在 `PlayerLikeCombat#tick`（门控在"她有攻击目标"）里推进
  ⇒ **目标一没就停摆** —— 法术不结算、弹匣不再开火（观感"举着杖/枪卡 2 秒"，
  `GoetyChannel` 的 `STALE_TICKS = 40` 只保证不**永久**卡住）。
* 现在：推进在 `DirectiveTicker`（挂 `MaidTickEvent`，与战斗无关）；
  **完成信号**回行为层开承诺门（承诺门住在 `PlayerLikeCombat`）：
  ```java
  if (ActionExecutors.tickInFlight(maid, now) == Progress.COMPLETED) {
      PlayerLikeCombat.noteInFlightCompleted(maid);
  }
  ```
  ⚠️ 行为层**绝不能再**自己调 `tickInFlight` —— 那会每 tick 推进两次（**射速翻倍**）。
  ★ 这条由 `tools/check_instant_wiring.py` 用正则钉住（"注释里写过不算"）。

---

## ★ 3.25 第十七轮续（七）：提示词两条习惯 + 击败强敌自动对话

### 3.25.1 提示词（委托方第 1 条）

| 委托方原话 | 落在哪 | 写法 |
|---|---|---|
| 「备忘录中任务完成了记得去除」 | skill.md 新增「五点五六」+ **每回合上下文**第 6 条 | 做完 / 玩家说不用了 ⇒ 立刻 `flp_memo remove`；不知道序号就先读一遍 |
| 「新的指令和旧的冲突了，把旧的取消并说一声就好」 | skill.md 新增「五点五五」+ **每回合上下文**第 5 条 | **同组**冲突会被自动取消（回话里会写「★ 自动取消了同组指令」）⇒ **你要把这件事说一声**；**不同组但语义冲突**（`only_item` 一把剑 vs 手上是法杖）⇒ 先 `cancel <旧 id>` 再下新的 |

★ 为什么"冲突"那条也进了**每回合上下文**：它发生在"下指令"这个最频繁的动作里，
而 skill 正文是要模型主动 `use_skill` 才读到的（上一轮的教训）。

### 3.25.2 击败强敌 ⇒ 自动触发一次对话（委托方第 2 条）

**入口（TLM 公开 API，`javap` 取证）**：
```java
EntityMaid#getAiChatManager()                                    // MaidAIChatManager
MaidAIChatManager#chat(String message, ChatClientInfo, ServerPlayer)
ChatClientInfo.fromMaid(EntityMaid)
```
⇒ 不自己造通道：把"战斗记录"当成**一轮用户消息**交给她，回复由 TLM 说给主人
（TTS / 聊天气泡一并生效）。触发点在 `KillListener`（它已经算好了归因：她本人 / 她的投射物 /
她的仆从都算"她击败的"）。

**三道把守 + 一条冷却**（缺一条都会变成骚扰）：
1. `llm.bossChat`（开关，默认开）；
2. `llm.bossHealthThreshold`（默认 100，**原版末影龙/凋灵无条件算**）——
   "什么叫 boss 级"在整合包里没有统一答案，所以用血量阈值而不是类型；
3. 她**配没配 LLM**（`getLLMSite() == null` ⇒ 静默跳过；否则 TLM 会往聊天栏打印配置提示）；
4. **主人在不在线**（对话的接收者是他）；★ 冷却 `BossChatPolicy.DEBOUNCE_TICKS = 20 秒`（连杀不刷屏）。

★ 判据（新纪律 BB）：**主动行为必须配开关 + 条件 + 冷却**。

---

## ★ 3.26 第十七轮续（八）：脱战判据、索敌迟滞、仆从自知之明

### 3.26.1 「指令一脱战就消失」——**先查证，再动手**

复核结论：**本项目没有"脱战清指令"的代码**（`DirectiveHolder#forget` 零调用点）。
委托方看到的"消失"是三条之一：

| 机制 | 数值 | 说明 |
|---|---|---|
| 持续指令 **TTL** | 除 `only_actions` = **6 秒**，其余默认 **120 秒**（上限 10 分钟） | 到点自动解除并记日志 |
| **同互斥组**被覆盖 | — | 回话里会写「★ 自动取消了同组指令：…」 |
| ★ `only_actions` 的**白名单看门狗** | 40 tick | 连续 2 秒没做出任何动作 ⇒ 自动解除 —— **「清空敌人」正是它的误伤**（没敌人 ≠ 她卡住） |

处置：
1. **修掉真实误伤**：看门狗在**脱战时不许解除**（脱战 = 连续 5 秒没有仇恨对象）；
2. 脱战判据统一为纯逻辑 `decision/CombatState`（`IDLE_TICKS = 100`）；
3. 他想要的那个行为做成开关 **`directive.clearOnDisengage`（默认关）**：开着时脱战满 5 秒自动解除全部持续指令并说明；
4. 他担心的"**永远不脱战**"：开关默认关；即便打开，**TTL 始终是硬上限**（指令不会永远活着）。

### 3.26.2 索敌：产生仇恨 vs 锁定（迟滞）

| 配置 | 默认 | 语义 |
|---|---|---|
| `combat.targetAcquireRange` | **24** 格 | 她**没有**目标时的扫描半径 ⇒ "产生仇恨" |
| `combat.targetKeepRange` | **40** 格 | 她**刚丢**目标（3 秒内）时的扫描半径 ⇒ 把目标**找回来**，不再"稍微跑远就不打了" |

★ 判据只用 `maid.canAttack(e)`（与执行器同一口径：已含 `TargetFilter` 的 focus/ban entity 指令）。
★ 实现挂在**每 tick 的驱动器**上（`PlayerLikeCombat#tickTargeting`）—— 她没目标时行为层不跑，
放行为里等于永不索敌（纪律 AS 的第 5 个实例）。

### 3.26.3 仆从自知之明

新增惰上下文 **`Her servants`**（`flp_servants`）：现在有几只（Goety + 铁魔法召唤物分别计数）、
**她们是她的**、以及最要紧的一句 ——

> ★ 清理她们的**唯一方式**是瞬间指令 `dismiss_servants`（由玩家或她自己的对话下达）；
> 她自己那个"清仆从"动作已被禁用（`role=directive`）⇒ **她不会也不能主动清她们**；
> 所以她们若在没下这条指令的情况下消失，**那不是她干的**。

skill.md 同步新增「五点五五七、关于你的仆从」一节（含"少用召唤是 `no_summons`，不是 `no_summon`"）。

---

## ★ 3.27 第十七轮续（九）：两条 LLM 通路的关系 + 「让她用枪」失败的真正原因

### 3.27.1 委托方的问题：**女仆 LLM → 动态指挥 → 指令**，还是**女仆 LLM → 指令**？

**答：两者都有，而且互相独立**（都往**同一条指令总线**里写，靠 `source` 区分）：

| 通路 | 入口 | source | 是否经过对方 |
|---|---|---|---|
| 女仆对话 LLM（她自己） | 工具 `flp_directive` → `DirectiveHolder.issue(...)` | `chat` | ❌ 不经过动态指挥 |
| 动态指挥 LLM | `LlmAdvisor`（`llm.*` 配置，chat 模型）→ `LlmBridge.tick` → 同一条总线 | `llm` | ❌ 也不经过她 |
| 玩家 | `/flp directive set ...` | `player` | ❌ |
| 思维层（JEV） | 只写**旋钮**（`thinking.*`），**不下指令** | — | — |

★ 处置（正是委托方给的两条路里的第二条："两者都有 ⇒ 需要在 prompt 中写明语法与注意事项"）：
新增 skill「**五点五四、语法与注意事项**」+ 每回合硬规矩第 7 条（`only_item` 才是"一直用"，
`switch_item` 只是一次性；换打法前先 `cancel` 旧的）。

### 3.27.2 「让她用枪」为什么没生效（`tacz:fn_fal` 其实是对的）

* ★ **`tacz:fn_fal` 不是物品，但它是"枪的 id"**：TaCZ 把枪身份放在 **NBT 的 GunId** 里，
  枪包 `tacz_default_gun` 里就是 `assets/tacz/.../fn_fal.*`；**所有现代枪共用一个注册名**
  （`tacz:modern_kinetic_gun`）。日志显示 `switch_item => 成功：把 tacz:fn_fal 换到主手`
  ⇒ **换手真的成功了**。
* 真正的两个原因：
  1. **`switch_item` 只换一次手** ⇒ 决策层选中"由三叉戟承载"的动作时，执行器把三叉戟换上来了
     （`ranged_only` 允许三叉戟：它就是远程类）。**要"一直用"必须下 `only_item`**。
  2. ★ **上一场的 `only_item(minecraft:netherite_sword)` 还挂着**，而背包已清空 ⇒ 它处于 ABSENT：
     按护栏**不过滤候选集** ⇒「只用 X」静默变成空操作，还让伺服每 tick 白试一次。

### 3.27.3 修法

1. **过期物品指令自动解除**：连续 **5 秒**拿不到那件东西 ⇒ 自动 `cancel`（`source=auto`，
   留痕 + 说明）；判据是纯逻辑 `DirectiveFilter#staleItemDirective`（可离线断言）。
2. **`switch_item` 教会配对**：工具描述与回话都写明"一次性 ⇒ 要一直用请再下 `only_item`"。
3. **提示词**：skill「五点五四」给出一张**语法表**（"只用某把枪"= `only_item` + 枪自己的 id；
   "只用机枪"= `only_item` + `mg`；"只用某种手段"= `ranged_only` 之类），
   并点明**三条最容易踩的**（`switch_item` 不是"一直用"；换打法先 `cancel` 旧的；
   别写 NBT/附魔细节）。

---

## ★ 3.28 第十七轮续（十）：「有些攻击可以绕过指令」——三条真旁路

委托方：「哪怕我指定了一种攻击方式且女仆确实执行了，也有一些攻击可以**绕过**指令
（指定枪械时她有时会拔刀砍一下、有时会放一颗聚晶）。我希望：在有 only 系列指令时，
**应激反应保持静默**。」

### 3.28.1 排除项（都不是原因）

| 嫌疑 | 复核结果 |
|---|---|
| TLM 的脑行为自己动手 | ❌ `MaidMeleeAttack` / `MaidUseShieldTask` **只由 TLM 自己的任务构造**（`TaskAttack` / `TaskGunAttack` / `MeleeTaskJS` / `TaskFeedAnimal`）；我们的任务只注册三个行为（findTarget / stopAttack / decideAndAct） |
| 「万法皆通」全局注入 | ❌ 它的两个 mixin 分别是"GUI 打开时不写 WALK_TARGET"与"远程女仆脑刷新"；它自己的施法只跑在**它自己的任务**上 |

### 3.28.2 真旁路（三条，都在我们这边）

| # | 旁路 | 为什么"有时" | 修法 |
|---|---|---|---|
| 1 | **ABSENT 回退** | `only_item(X)` 而 X **不在她身上** ⇒ 整表**不过滤**（第 68 条的护栏）⇒ 那段时间什么都能做 | ABSENT 状态**写进 `describe()`**（`/flp` 与工具回话直接显示"这条当前不生效，5 秒后自动解除"）+ 0.23.0 起的 5 秒自动解除 |
| 2 | **在途动作继续** | 连段 / 引导 / 弹匣**跨 tick**，指令下达后它们会**做完**（只有 `interrupt` 才打断） | **在途动作与指令冲突 ⇒ 立刻中止**（类别与物品两道判据；与 `interrupt` 同义） |
| 3 | **副手循环不受物品指令约束** | 物品过滤当时**只在 mainLoop 跑** ⇒ 副手上的刀/盾类动作绕过 `only_item` | 物品过滤**两个循环都跑**（副手用副手物品当"手"） |

★ 结论（新纪律 BG）：**"过滤了候选集"≠"她做不出别的"** —— 还要问
**"除了候选集，还有谁会让她动手？"** 本项目有两条：**在途动作**与**另一个循环**。
★ 并且：**指令让某个行为失效时，失效状态必须可见**（否则用户会把它读成"指令被绕过"）。

---

## ★ 3.29 第十七轮续（十二）：**法术分系、仆从三句话、以及"加一条指令"的真实成本**

委托方三条（原话）：

> 1.「女仆似乎做不到"指定使用铁魔法"，对于她而言，**铁魔法和巫法是一个类的**。可以在指令里面细分一下，
>   铁魔法的魔法、诡厄巫法的魔法。当然**总类的指令也保留**。」
> 2.「prompt 中要让女仆知道：**主人要求清除仆从，就是清除仆从的指令。主人要求召集仆从才是召集仆从的指令。
>   且如果主人没明说禁用召唤，则不进行禁用召唤的指令。**」
> 3.「我想知道，我们的模组和涉及到的几个模组分别是什么关系？哪些是附属、哪些是联动？」

### 3.29.1 法术分系：`SpellSystem` + 两条细分指令（总类保留）

| 层 | 改了什么 | 判据 |
|---|---|---|
| 分类 | `DirectiveFilter.SpellSystem{NONE, GOETY, IRONS}` + `spellSystemOf(id)` | **只对 `ActionClass.SPELL` 有定义**；认不出的给 `NONE`（在"只用铁魔法"下**会被挡下** = 保守一侧，不冒充） |
| 动作 | `goety_only` / `irons_only` 与 `magic_only` **同互斥组 `means`** | 口径与 `magic_only` **刻意一致**：非施法动作（换弹/管仆从/位移）**一起挡下** |
| 法术池 | `focusAllowed(bus, system, category, label)` 四参数重载；Goety 池 / 铁魔法表 / 场景 / 上下文四处都用它 | 动作挡了而**池子照喂** = 她"以为自己还能放"（第 14 轮铁魔法那次的同族错误） |
| 回话 | `DirectiveHolder#feasibility` 新增 `goety_only`/`irons_only` 分支：该系一个法术都没有 ⇒ **当场说清"候选集会被清空"**，并给替代建议 | 头号禁令（候选集被指令清空）必须在**下达的当下**可见 |
| 日志 | `PlayerLikeCombat#mainLoopResolution` 在有体系指令时把被挡的清单前缀写成 `irons_only 生效 ⇒ 只放行铁魔法` | 原来只写"某 id 被挡下" ⇒ 读日志的人分不清是总类还是细分挡的 |

### 3.29.2 仆从三句话（清除 / 召集 / 不许召唤）

- **每回合上下文**（`FlpMaidContexts#CommandRulesContext` 规则 8/9）与**技能正文**（五点五五八 + §四 场景表）
  各写一遍：`clear/dismiss 仆从 ⇒ dismiss_servants`、`call/gather ⇒ recall_servants`、
  **`no_summons` 只在主人明说"不许召唤"时才下**（默认 **不下**，发现旧的还在就 `cancel` 并说一声）。
- ★★ **代码那一半**（提示词只是说明，门必须在代码里）：
  `DirectiveSpec#playerOnlyReason` 把 `no_summons` 与 `dismiss_servants` 标为**只能由主人下达**；
  `DirectiveHolder#issue` 对 `source=llm`（**动态指挥官** —— 它只看战斗统计与场景，**看不到主人说了什么**）
  **直接拒绝**并回一句可读说明；`LlmAdvisor` 的提示词同步加规则 9。

### 3.29.3 「加一条指令」要动几处（本轮复盘）

```
① 门      DirectiveFilter#allowed（动作级过滤）
② 池子    focusAllowed（法术池 / 场景 / 上下文 —— 三处调用，漏一处 = 指令只生效一半）
③ 上下文  FlpMaidContexts#CommandRulesContext（每回合都看得见的地方）
④ 正文    skill.md（表 + 场景表 + 专章）
⑤ 回话    DirectiveHolder#feasibility（做不到/会清空候选集 ⇒ 当场说）
```

★ 验收口径（新纪律 BJ）：**"它挡下的东西，用户在四处能不能看见？"** ——
LLM 清单 / 玩家命令 / 下达回话 / 日志与 `describe()`。
★ 本轮自测从 **192 → 206** 条断言（`DirectiveSelfTest §5` 新增 11 + 玩家专属 3）。

### 3.29.4 与其它模组的关系（委托方第 3 问，结论表）

| 模组 | 关系 | 证据 |
|---|---|---|
| 车万女仆 TLM | **前置 / 宿主**（我们是它的**附属**） | `mods.toml`：`touhou_little_maid` **mandatory=true**（唯一的硬前置）；`build.gradle`：compileOnly **+ runtimeOnly**；代码走 `@LittleMaidExtension` / 任务注册 / `MaidAIChatManager` 等公开 API |
| 诡厄巫法 Goety | **联动**（可选，装了才生效） | compileOnly（**绝不 runtimeOnly**）+ `ModList.isLoaded("goety")` 守卫 + 独立 compat 类；7 条动作 + 4 条载体规则 + 仆从管理 |
| 铁魔法 Iron's Spellbooks | **联动**（可选） | 同上；12 条动作 + 2 条载体规则 + 94 个法术的法术意图表 |
| 拔刀剑重锋 SlashBlade | **联动**（可选） | 同上；30 条动作（本项目动作数最多的来源）+ 1 条载体规则 |
| Curios | **联动（前置的传递依赖）** | 它是 Goety/铁魔法饰品槽的前置；我们只用它做饰品枚举（W5），同样 compileOnly |
| TaCZ 枪械 | **联动，但【不引依赖】** | 许可原因（GPL3 / CC BY-NC-ND 4.0）⇒ 撤掉编译依赖，改走 **TLM 公开抽象 `GunCommonUtil`** + 反射读枪型；`tools/check_isolation.py` 钉住"**0 处对 TaCZ 的编译期链接**" |
| 卓越前线 Superb Warfare | **联动（载体规则）** | `catalog/carriers.json`：`requiresMod = "superbwarfare"`（未装则整条规则不加载） |
| Pillager's Gun | **联动（缺口，显式可见）** | 载体规则 `pillagersgun:gun` 标 `unresolved`（第 34 条 fail-closed 的做法）；它的枪械接口**未勘测** ⇒ 保持缺口 |
| 万法皆通 TLM Spell | **非依赖、非联动 —— 共存 + 重叠告警** | 代码里 0 处链接；`CarrierDetector` 把它列进 `CONFLICTING`（同场会注册两套施法任务，出问题时先隔离定位）；另有数处**作为参考实现**被引用（读它的字节码学做法，不是依赖它） |
| 调律师 Goety Tuner | **参考实现（MIT，同一作者）** | 同样在 `CONFLICTING` 里（它会加自己的 Boss/仆从）；我们的"附伤/仆从管理"结论来自读它 |

★ 另有 4 个 **Goety 附属**（`goetyawaken` / `goety_ladder` / `goety_cataclysm` / `goetytwilight`）：
它们加的是**长按法术**，我们经 Goety 的**法术闭包表**自动扩表 ⇒ 关系是"**经 Goety 间接**"，
不需要为它们写任何代码（`CarrierDetector` 只做探测与日志）。

---

## ★ 3.30 第十七轮续（十三）：实机回测的三件（**前摇被反复重启** / 总类指令的可见性 / 日志配额）

委托方两条（原话）：

> 1.「修改后，铁魔法似乎有 bug，我让她只用铁魔法打，她**隔了好久才释放一个法术**。」
> 2.「不是说总则的指令保留吗？即要求女仆仅可使用魔法（铁魔法+巫法）的指令。
>   且**女仆 llm 要知道该指令是这个意思**。」

### 3.30.1 铁魔法"隔好久才放一个"——前摇每 tick 被重启

**证据（实例 `debug.log`，18:04:03 下达 `irons_only` 之后 29 秒）**：

```
18:04:06 ★ 换手：goety:nameless_staff → irons_spellbooks:evoker_spell_book
18:04:06 铁魔法起手 irons_spellbooks:fang_ward（前摇 15 tick）  → 执行 irons:cast_scroll
18:04:06 铁魔法起手 irons_spellbooks:summon_vex（前摇 20 tick） → 执行 irons:cast_spell
18:04:07 铁魔法起手 irons_spellbooks:fang_strike（前摇 15 tick）→ 执行 irons:cast_scroll
…（每秒 2~3 次，两条动作交替）
18:04:32 铁魔法施放：irons_spellbooks:fang_strike（从 唤魔者魔典 第 1 槽）   ← 29 秒里第 1 次真正放出来
```

**机制**：

| 层 | 事实 |
|---|---|
| 清单 | `irons:cast_spell` 是**族动作**，静态承诺 = 族默认（`kind=INSTANT`）⇒ **0**；`irons:cast_scroll` 连 `duration` 都没写 ⇒ **0** |
| 清单里那条覆写 | `durationOverrides` 按 `castType`（LONG ⇒ 20 tick）写好了，但 `pickCommitment` 是**按物品参数**查的 —— **法术类型不是物品参数** ⇒ 那条覆写从未被消费（第 27 条"数据写了没人读"的同族） |
| 决策层 | 承诺 0 ⇒ 不进承诺门 ⇒ **每 tick 重选**；选回同一条动作 ⇒ 执行器再次 `ironsCast` ⇒ **覆盖 `readyAt`**（前摇重新计时） |
| 结果 | 前摇（15~20 tick）**永远走不完**；只有"连续十几拍恰好都选中同一条"时才偶然放出一个法术 |

**修法**（`DecisionCycle` 的 1.5 段，纯逻辑层）：

```
没有承诺（inFlight == null）但执行器说"她正在做"  ⇒  这一拍不开新决策（并留痕）
```

- ★ 这正是 `BusySource` 注释里**从第一天就写着**的规则（「在执行器说'还在忙'期间，本循环不开新决策」）——
  实现却只写在"承诺到期之后"那一支里 ⇒ **只有承诺 > 0 的动作享受它**（新纪律 BK：注释里写的无条件规则，实现里也必须是无条件的）。
- ★ 两个"忙"分开：`ActionExecutors#isBusy`（"这条动作做完了没"，含纯冷却）与
  `ActionExecutors#isActivelyBusy`（"她本人能不能另开动作"，纯冷却不算）
  ⇒ 新门用后者（否则**刚开完一枪她会站着等冷却**，而不是换近战）。
- ★ 看门狗②+闩锁：执行层可能漏清在途状态 ⇒ 200 tick 后**不再等它**，并**一直放行到它自己清干净**
  （否则"每 200 tick 放行一次"= 每 10 秒动一下，等于没放行）。

### 3.30.2 「仅使用魔法」的总类保留 + 三处可见面（委托方第 2 条）

- **总类 `magic_only` 保留**（记作「仅使用魔法攻击（任意一系：铁魔法 + 诡厄巫法）」），
  细分 `goety_only` / `irons_only` 与它同互斥组 `means`。
- ★ 但日志暴露了**真问题**：18:02:30 主人说"只用铁魔法"，模型**在同一次回话里连下两条**
  （`irons_only` 先、`goety_only` 后，相差 0 毫秒）⇒ 互斥规则让**后一条顶掉前一条**
  ⇒ 她用了**主人没要的那一系**。⇒ 三处可见面一起写清"没指名 ⇒ 总类；指名才细分；**绝不同时下两条**"：
  ① `FlpDirectiveTool.summary`（工具描述，模型一定看得到）
  ② `FlpMaidContexts#CommandRulesContext` 规则 10（每回合上下文）
  ③ `skill.md` 的手段表 + 场景表 + 描述。
- ★ 并把"是它自己下的另一条把它顶掉了"写进**互斥回话**（模型唯一的自我纠正输入就是那句话）。

### 3.30.3 诊断设施的配额（我在查上面两件事时被自己的日志挡住）

一句「指令挡下这颗聚晶」在**每 tick 重建的挑选池**里判一次 ⇒ 每秒 20 遍 × 8 颗 ⇒
一次会话 `debug.log` **4.7 MB**，真正要看的那 20 行被埋了。⇒ 新增纯逻辑 `LogThrottle`
（key + 时间窗，表自我修剪，空 key 放行），两处池子过滤（Goety / 铁魔法）都用它。
★ 纪律 BM：**"这句日志的调用者多久跑一次？"** 每 tick ⇒ 上节流。

### 3.30.4 验收（离线可复跑）

| 断言 | 位置 |
|---|---|
| 前摇 20 tick 内**只起手一次**（旧实现会每 tick 重启） | `GameLoopSelfTest §4.5`（新增 6 条断言） |
| 一直报"忙"也不会锁死（200 tick 后放行 + 闩锁） | 同上 |
| **纯冷却不锁决策层**（两个"忙"必须分开） | 同上 |
| `magic_only` 仍放行两系 + 细分互斥 + 玩家专属 | `DirectiveSelfTest §5` |
| `LogThrottle` 的窗口/多键/空键/自我修剪 | `DirectiveSelfTest §5` |

---

## ★ 3.31 第十七轮续（十四）：**她自己的召唤物会索敌她**（"主人"= 玩家这一条隐含前提）

委托方原话：

> 「存在女仆的铁魔法召唤物（召唤出的僵尸、召唤出的骷髅、召唤出的恼鬼）会索敌女仆的问题。
> 虽然不会对女仆造成伤害，但是仇恨会在女仆身上，同样女仆也会攻击其召唤物。
> 我测试了一下，这些召唤物**于玩家是中立关系**，对于女仆而言：它们会先攻击女仆攻击的对象，
> **然后转回来尝试攻击女仆**（但他们无法造成伤害），女仆也会同样尝试攻击它们（也造成不了伤害）。」

### 3.31.1 取证：反编译铁魔法 3.16.3（CFR）

| 事实 | 出处 |
|---|---|
| 召唤物用一条"保护主人"的目标行为 | `SummonedZombie/Skeleton/Vex/PolarBear#registerGoals` 里 `f_21346_`（targetGoals）第 5 条 = `GenericProtectOwnerTargetGoal`（僵尸/骷髅/恼鬼**都是这一套**） |
| 它会打「正在攻击主人的 Mob」，**也会打「正在攻击主人名下召唤物的 Mob」** | `GenericProtectOwnerTargetGoal#canUse` 的扫描谓词：`aggressor.getTarget() != null && (target.uuid == owner.uuid \|\| (target instanceof IMagicSummon && itsSummoner.uuid == owner.uuid))` |
| ⇒ **玩家侧永远不会触发** | 扫描对象是 `level.getEntitiesOfClass(Mob.class, …)` —— **玩家不是 `Mob`**（没有显式的 `instanceof Player` 判据，坏就坏在这条**隐含前提**上） |
| ⇒ **女仆侧必然触发** | 她是 `Mob`。日志实证：我们自己的索敌把她自家召唤物设成了目标（`索敌：… ⇒ 召唤出的恼鬼`）⇒ 同队其它召唤物把她当"攻击主人召唤物的侵略者" ⇒ 整队转过来打她 ⇒ 她反击 ⇒ **自激循环** |
| 为什么两个方向都不掉血 | `IMagicSummon#shouldIgnoreDamage` → `DamageSources.isFriendlyFireBetween`：**把双方都替换成召唤者**（都是女仆）⇒ `maid.isAlliedTo(maid)` 为真 ⇒ 友伤忽略 |
| 为什么 `isAlliedTo` 也拦不住 | `SummonedZombie#isAlliedTo` 只额外认「同一个主人的别的召唤物 / OwnableEntity」——**主人自己**不在其中（玩家侧也用不上它） |

### 3.31.2 修法（三件，缺一件都不完整）

| # | 做的事 | 位置 | 为什么在这里 |
|---|---|---|---|
| 1 | **堵因**：她自己的东西（她自己 / 自家召唤物 / 自家仆从 / 同阵营）**永远不算敌人** | `TaskPlayerLikeCombat#canAttackBase` | TLM 的 `EntityMaid#canAttack` 是"写目标 / 清目标 / 保留目标"**三条路径的汇合点** ⇒ 一处生效＝三处生效；而且 `StopAttackingIfTargetInvalid` 的 `erase()` 是**无条件**的 ⇒ 存量目标下一 tick 自动松开（不必手动清，清反而抢时序） |
| 2 | **判据**：`FactionRules`（纯逻辑，四个布尔事实 → 可读理由）+ `MaidAllies`（compat 侧查四个事实） | `decision` / `compat.maid` | 判据不含 MC 类型 ⇒ **可离线断言**；四个事实 = 她自己 · `isAlliedTo` · `OwnableEntity#getOwner()==她` · 铁魔法 `IMagicSummon#getSummoner()` / Goety `IOwned#getTrueOwner()`（后两个委派给各自被把守的 compat 类） |
| 3 | **修缮存量**：已被设成"目标是女仆"的召唤物 ⇒ 一秒一次清掉（`setTarget(null)` + 清 `ATTACK_TARGET` 记忆）并留痕 | `SummonAllyGuard`（挂 `DirectiveTicker`） | 对方的目标行为**不会因为我方判据变化而清目标**（`GenericProtectOwnerTargetGoal` 只在 `canUse` 时写，松手不会替它清）⇒ 不修的话"打她"这件事会一直挂着。★ **刻意一秒一次**：不做成每 tick 抢写（同一个字段两个写入者 = 拉锯），而它们本来就伤不到她 |

★ 顺带：`enemies_nearby`（态势事实）现在也**不再把自家召唤物算成敌人** —— 玩家侧的"附近敌人"本来就不含自家宠物（否则谨慎度会被自己人抬高）。

### 3.31.3 验收

| 断言 | 位置 |
|---|---|
| 四种"自家"情况都被认出来，且各有**可读理由** | `DirectiveSelfTest §5`（新增 4 条） |
| 普通敌人（四条都不成立）⇒ 仍可当敌人（**不许把所有东西都当友军**） | 同上 |
| `isFriendly` 与 `friendlyReason` 同口径（两个出口不许漂移） | 同上 |
| 目标判据的其余口径（指令筛选 / 冷却 / 候选集）未受影响 | 套件 23 步全过 |

---

## ★ 3.32 第十七轮续（十五）：**性能审计（"一卡一卡的"）** + **物品分类与弹药**

委托方两条（原话）：

> 1.「该模组带来了很大的性能负担，一卡一卡的，你看一下有没有**不动核心、不出 bug** 的优化方式。」
> 2.「在女仆可获取物品信息的部分，在信息处分类：即从『物品栏里有：A、B、C』到
>   『物品栏内有：拔刀剑相关：A、B；枪械相关：C、D（**每个枪支消耗的弹药种类，以及拥有的
>   弹药及多少（检测弹药箱）**也要写在里面，记得**创造弹药盒**和**全类型创造弹药盒**这些特殊案例）』」

### 3.32.1 性能：每 tick 路径上的三类真开销（逐条取证）

| # | 病灶 | 为什么贵 | 修法（**语义不变**） |
|---|---|---|---|
| 1 | `IronsServantOps.countSummons` | 走 `level.getAllEntities()` = **整个维度**的实体表，而它每 tick 被 `MaidSnapshot.facts` 问一次 | 改用**铁魔法自己的索引** `SummonManager.getSummons(owner): Set<UUID>`（反编译取证：`ownerToSummons` 按主人 UUID 索引）⇒ O(她有几只)；对外入口 `countSummonsIndexed`，而"处死全部召唤物"那条指令仍走全量枚举（**必须尽量不漏**） |
| 2 | `customFacts` 的 5 个探针 | 每个都要**枚举她的全部物品**（主副手/盔甲/背包 36 格/饰品）并读各模组 NBT（魔杖里有没有聚晶、法术容器、回溯聚晶、妖刀、刀技），每 tick 一遍 ⇒ 5 遍 | `TickMemo` 按 **0.5 秒 TTL** 缓存整组事实；★ 执行期不受影响（真施法前执行器还会再判一次） |
| 3 | `SlashBladeTicker#feedItemSnapshot` | 每 tick **生成一次完整物品清单**（每件物品：枪身份反射 + 类型名继承链 + 属性/附魔 + 显示名），只为与上一版比对"变没变" | 先算 **`MaidSnapshot.itemFingerprint`**（只读 Item identity/数量/耐久/NBT-hash）⇒ 没变直接返回；变了才做那套翻译。★ 指纹覆盖范围与 `possessed` **逐格一致**（否则漏报） |
| 4 | `isWeapon(maid, stack)` → `isArmed(maid)` | **TLM 会对她身上每一件东西问一次**，而它做的是完整快照 + 5 个探针 + 2 次载体解析 | 按「物品指纹 + 1 秒」缓存；她换了装备立刻重算 |
| 5 | `loadedMods()` / 冷却候选 id 清单 | 每 tick 新建 HashSet（模组表）/ 重建 77 条 id 清单 | 各缓存一份（模组表启动后不变；清单只在热重载换实例时失效） |
| 6 | 索敌扫描（`tickTargeting`） | 每 tick 一次半径 24~40 的实体查询 + 逐个实体判"能不能打" | 节流到 **1/4**（0.2 秒）—— 只节流**扫描**，不节流"已有目标的保留" |
| 7 | `SummonAllyGuard`（上一轮新加的） | 每 20 tick 一次半径 40 的 `Mob` 查询，**哪怕她一只召唤物都没有**（最常见情况） | 先问"她有没有召唤物"（走索引 + 2 秒缓存），没有 ⇒ **零开销** |

★ 顺手把 `possessed()` 的每件物品翻译降下来：`typeNames` / `itemTags` / 属性能力**按物品类缓存**
（它们只取决于 `Item` 类，与 NBT 无关 ⇒ 缓存永远安全）；附魔那一半**不缓存**（写在 NBT 上）。
★ **什么不许缓存**（写在 `TickMemo` 的类注释里）：她这一拍就能感觉到的量 —— 与目标的距离、她的血量、
在途动作状态、指令总线内容。

★ 仍然留着的一个可选项（**本轮刻意没做**，因为要动 `Ctx` 的结构）：两个 `CarrierResolver.resolve`
每 tick 各跑一次，而决策循环自己是节流的（`decisionInterval`）⇒ 理论上可以把解析改成"按需计算"。
风险是 `Ctx` 同时被三个桥（JEV/顾问/LLM）与步法消费，改懒加载要连它们的签名一起动 ⇒ 记在这里作为下一步。

### 3.32.2 物品分类 + 每支枪的弹药（委托方第 2 条）

**分类判据（不猜，用已有的权威分类）**：

| 类别 | 判据 | 出处 |
|---|---|---|
| 枪械 | `GunCommonUtil.isGun`（TLM 公开抽象，**同时覆盖 TaCZ 与卓越前线**）| 编译期可用 |
| 弹药 / 弹药盒 | TaCZ：反射 `IAmmo` / `IAmmoBox`；卓越前线：注册名（它的枪与弹药**就是普通物品**）| 见下 |
| 拔刀剑 / 魔杖 / 法术书 / 近战 / 弓弩 / 盾 … | **命中她的那条最专精的载体规则**（`carriers.json` 的 `carrier` 字段） | 与解析器的"专精度抑制"**同一口径** |

**弹药那一句（`AmmoInfo`）——取证结论（`_scratch/ammo_research/FINDINGS.md`）**：

- **TLM 没有任何弹药 API**（`getAmmoCount/getAmmoType/isAmmo` 全 jar 不存在），而且它自己的换弹路径
  `TacInnerCompat#performGunAttack` 的 `NO_AMMO` 分支**只扫散装 `IAmmo`、完全不认弹药盒**
  （1817 个反编译文件里 `IAmmoBox|AmmoBox|CreativeAmmo` 命中 **0**）
  ⇒ 委托方点名要的"弹药箱检测"**只能我们自己做**。
- **TaCZ**（反射；1.1.7 与 1.1.8 API 逐字相同）：
  枪→弹药种类 `TimelessAPI.getCommonGunIndex(gunId).get().getGunData().getAmmoId()`；
  散装 `IAmmo#isAmmoOfGun(gunStack, ammoStack)`（★ **枪在前**）；弹药盒 `IAmmoBox`
  （**没有静态工厂** ⇒ `Class.isInstance`）；
  ★ `getAmmoCount` 在创造变体上返回 **`Integer.MAX_VALUE`**（哨兵值）；
  计数**照抄 TaCZ 自己的权威算法** `GunHudOverlay.handleInventoryAmmo`
  （散装累加 + 弹药盒累加 + 遇到创造变体直接"无限"）。
- **卓越前线**：它自己把答案做好了 —— `GunData.from(stack).countBackupAmmo(entity)`
  （含背包与弹药盒）与 `hasInfiniteBackupAmmo(entity)`；创造弹药盒是**独立物品**
  `superbwarfare:creative_ammo_box`。
- **Pillager's Gun**：引擎里**没有弹药盒、也没有"无限弹药"概念**，且 TLM 不认它的枪
  ⇒ 只做"尽力而为"的散装计数（`GunItem#getAmmo()`）。
- **输出口径（一处写死）**：
  <code>弹药 7.62x39mm：备弹 128 发（弹匣内 30；含弹药盒 60）</code> /
  <code>弹药 5.56x45mm：无限（创造弹药盒）</code> /
  <code>弹药 火箭弹：无限（全类型创造弹药盒）</code> /
  拿不到 ⇒ <code>弹药：未知（读取失败）</code>（**绝不静默留空**）。
  ★ **绝不打印 `Integer.MAX_VALUE`** —— 那是 API 的哨兵值，不是"她有两亿发"。

**落到哪里**：`flp_items` 上下文（女仆自己的物品清单，惰上下文）现在输出**按类别分行**的清单
（每行仍是 `id(名字)x数量@槽位`，枪再多一句弹药）；指挥通道的 `scene.equipment.items[]`
也在枪的名字后面补了同一句（`conserve_ammo` / "改用近战" 这类判断本来就依赖它）。

### 3.32.3 验收

| 断言 | 位置 |
|---|---|
| `TickMemo`：窗口内只算一次 / 过 TTL 重算 / 不同 key 互不影响 / `ttl<=0` 不缓存 / `invalidate` 立刻重算并被记住 | `DirectiveSelfTest §5`（新增 6 条） |
| `ItemGroups`：三个枪包同类 / 载体 id 直接当类别 / 认不出的落「其它」/ 枪在最前 / 每个类别都有中文名 / 弹药与弹药盒分开 | `DirectiveSelfTest §5`（新增 6 条） |
| 既有语义一条都不许变（候选集、指令过滤、目标判据、回放节奏） | 套件 **23 步全过**（226 断言 + 游戏侧回放 150） |

---

## ★ 3.33 第十七轮续（十六）：**「她不认为魔剑阎魔刀是拔刀剑」——提示词与候选集口径打架**

委托方实测（女仆原话，日志 `latest.log` 03:34:16 原文）：

> 主人（T0NIaT0）：「很好，接下来进行拔刀剑能力测试。**你仅可使用拔刀剑**应对接下来的敌人。」
> 女仆（大正女仆酒狐）：「主人~ 可是我现在**手头没有可用的拔刀剑**呢，仓库里的魔剑「阎魔刀」
> **好像不算拔刀剑**哦，用不了刀的话拔刀技也没法放出来……我该怎么办呀？Uwu~」

### 3.33.1 两条口径在打架（这是"她不知道"的真正原因）

| 层 | 说的事 | 对不对 |
|---|---|---|
| 每回合的上下文 `flp_blade` | 原来写的是 `isUsableBlade(maid)` —— **只看主手** ⇒ 刀在背包里时输出「**No usable blade in hand**」 | ★ 作为"手上有没有"是对的，**作为"她能不能放刀技"是错的** |
| 物品清单 `flp_items`（本轮新加的） | 把同一把刀列在「**拔刀剑**：…」类别里 | ✅ 对 |
| 决策层（真正决定她做什么的那一层） | `LoopSplit.mainItems` **含背包/饰品** + 执行器的**换手前置** ⇒ 刀在背包里**照样**能放刀技 | ✅ 对 |

⇒ 她同时看到"没有可用的刀"和"这是拔刀剑"，**没人告诉她怎么调和** ⇒ 她自己补了一个解释
（「好像不算拔刀剑」），而主人把这句话当成了事实。

★ 这正是 docs/13 第 18 条的纪律（**解析期按"拥有物"判定，执行期才看主手**）—— 我只把它落到了
**代码**上，没有落到**提示词**上。修法：

1. `flp_blade`：报三件事 —— ① 手上有没有；② ★ **身上别处有没有（在哪个槽位）**，
   并明写「执行器会自动把它换到手上，**所以这不是障碍**」；③ 那把刀的**状态**（损坏/封印/刀技/耀魂），
   ★ 状态按**实际那把刀**读（不是"在手上那把"）；
2. `flp_phantom_sword`：三条前置（妖刀 / 力量附魔 / 耀魂）**按拥有的刀**逐条判，
   不在手上时补一句「这不是障碍（会先换手）」，**不再输出 "no usable blade in the main hand"**；
3. **每回合的硬规矩 11**（`flp_command_rules`）：**「她能用什么，按【身上有没有】算，不按【在不在手上】算」**
   + 「上下文说 'not in hand' 而物品清单里有 ⇒ **就是她有**」+ 「**绝不许自己编解释**（例如"那把不算拔刀剑"）」；
4. 技能正文「五、先看她有什么」同一条（含"回答主人时要怎么说"）；
5. 指挥通道的场景 `equipment.has_melee_weapon` 也在同一处犯了这个错（`hasUsableBlade` 只看主手）
   ⇒ 改成按**拥有**的刀判（`slashBladeLike`，与载体规则同一口径）。

### 3.33.2 顺带查出的第二个坑：刀的身份（与枪同型，第 79 条纪律的第二次发作）

反编译 + `javap -c` 取证（`SlashBladeResharped-1.20.1-1.9.65.jar`）：

```
SlashBladeDefinition 的 `item` 字段默认值 = slashblade:slashblade
  ⇒ **所有具名刀共用同一个物品注册名**（阎魔刀、无名刀、白鞘……全都是它）
真正的身份在刀状态里： ISlashBladeState#getTranslationKey() = "item.slashblade.yamato"
  ⇒ 解析成身份 id 就是 slashblade:yamato   （翻译键格式 item.<namespace>.<path>）
```

⇒ 与 TaCZ 的枪（所有现代枪共用一个注册名 + 身份在 NBT 的 GunId）**完全同型**。
修法与枪一致：

| 位置 | 改法 |
|---|---|
| 物品身份 | `MaidSnapshot#itemId`：刀 ⇒ **刀自己的 id**（`slashblade:yamato`），读不到才退回注册名 |
| 解析（纯逻辑） | `ItemIdentity#fromTranslationKey`（`item.slashblade.yamato` → `slashblade:yamato`；认不出 ⇒ `null` **不猜**） |
| 别名 | 注册名 `slashblade:slashblade`（**老写法照样命中**）、刀名 `yamato`、中文名「魔剑「阎魔刀」」（拆词） |
| 玩家可查 | ★ 新增 **`/flp items <女仆>`**：把**她自己看到的那份清单**（同一份 `MaidItems.describe`，不是另写一遍）＋每把刀的身份/槽位/在不在手上打出来 ⇒ 以后"她为什么认成那样"当场对账 |

### 3.33.3 验收

| 断言 | 位置 |
|---|---|
| 翻译键 → 身份 id（含"已经是 id"与"认不出 ⇒ null"两条边界） | `DirectiveSelfTest §5`（新增 4 条） |
| 既有语义不变（候选集/指令/目标判据/回放节奏/物品分类） | 套件 23 步全过（230 断言 + 游戏侧回放 150） |

---

## ★ 3.34 第十七轮续（十七）：**「仅使用先锋聚晶施法」下不下去 —— 指令表缺一条**

委托方原话：

> 「女仆 llm 获取巫法聚晶 id 的功能似乎有 bug。女仆虽然能获取自己有哪些聚晶，
> 但无法通过 id 获取自己有哪些聚晶，同时因为 id 的 bug 无法针对其下指令。我是测试先锋聚晶发现的。」

### 3.34.1 日志实证（委托方那次测试的原文）

```
04:05:29  主人：我需要你在接下来的一段时间内仅使用先锋聚晶施法，你测试你的召唤类法术使用能力。
04:05:30  [FLP][chat] 对话 LLM 下达指令 only_item params={} texts={item=goety:vanguard_focus}
04:05:30  [FLP][directive] 指令伺服：「goety:vanguard_focus」她身上没有 ⇒ 换不了手（5 秒后会自动解除）
04:05:31  女仆：我身上没有「先锋聚晶」这件东西呀，只有无名魔杖和其他聚晶，所以这条限制没法生效。
04:06:17  女仆：我有的聚晶是：灵魂束、风刃、冰刺、冷静、治疗、先锋、壁垒和暗卫聚晶，一共8颗哦！
          先锋聚晶就在聚晶包里，刚才是我写错了 ID～
04:06:44  女仆：先锋聚晶在我聚晶包里，可是它的真实 ID 好像不是我以为的那个，我没法精确锁定这一颗呢。
```

### 3.34.2 两件事（都不是"她看不出有哪些聚晶"）

| # | 事实 | 为什么坏 |
|---|---|---|
| 1 | **指令表里没有"只用某一颗法术/聚晶"这一条** —— 只有 `focus_category`（只用某一**类**）与 `ban_focus`（禁用某一**颗**） | 模型不会说"我做不到"，它**借了最像的那条** `only_item`，并给了一个形似的参数 |
| 2 | **`only_item` 结构上指不到聚晶包里的聚晶** | `only_item` 的参数是**物品 id**，而 `MaidSnapshot.possessed` 只枚举**独立物品**；聚晶装在聚晶包/多晶大袋/魔杖里 ⇒ 它不是一个物品 ⇒ 伺服只能回「她身上没有」⇒ 5 秒后自动解除（她完全不知道为什么） |
| 3 | ★ 顺带查出的**假否定** | 若拿**有门**的枚举去核对参数，会得出"她没有这颗"：**先锋聚晶是 SUMMON 类，而她的召唤位是满的**（日志 `召唤位已满 ⇒ 跳过召唤聚晶：VanguardSpell`）⇒ **说错比不说更糟** |

### 3.34.3 修法

| # | 改什么 | 落在哪 |
|---|---|---|
| 1 | 新增指令 **`only_focus`（只用某一颗法术/聚晶）**，参数与 `ban_focus` **完全同一口径**（`label`） | `DirectiveSpec`（表 + needs）→ 自动进 LLM 清单/命令/工具枚举 |
| 2 | 过滤落两处：**标签层**在 `DirectiveFilter#focusAllowed`（挑选池；`cast_focus`/`cast_spell` 是族动作，具体放哪颗在执行期挑）；**手段层**在 `allowed`（与 `magic_only` 同口径：非施法动作一并挡下） | `DirectiveFilter`（新增 6 条断言） |
| 3 | 她的法术清单**开头就写明**「针对某一颗用 `only_focus`/`ban_focus` + 下面每行的 `label=`；**不要写物品 id**（聚晶常在聚晶包里，不是独立物品）」 | `FlpMaidContexts#SpellsContext` |
| 4 | 每回合硬规矩新增第 12 条（同一件事，放在"每回合都看得见"的地方） | `flp_command_rules` |
| 5 | 技能正文新增「五点四九」专章（怎么查、怎么下、写错了会怎样） | `skill.md` |
| 6 | **下达那一刻的参数核对**：`only_focus`/`ban_focus` 的 label 不存在 ⇒ 回话里**列出她实际有的 label**；若她有这颗但**现在放不出来**，说清原因（召唤位满 / 被她自己的指令挡着） | `DirectiveHolder#feasibility` + `notCastableReason` |
| 7 | **"有没有"用无门枚举**：`GoetyFocusOps#ownedLabels` / `IronsSpells#ownedLabels`（不看召唤位、不看指令），与"放不放"分开 | compat 两个执行器类 |

★ 新增纪律 BS：**"缺一条指令"会被模型用最像的那条去凑** —— 判据是
**"用户这句话，指令表里真有一条对应吗？"**；并且 **"她有没有"与"她能不能用"必须分开问**。

### 3.34.4 验收

| 断言 | 位置 |
|---|---|
| `only_focus` 放行那一颗、挡下别的法术；非施法动作一并挡下；**精确**比较；没带 label 时不过滤（护栏） | `DirectiveSelfTest §5`（新增 6 条） |
| `only_focus` 在指令表里、声明了 needs、参数名与 `ban_focus` 一致 | 同上 |
| 既有语义不变 | 套件 23 步全过（236 断言 + 游戏侧回放 150） |

---

## ★ 3.35 第十七轮续（十八）：**「枪械用不出来」—— 两条指令的交集是空的**

委托方原话：

> 「你的修复把之前的功能炸了：现在枪械用不出来。」

### 3.35.1 日志实证（委托方那次测试）

```
close_in  ← llm ：已下达持续指令 close_in（贴身缠斗）max=3     ← ★ 动态指挥官下的（按设计**禁用远程**）
melee_only← llm ：已下达持续指令 melee_only                    ← 指挥官也下了这条（她自己取消了）
only_item ← chat：已下达持续指令 only_item  item=tacz:uzi      ← ★ 主人下的（只留"由它承载"的动作）
[FLP] [directive] main 被指令挡下 4 条：tacz:shoot, maid_native:bow_shot, maid_native:trident, 物品指令生效：tacz:uzi
[FLP][main] → fight_like_player:disengage                     ← ★ **唯一活着的候选是"撤退"**
```

★ 两条指令**各自都合理**：`close_in`（贴身）禁远程是设计如此；`only_item(tacz:uzi)`（只用乌兹）
禁掉别的一切也是主人的意思。坏的是**组合** —— 交集为空。

### 3.35.2 为什么护栏没拦住

"绝不允许候选集变成空的"是本项目的**头号纪律**（docs/13 第 26 条），但它此前只做在
**单条指令内部**：物品 ABSENT 回退、白名单看门狗、"过滤为空就回退"。
**跨指令组合这一层没人管** ⇒ 空集只是换了个地方出现：这里不是"她站着不动"，
而是"**她只剩撤退**"（`disengage` 是 MOVE 类，两条指令都不挡它）。

★ 而且这次是**两张嘴同时在指挥**：主人在测冲锋枪，动态指挥官却在按自己的判断下"贴身 + 只用近战"。

### 3.35.3 修法

| # | 做什么 | 落在哪 |
|---|---|---|
| 1 | **组合体检**（只在**下达指令那一刻**跑一次，不是每 tick）：用与决策层同一条路径算出"过滤之后还剩几个能打的动作"（MELEE/RANGED/SPELL）；一个都不剩 ⇒ 触发让位 | `PlayerLikeCombat#attackCandidatesLeft` + `DirectiveHolder#resolveConflict` |
| 2 | **谁让位**（纯逻辑，可离线断言）：主人的 `player`/`chat` **永不让位**；先撤 `llm` 的**站位约束**（`close_in`/`keep_distance`/`hold_position`/`no_retreat`），再撤它的**手段限制**（`*_only`/`only_focus`）；主人的"指名道姓"（`only_item`/`ban_item`/`only_focus`/`ban_focus`/`focus_one`/`focus_entity`/`ban_entity`/`only_actions`）**一律不动**；**全是主人下的 ⇒ 一条都不自动撤，只报告** | `decision/ConflictResolver` |
| 3 | **可读出口**：让位时把"我自动撤了 X（来自 llm 的站位约束），主人的指令优先"写进回话 ⇒ 她能转述给主人，而不是"只会撤退" | `DirectiveHolder#resolveConflict` |
| 4 | ★ **"不知道"≠"真的没有"**：清单没加载 / 没在打架（没有目标）⇒ 体检返回"未知"并**跳过**（否则会在战斗外误撤指令） | 同上（返回 `null`） |

★ 这与第 83 条那条老纪律同源（**动态指挥撤不掉主人下的指令**）——
现在把它从"**取消**"扩展到"**冲突让位**"：主人说的话，优先级最高。

### 3.35.4 验收

| 断言 | 位置 |
|---|---|
| 组合冲突撤掉指挥官那条；先撤站位、再撤手段；全是主人的 ⇒ 不自动撤；主人的指名道姓永不撤；来源优先级 | `DirectiveSelfTest §5`（新增 6 条） |
| 既有语义不变 | 套件 23 步全过（242 断言 + 游戏侧回放 150） |
