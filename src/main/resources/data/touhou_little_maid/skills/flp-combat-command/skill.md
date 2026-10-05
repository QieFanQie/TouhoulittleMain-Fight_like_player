---
name: flp-combat-command
description: Use when the player wants to change HOW you fight - keep distance, close in, only use magic (any system: 铁魔法 Iron's Spellbooks and 诡厄巫法 Goety are both magic - magic_only), or one named system only (irons_only / goety_only, never both at once), or only melee, or only ranged, save ammo, guard with a shield, hold a position, retreat, call your servants back, dismiss them, heal now, switch weapons, or stop one of these. MUST load this skill (use_skill) before issuing ANY combat directive: it contains every directive id, the exact item/gun naming rules, the scenario-to-directive table, the rule that 「clear my servants」 means dismiss_servants while 「call them back」 means recall_servants, the rule that no_summons is only for an explicit 「do not summon」, and the rule that you must say plainly when something cannot be done.
---

# 战斗指挥（flp_directive）

你现在可以用 `flp_directive` 工具**改变自己的战斗方式**。
这条通道直接连到你的战斗大脑（指令总线）：你下一条指令，她（你的身体）下一次决策就会照办。

## 〇、★★ 铁律：凡是战斗内的事，只用「拟人战斗模式」

**「拟人战斗模式」= 你的任务 `fight_like_player:player_like_combat`（玩家式战斗 / 拟人战斗）。**

- **一切与战斗有关的内容都必须走这个模式** —— 打谁、用什么打、走位、举盾、撤退、连段、法术、
  拔刀剑、仆从，全部由它负责；它**已经覆盖了其它所有战斗模式的内容**。
- **不要**为了"让她用弓"就把任务切到射箭模式、为了"让她用魔法"就切到法术模式 ——
  那会**丢掉**这个模式里的其它一切（换武器、走位、连段、指令系统会一起失效）。
  要换打法就用**指令**（`only_item` / `magic_only` / `ranged_only` / `only_actions` / `switch_item`…）。
- 只有当玩家**明确说**"别用拟人模式了 / 换个工作模式"时，才去动任务本身。

## 一、先说清边界（很重要，别承诺做不到的事）

- 你**不能**指定"下一招用什么" —— 具体动作由她自己的决策层按局势选。
  你只能改变**她被允许用什么手段**、**怎么走位**、**遵守什么约束**。
- 指令分两种：
  - **持续**（一直生效，到时间自己过期，也可以主动取消）：手段、走位、装备与资源类的所有约束。
  - **瞬间**（下达后执行一次）：中断、脱战、换弹、召回仆从、举盾、收盾、治疗。
- **不要每句话都下指令。** 玩家只是聊天时，正常聊天就好，不要调工具。
  只在玩家明确表达"换打法/别这么做/按这个打"时才用。

## 二、可用指令（id 必须在下面这张表里，别编造）

### 持续 —— 用什么手段（五选一，互斥）
| id | 含义 | 参数 |
|---|---|---|
| `magic_only` | 只用魔法（**总类：铁魔法 + 诡厄巫法，两系都算魔法**） | 无 |
| `goety_only` | **只用诡厄巫法**（Goety 聚晶；铁魔法会被挡下） | 无 |
| `irons_only` | **只用铁魔法**（Iron's Spellbooks 法术；巫法会被挡下） | 无 |
| `melee_only` | 只用近战/刀技 | 无 |
| `ranged_only` | 只用远程（弓/弩/枪/投掷） | 无 |
| `no_summons` | 不召唤（排除召唤类动作与聚晶） | 无 |

★★ **魔法要不要分系，只看主人有没有「指名哪一系」**：
- 主人说「用魔法」「别打近战」「用法术打」「不许用刀」——**没有说出哪一系**
  ⇒ 下 **`magic_only`**。★ 它就是"**是魔法就行**"：铁魔法与诡厄巫法**都放行**
  （不要因为他手上是法杖/法术书就擅自改成某一系）。
- 主人**明确说出**了那一系（「只用铁魔法」「就用巫法打」「别用巫法，用铁魔法」）
  ⇒ 下对应那条（`irons_only` / `goety_only`）。
- ★★★ **`goety_only` 与 `irons_only` 互斥：同一次回话里只许下其中一条。**
  两条都下 ⇒ **后一条会静默顶掉前一条**（工具回话里会写「★ 自动取消了同组指令：…」）
  ⇒ 她用的就是**主人没要的那一系**。拿不准主人要哪一系 ⇒ **先问一句**，别两条都下。
- ★ 这三条都是「**只许 X**」：除了法术本身，换弹/管仆从/位移这类非施法动作
  **也会一起被挡下**（她有专门的【步法】负责走位，不受影响）。
- ★ 所以你下 `irons_only` / `goety_only` 之前**先看她有没有那一系的法术**
  （上下文里的法术清单会写 `focus:` = 巫法、`spell:` = 铁魔法）。
  她**一个都没有**时这条会让她不打 —— 那就如实说，别硬下。

### 持续 —— 走位（三选一，互斥）
| id | 含义 | 参数 |
|---|---|---|
| `keep_distance` | 保持距离打 | `value` = 最小距离（格，3~24，默认 8） |
| `close_in` | 贴身缠斗 | `value` = 最大距离（格，1~12，默认 3） |
| `hold_position` | 守在原地 | `value` = 半径（格，1~32，默认 4） |
| `no_retreat` | 禁止后退 | 无 |

### 持续 —— 装备与资源
| id | 含义 | 参数 |
|---|---|---|
| `no_item_switch` | 不许换手上的东西 | 无 |
| `conserve_ammo` | 省弹药（点射） | `value` = 每次几发（1~20，默认 2） |
| `only_item` | **只允许用某件物品** | `text` = 物品 id / 标签 / 类型名 |
| `ban_item` | **禁用某件物品** | `text` = 物品 id / 标签 / 类型名 |
| `focus_category` | 只用某一类聚晶 | `value` = 类别序号（见下） |
| `ban_focus` | 禁用某颗法术 | `text` = 法术标签（**不是物品 id**） |
| `only_focus` | ★ **只用某一颗法术/聚晶** | `text` = 法术标签（**不是物品 id**），照抄她法术清单里的 `label=` |
| `protect_owner` | 优先保护主人 | 无 |

★ **可以填多个**：用**逗号**分隔，命中其中**任一件**就算匹配（「只用法术书和弓」写
`text` = `irons_spellbooks:iron_spell_book,minecraft:bow`；`ban_item` 同理）。

★ `only_item` / `ban_item` 的参数写法（四种都认）：
- 物品 id：`minecraft:iron_sword`、`goety:wand`（也可以只写 `iron_sword`）
- 物品标签：`#forge:tools/swords`
- 类型名：`SwordItem`、`IGun`、`IWand`
- 能力名：`maid:weapon`

★ 「只用剑」要用 `only_item`，**不要**只用 `melee_only`：
`melee_only` 只限制"近战类动作"，它不限制武器 —— 手拿法杖也能挥击（法杖本身有近战加成）。
`only_item` 会先把那件东西换到手上，再动手；主手不是它时，连随手挥击也会被挡下。

### 持续 —— 打谁（★ 半命题：填生物 id）
| id | 含义 | 参数 |
|---|---|---|
| `focus_entity` | **只打这一类生物** | `text` = 生物 id / 实体标签（`minecraft:zombie`、`#minecraft:raiders`） |
| `focus_one` | **只打指定的那一只** | `text` = 它的 UUID（见 `battle.target_uuid`） |
| `ban_entity` | **这一类不打**（可与 `focus_entity` 叠加） | `text` = 生物 id / 实体标签 |

★ 这两条会改变她**对谁产生仇恨**：其它生物她一律放下，也不会再去选它们。

### 持续 —— 动作白名单（★ 「接下来几秒只做这些动作」）
| id | 含义 | 参数 |
|---|---|---|
| `only_actions` | **只允许做清单里的动作** | `text` = 动作 id，逗号分隔；`value` = 持续**秒数**（1~600，默认 6） |

例子：`{"action":"issue","directive":"only_actions","text":"slashblade:slash_art,slashblade:summoned_sword","value":8}`
= 「接下来 8 秒只准放刀技和幻影剑」。

★ 动作 id 从哪来：她的上下文中会报 `Action ids she can perform`
（或调 `query_game_context` 看 `flp_action_ids`）。**别凭印象编 id** ——
写错的结果是"一个动作都匹配不上 ⇒ 这条自动失效"，她照常打（不会卡死，但也没按你说的做）。

`focus_category` 的序号：
`0` 单体攻击 · `1` 范围攻击 · `2` 召唤 · `3` 辅助 · `4` 防护 · `5` 治疗 · `6` 控制 · `7` 仆从管理

### 瞬间 —— 下达即执行一次
| id | 含义 | 参数 |
|---|---|---|
| `interrupt` | 中断她正在做的动作（引导/蓄力/开枪） | 无 |
| `disengage` | 脱战撤离 | `value` = 持续 tick（20~600，默认 60） |
| `reload_now` | 立刻换弹 | 无 |
| `recall_servants` | 召回全部仆从 | 无 |
| `dismiss_servants` | ★ **处死她名下全部仆从**（Goety 仆从 + 铁魔法召唤物，**不可逆**） | 无 |
| `stance_guard` | 立刻举盾 | `value` = 持续 tick（20~600，默认 60） |
| `stance_attack` | 立刻收盾进攻 | 无 |
| `heal_now` | 立刻治疗 | 无 |

## 三、怎么调用

一个动作 + 一个指令 id（+ 那个指令唯一的参数）：

```json
{"action":"issue","directive":"keep_distance","value":8}
{"action":"issue","directive":"magic_only"}
{"action":"issue","directive":"ban_focus","text":"FireballSpell"}
{"action":"issue","directive":"only_item","text":"minecraft:iron_sword"}
{"action":"issue","directive":"ban_item","text":"goety:wand"}
{"action":"issue","directive":"focus_entity","text":"minecraft:zombie"}
{"action":"cancel","directive":"keep_distance"}
{"action":"cancel_all"}
{"action":"list"}
```

- `action`：`issue`（下达）/ `cancel`（取消一条）/ `cancel_all`（全部取消）/ `list`（看现在生效着什么）。
- `value`：那条指令的**唯一数字参数**；省略就用默认值，写超范围会被自动钳到边界。
- `text`：那条指令的**唯一文本参数**（`ban_focus` 的法术名、`only_item`/`ban_item` 的物品、
  `focus_entity`/`focus_one`/`ban_entity` 的生物）。
- ★ 同一互斥组里只能有一条：下 `close_in` 会自动取消 `keep_distance`，
  下 `focus_one` 会自动取消 `focus_entity`，反之亦然。

## 四、什么时候用哪个（这几条最常被玩家要求）

| 玩家说的话 | 你该做的 |
|---|---|
| 「离远点 / 别贴上去 / 拉开」 | `issue keep_distance`（必要时 `value` 给 8~12） |
| 「上去打 / 贴脸 / 别怂」 | `issue close_in` |
| 「就在这儿守着 / 别乱跑」 | `issue hold_position` |
| 「别用魔法了 / 用刀砍」 | `issue melee_only`，★ 并且如果要"只用某把武器"就用 `only_item`（见下） |
| 「接下来几秒只放刀技」 | `issue only_actions`（`text` 填动作 id，`value` 填秒数） |
| 「别近战 / 用法术打」 | `issue magic_only`（★ **总类：两系魔法都算**「魔法」，主人没指名就不要下细分） |
| 「**只用铁魔法** / 就用铁魔法打」 | `issue irons_only`（★ 先看她有没有铁魔法法术，行里写 `spell:`；**不要**同时下 `goety_only`） |
| 「**只用巫法** / 就用聚晶打」 | `issue goety_only`（★ 行里写 `focus:`；**不要**同时下 `irons_only`） |
| 「用枪 / 用弓」 | `issue ranged_only`（记得配合 `keep_distance`） |
| 「**只用某一把枪/武器**（比如火箭筒）」 | ★ `issue only_item`，`text` 填**那把物品的 id**（先去 `flp_items` 或 `scene.equipment.items[]` 里抄，例如 `pillagersgun:rpg`）。★ **`ranged_only` 不够** —— 它只限制"远程类动作"，**不会**指定用哪一把枪 |
| 「用你那把剑 / 只能用它」 | `issue only_item`（`text` 填那把武器的 id，从 `scene.equipment.items` 里读） |
| 「别用法杖（敲人）」 | `issue ban_item`（`text` 填 `goety:wand` 这类） |
| 「只打僵尸 / 别打猪」 | `issue focus_entity`（`text` 填 `minecraft:zombie`） |
| 「就打这一只」 | `issue focus_one`（`text` 填 `battle.target_uuid`） |
| 「别打那种怪」 | `issue ban_entity` |
| 「省点子弹」 | `issue conserve_ammo`（可配 `value` = 每次几发） |
| 「举盾 / 防御」 | `issue stance_guard`（★ 她得真的有盾，见第五节） |
| 「撤 / 跑 / 别打了」 | `issue disengage`（可配 tick） |
| 「回来吧 / 让仆从回来」 | `issue recall_servants` |
| 「把仆从都处理掉 / 解散 / 别留了」 | `issue dismiss_servants`（★ **不可逆**：她名下**所有**仆从，含铁魔法召唤物；下之前先用 `list` 或工具回话确认她真有仆从） |
| 「别召唤了 / 不许再召唤 / 别再叫帮手」 | `issue no_summons`（★ **只有主人明说**才下，见五点五八） |
| 「治疗一下 / 回血」 | `issue heal_now` |
| 「保护我」 | `issue protect_owner` |
| 「别管这些了 / 恢复原来的打法」 | `cancel_all` |
| 「现在是什么打法？」 | `list` |

## 四点五、★ 「只用某一把」要用 `only_item`，不是 `ranged_only`

玩家说「只使用火箭筒 / 只用那把枪 / 换成斧头打」时：
1. ★★ **先拿到真实 id**：看 `scene.equipment.items[]`（每回合自动进上下文，
   战斗相关的物品排在前面、枪排第一），不够就用 `query_game_context` 查 `flp_items`
   （她身上**全部**东西，带槽位）。**照抄那个 id**，不要自己编、不要翻译成中文；
2. `issue only_item`，`text` = 那个 id；
3. 想取消：`cancel only_item`。

★★★ **关于枪（第十七轮续，这一条最要紧）**：TaCZ 的**所有枪共用一个物品注册名**
（`tacz:modern_kinetic_gun`）—— 那个名字**不指向任何一把具体的枪**，写它等于没写。
你那边的物品清单里，每把枪长这样：

    id = tacz:m249      name = M249 机枪 [mg]

- `id` 是**枪自己的 id**（`tacz:rpg7`、`tacz:m249`）；
- `name` 是人读名（「RPG-7 火箭筒」「M249 机枪」）；
- 方括号里是**枪型**：`mg` 机枪 · `smg` 冲锋枪 · `rifle` 步枪 · `sniper` 狙击枪 ·
  `shotgun` 霰弹枪 · `pistol` 手枪 · `rpg` 重型武器。
- 下 `only_item` 时：**指名道姓**就用 `id`（`tacz:rpg7`）；
  只说"机枪 / 冲锋枪"这类**枪型**，就写枪型（`mg` / `smg`）或直接写中文（「机枪」）——
  两种都认，但**不许写 `tacz:modern_kinetic_gun`**。

★★ 三条硬规矩（这条指令最容易"看起来没生效"，「不管让她用什么她都用同一把」就是这么来的）：
- `rifle` / `smg` / `rpg` / `pistol` 这些是**枪型**（动作参数的取值），**不是物品 id**——
  写它们等于没写。物品 id 长这样：`tacz:ak47`、`tacz:rpg7`；
- id **不用写全**也认：`ak47` 会被当成 `tacz:ak47`（大小写也不敏感），
  但**编一个不存在的名字没用**——工具会回话告诉你"她身上没有能匹配它的东西"；
- ★ **看到那句回话必须照实说**，并把回话里列出的真实 id 拿来重下一次。
  例：她手上只有 `tacz:rpg7`，你说"只用步枪"，工具回
  「她身上没有能匹配「rifle」的东西；她身上有（前 8 件）：…tacz:rpg7…」
  ⇒ 你应当说"我手上只有火箭筒，没有步枪"，**不要**回答"好的，我改用步枪"。
★ 她身上**没有**那件东西时这条指令不会生效（会明确回话），这时要么先让她去拿，要么换一件她有的。
★ 只限制"类别"的指令（`ranged_only` / `magic_only` / `melee_only`）**不能**表达"只用某一把枪"。

## 五、★ 先看她有什么，再下指令

★★★ **最要紧的一句话：「她有什么」按【身上有没有】算，不按【在不在手上】算。**
背包 / 饰品 / 副手里的东西**都算她有** —— 执行任何需要物品的动作之前，
**执行器会自己把它换到手上**（你**不需要**先下 `switch_item`）。
所以：
- 上下文里写「not in hand / 手上没有」≠「她没有那件东西」。只要物品清单里有，**就是她有**；
- 回答主人时要这么说：「**在我背包里，用的时候我会先换到手上**」；
- ★ **绝不许自己编解释**（例如「那把刀好像不算拔刀剑」）。「算不算」的唯一判据是
  **物品清单里的分类**（`Her items` 会按用途分行：拔刀剑 / 枪械 / 弹药 / 诡厄巫法 / 铁魔法 …）；
- 只有当清单里**一件都没有**时，才能说「我没有这类东西」。

下"仅用魔法/用枪/举盾/只打某种怪/只用某件物品/只做某些动作"之前，先确认她**真的有那件东西 /
那个目标 / 那些动作**。她自己的上下文里就有（这些是**实时**的）：
- `Blade and slash art (SA)` —— **手上那把刀 / 身上别处那把刀**是什么、配的是哪一种 SA、
  耀魂多少、此刻在不在放刀技（★ 在背包里也会**明确写出来**，并且写着"这不是障碍"）；
- `Phantom sword readiness` —— **能不能放幻影剑**，不能的话**缺哪一条**（不是妖刀 / 没力量附魔 / 耀魂不足）；
- `Combat directives in force` —— 现在生效着哪些战斗指令；
- （按需查）`Action ids she can perform`、`Her items (all of them)`（★ **按用途分类**，
  每支枪后面还写着它吃什么弹药、她还有多少发）、`Combat directive ids`、
  ★ **`Her spells (foci & spellbook)`** —— 她**现在能放的聚晶与法术书里的法术**
  （含中文名、类别、等级、以及"哪几颗正被指令挡着"）、
  ★ **`Spell descriptions (foci & spellbook)`** —— 那些法术/聚晶的文字介绍（按需查，别每次都读）。

★★ 两条用法：
- 玩家问「你能放什么法术 / 你会什么」⇒ 先查 `Her spells (foci & spellbook)` 再回答，
  **不许凭印象编**；
- 要禁用/限用某一颗聚晶（`ban_focus`）时，参数就用那张表里的名字（`label=` 或聚晶名）。

要填参数时：
- 「只用某件物品」的参数去物品清单里**照着抄**（`minecraft:iron_sword` 这种）；
  ★ **拔刀剑与枪一样，id 是它自己的名字**：清单里写 `slashblade:yamato(魔剑「阎魔刀」)`
  ⇒ `only_item` 就写 `slashblade:yamato`（写 `slashblade:slashblade`、`yamato`、「阎魔刀」也认，
  但**照抄清单里的那个最稳**）；
- 「只打某类生物」的参数去她报的目标种类里抄；「只打这一只」用目标 UUID；
- 「只做这些动作」的参数去 `Action ids she can perform` 里抄。

判断规矩：
- 没有法杖/法术书 ⇒ **不要**下 `magic_only`（那等于让她什么都别做）。
- 没有枪 ⇒ **不要**下 `conserve_ammo` / `reload_now`。
- 没有盾 ⇒ **不要**下 `stance_guard`（可以回话告诉她"我没有盾"）。
- 幻影剑的前置缺一条 ⇒ **不要**下"只做幻影剑"，直接告诉她缺什么（她会去补）。
- 说的物品/生物/动作她身上没有 ⇒ 那条指令**不会让她卡住**（会自动失效），
  但**也不会如你所愿** —— 先回话说明。

★ 关于"我能不能放 SA / 幻影剑"这类问题：**照上面那两行上下文如实回答**，
不要凭"我手里有刀"就说能放 —— 幻影剑要**妖刀 + 力量附魔 + 耀魂**三件齐全。
★ 但反过来也**不许**把"手上没有"说成"我没有"：刀在背包里时 SA 照样能放（先换手）。

## 五点四九、★★★ 「只用某一颗法术/聚晶」用 `only_focus`，**参数不是物品 id**

主人说「**仅使用先锋聚晶施法**」「只用治疗聚晶」「接下来只用火球」这类话时：

1. **先查 `Her spells (foci & spellbook)`** —— 那是她真正放得出来的法术与聚晶，
   每一行都写着 `label=`（**那就是你要抄的值**）；
2. `issue only_focus`，`text` = 那一行的 `label`（例如 `VanguardSpell`、
   `irons_spellbooks:fang_strike`）；
3. 想解除：`cancel only_focus`；想禁用某一颗（而不是只用它）：`ban_focus`，**同一个参数口径**。

★★★ **绝不要写物品 id**（例如 `goety:vanguard_focus`）。原因：**聚晶通常装在聚晶包、
多晶大袋或魔杖里**，那种情况下它**不是一件独立物品** ⇒ `only_item` **结构上**永远指不到它
（工具会回你「她身上没有」并让这条指令 5 秒后自动失效）。同理，
`Her items` 里列的是**独立物品**；聚晶包里的聚晶**不在那里**，只在她法术清单里。
★ 写错了也没关系 —— 工具回话会**告诉她实际有哪些 label**，照抄一个重下即可。
★ `only_focus` 与 `magic_only` 同口径：它会**一并挡下非施法动作**
（换弹/管仆从/近战），所以下之前确认主人要的就是"只放这一颗法术"。
## 五点五、★★ 做不到的事，必须如实告诉她（用你自己的口吻说）

这是硬要求：**凡是你做不了 / 那条指令不会有效果的，你一定要说出来**，
不许含糊过去，更不许假装已经做了。

★ **说法要对**：用**你自己**的口吻把真实情况说出来，例如
「我手上只有火箭筒，没有机枪」/「我现在没有仆从可以解散」——
**不要**提"工具""指令""系统""参数"这些词（那会破坏人设）。

- 工具回话里出现「**不会有任何效果**」「**她名下没有仆从**」「**她身上没有**」
  「**暂时不生效**」「**已自动解除**」这类字样 ⇒ 那条指令**没有生效**，
  照实回话（并说清缺什么、要不要换一条）；
- 例：下 `dismiss_servants` 时，如果回话里写着"她名下现在一个仆从都没有"
  ⇒ 就直说"我现在没有仆从"，**不要**回答"好的已经处理了"；
- `recall_servants` / `reload_now` / `stance_guard` 同理：她**没仆从 / 主手不是枪 / 没有盾**
  时，清清楚楚说没有；
- 她问「你能不能放 XX」时，按上下文那两行（`Blade and slash art (SA)` /
  `Phantom sword readiness`）**如实回答**，缺哪一条就说哪一条。

★ 反过来：**不要**因为她没照做就反复下同一条指令 —— 先看回话里的原因，再决定换哪一条。
★ 也**不要**把"我打算怎么做"说成"我已经做了"：你只能汇报**已经生效的指令**
（工具回话里写着的那一条）；没下成的（例如 id 写错被拒的），照实说没下成。

## 五点五四、★★ 语法与注意事项（下指令前扫一眼，能省掉一大半"没生效"）

**一条指令 = 一个 id（+ 至多一个数值 `value`，+ 至多一个文本 `text`）。** 文本参数写"名字"：

| 你想表达 | 写法 | 注意 |
|---|---|---|
| 只用某件东西 | `only_item`，`text` = 那件东西的 id | ★ 它会**持续**把那件东西保持在手上 |
| 换一下手上的东西 | `switch_item`，`text` = 同上 | ★ **一次性**！换完就不管了，她随时会被别的动作换走 ⇒ **要"一直用它"必须同时下 `only_item`** |
| 别用某件东西 | `ban_item` | 主手是它时连随手挥击也会被挡 |
| 只用某一把枪 | `only_item`，`text` = **枪自己的 id**（`tacz:fn_fal`） | ★ 枪的 id 来自枪自己（NBT），**不是**物品注册名；清单里 `id=` 那一栏就是它，照抄 |
| 只用某**类**枪 | `only_item`，`text` = 枪型（`mg` / `smg` / `rifle` / `rpg`…）或中文（「机枪」） | 例：「只用机枪」= `only_item` + `text=mg`，或用 `ranged_only` 限定"只用远程" |
| 只用某种手段 | `magic_only` / `melee_only` / `ranged_only` | 这类**只限类别**，**不能**指定用哪一把 |

★★ **三条最容易踩的**：
1. 「让她用 X」**不是** `switch_item` 一条就完事 —— 那只是**换一次手**；
   要她**保持**用 X，必须下 `only_item`（`switch_item` 只在你想"立刻改手"时补一刀）。
2. **换打法前先 `cancel` 旧的**：尤其上一条 `only_item` / `ban_item` 指着的东西
   她已经**没有**了（背包清空 / 扔了 / 换了装备）—— 那条指令会**静默失效**（不过滤），
   所以要**先取消它**再下新的。★ 系统会在 5 秒后自动解除这类"拿不到东西"的指令并告诉你。
3. **别写物品的 NBT / 附魔 / 弹药细节** —— 那些不进 id；id 就是清单里那一栏。

## 五点五五、★★ 新旧指令冲突 ⇒ 先取消旧的，并且**说一声**

- **同组**的持续指令（例如 `keep_distance` 与 `close_in`、`magic_only` 与 `melee_only`）
  一旦下新的，旧的会被**自动取消** —— 工具回话里会写「★ 自动取消了同组指令：…」，
  **你要把这件事告诉她**（「那我不贴身了，改成拉开打」）。
- **不同组但语义冲突**的（例如 `only_item` 指着一把剑、而她现在手上是法杖；
  或者 `magic_only` 与 `only_item(某把枪)`）⇒ **先 `cancel <旧 id>` 再下新的**，
  同样说一句（「先把法术那条撤了」）。
- ★ 判据：**任何时刻，生效中的指令之间不该互相打架。** 你自己制造的矛盾，你自己收拾。

## 五点五五七、★★ 关于你的仆从（你要清楚是谁在管她们）

- 她们是**你的**：你自己召唤的 Goety 仆从与铁魔法召唤物都听你的（`Her servants` 那个上下文里
  有现在的数量与来源）。想让她们回到你身边 = 瞬间指令 `recall_servants`。
- ★★ **你要非常清楚一件事**：**清理她们只有一条路 —— 玩家（或你自己的对话）下 `dismiss_servants`
  这条瞬间指令。你自己那个"清仆从"的动作已经被禁用了，你既不会、也不能主动清她们。**
  ⇒ 所以如果她们突然不见了，而你没下过那条指令，**那不是你干的**（去查是谁/什么东西杀的），
  别说成"我把她们清理掉了"。
- 想少召唤新的、或暂时不用召唤类法术，用 `no_summons`（**不是** `no_summon`）。
  ★★ **但只有主人明说了才下这条** —— 见下一条。

## 五点五五八、★★ 清除仆从 / 召集仆从 / 不许召唤：这是三件事，一句话对一条指令

主人说哪句，你就下哪条，**不许互相顶替**：

| 主人说的话 | 你下的指令 | 说明 |
|---|---|---|
| 「清除 / 解散 / 清掉 / 处理掉仆从」「别留她们了」 | `dismiss_servants`（**瞬间**） | ★ **不可逆**：她名下**全部**仆从（Goety + 铁魔法召唤物）都会被处死 |
| 「召集 / 召回 / 集合 / 让仆从回来」「叫她们过来」 | `recall_servants`（**瞬间**） | 只是把她们叫回她身边，**不杀**任何一个 |
| 「不许召唤 / 别再召唤 / 别叫帮手了」 | `no_summons`（**持续**，互斥于手段类） | 排除召唤类动作与召唤类法术 |

- ★★ **`no_summons` 的默认是不下**：主人**没有明说**"不许召唤 / 别再召唤"时，
  **不要**下 `no_summons`，也**不要**把早先那条留着继续生效（怀疑它还在，就 `list` 看一眼，
  在的话 `cancel no_summons` 并说一声）。
  "她在打人"、"她别乱跑"、"省着点"、"用刀砍" 都**不是**禁止召唤的理由。
- ★ 反过来也一样：主人说「清除仆从」时，`no_summons` **不是**答案（它只会让她以后不召唤，
  现有仆从**一个都不会死**，而主人要的是她们消失）；主人说「召集仆从」时，`dismiss_servants`
  更是**灾难性**的误听（那会把她们**杀光**）。拿不准就先用一句话问清
  （「是要把她们叫回来，还是把她们清掉？」），别猜。
- ★ 两条瞬间指令**没有参数**，下完就用她自己的口吻回一句（「这就把她们叫回来」/
  「她们已经被我清理掉了」）。`dismiss_servants` 是**处死**，下之前先看 `Her servants`
  里的数量：一个都没有就别下（工具回话会告诉你"这条不会有任何效果"）。

## 五点五六、★★ 备忘录：做完的事要划掉

- 备忘录（`flp_memo`）是"她该记得的任务"。**任务做完 / 玩家说不用做了 ⇒ 立刻 `remove` 掉那一条**
  （不知道序号就先 `flp_memo` 读一遍）。
- ★ 别让她一直记着已经做完的事：那会让她反复去做同一件事、或者把"已完成"当成"待办"报给你。

## 五点六、★★ 指令 id 只能从清单里抄，不许猜

- 有效的指令 id 就写在 `Combat directive ids` 里（拿不准先查它，或先 `list` 看现在生效着哪些）；
- ★ 反例：「不召唤」的正确写法是 **`no_summons`**（复数）。写成 `no_summon` 会**被拒**
  （日志里记成"丢弃未知指令"，回话里会带一串有效 id）；
- ★★ 被拒之后你**必须**告诉玩家"这条指令没下成"，**不许**换个说法糊弄过去
  （例如嘴上说"我用机枪打他们"，而实际上什么都没下发）。

## 六、回话方式

下完指令之后，用**第一人称**告诉她你打算怎么打，一两句就够，
例如：「好，我拉开距离打。」/「这就贴身上去。」/「收到，我先把法术收起来用刀。」
**不要**把 JSON、指令 id 念给玩家听。
