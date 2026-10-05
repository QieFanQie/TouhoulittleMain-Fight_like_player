# 操作清单 · 数据（Operation Catalog）

> **本文回答什么问题**：`catalog/` 里每个文件是什么、怎么加/改一条动作、怎么校验。
> **什么时候读**：要读或改清单数据时（`data/*.json` / `carriers.json` / `vectors.json` / `executors.json`）。
>
> 本目录是「**第一步**」交付物的**机器可读形态**。
> 散文与推导在 [`docs/01-操作清单.md`](../docs/01-操作清单.md)；**为什么选这个格式**在 [`docs/07-清单数据格式.md`](../docs/07-清单数据格式.md)。
> **当前条目数（权威）见 [README §「现在做到哪一步」](../README.md)**；本文 §5 的数字由下面第 3 节的脚本复跑得到。

```
catalog/
├── README.md                      ← 本文件：怎么用、怎么加条目
├── schema/
│   └── operation.schema.json      ← JSON Schema（编辑器可校验、可生成补全）
├── data/                          ← ★ 动作条目（6 个文件，共 77 条，按模组分）
│   ├── maid_native.json           ← 基线：TLM 原生 11 条（「类玩家」之前）
│   ├── slashblade.json            ← 载体：拔刀剑（30 条记录 / 31 个操作实例）
│   ├── goety.json                 ← 载体：诡厄巫法（聚晶族 + 仆从操作，7 条）
│   ├── ironspells.json            ← 载体：铁魔法（12 条）
│   ├── guns.json                  ← 载体：枪械 TaCZ / Pillager's Gun（15 条）
│   └── fight_like_player.json     ← 本项目新增（2 条）：`gait` 步法（stance）+ `disengage` 撤退&脱战
├── carriers.json                  ← 物品 → 载体（18 条规则，含专精度优先）
├── vectors.json                   ← ★ 评分表 `v_x`（54 条，与动作数据分开存放）
└── executors.json                 ← ★ 动作 → 执行器映射（覆盖率账本，12 个 kind）
```

> ⚠️ **`data/*.json` 之外还有三个"不是动作条目"的文件**：
> `carriers.json`（物品 → 载体）· `vectors.json`（评分）· `executors.json`（执行器覆盖）。
> 决策层要同时读这四类文件才能回答"她现在能做什么、该做什么、做不做得到"。

---

## 1. 三条使用规则

### 规则 1 · 族（family）与实例（instance）是两件事

| | `kind` | 含义 |
|---|---|---|
| **族** | `"family"` | 参数化动作族。**必须**有 `params`。决策层看到的是它（紧凑，避免组合爆炸） |
| **实例** | `"atomic"` | 可直接选中的单个动作。若是某族的实例，用 `familyOf` 指回该族 |

**为什么要这样切**：**124 个聚晶不是 124 条记录**。展开成平铺条目会产生上千条，
第二步（行为逻辑架构）无法驾驭。但"穷举所有可行操作"的要求又不能丢 ⇒ 族存参数域、实例存具体取值。

> ⚠️ **因此"条目数"有两种口径，引用数字时必须说明是哪一种**：
> 拔刀剑是 **30 条记录**（29 atomic + 1 family），但它表达 **31 个操作实例**
> ——因为「SA·Success」与「SA·Jackpot」被压成 `slashblade:slash_art` 的 `timing` 参数。

### 规则 2 · `reach` 必须逐条判定，不能"整族一个值"

| 值 | 含义 | 处理 |
|---|---|---|
| `RA` | 服务端有公开入口可**直接调用** | 首选做。`reachEntry` 里写**签名** |
| `RB` | 无公开入口，但可**组合复现** | 做，但 `reachEntry` 要写清复现路径 |
| `RC` | 本质依赖**客户端或 `Player` 身份** | **不进清单主体**（记录其存在与原因即可） |

实测三个载体的可达性**截然不同**，所以千万不要整族一个值：

| 载体 | R-A | R-B | R-C |
|---|---|---|---|
| 拔刀剑 | 13 | 10 | 7 |
| 诡厄巫法（聚晶族 + 仆从） | 6 | 1 | 0 |

### 规则 3 · `evidence.level` 用四档，不是"有/无"

| 值 | 含义 |
|---|---|
| `verified` | 已查证：读了源码 / 字节码 / 官方注册表 / `lang` |
| `inferred` | 推断（有间接证据） |
| `unverified` | 未能查证 |
| **`doc_mismatch`** | ★ **文档与代码不一致，以代码为准** |

**证据等级（硬性）**：**源码/字节码 > 官方注册表 > `lang` 文案 > 第三方资料**。
`lang` 与 tooltip 只用来**发现"存在某个操作"**，具体机制一律回代码验证。

---

## 2. 怎么加一条（给新模组编制清单的流程）

对应 [`docs/01`](../docs/01-操作清单.md) §2 的五步法，落到文件上就是：

| 步 | 动作 | 产出 |
|---|---|---|
| 1 | **从模组自身找操作清单** —— 很多模组把操作写进了成就/指南/tooltip | 一份候选操作名单（拔刀剑的 22 条成就就是官方操作手册） |
| 2 | **枚举载体** —— 哪些物品/系统是"操作来源" | `carrier` 值 |
| 3 | **抽取触发语法** —— 键位+状态+时序 → `stateReq` / `comboDeps` | 状态与依赖字段 |
| 4 | **查服务端可达性** —— `javap` 找公开入口；找不到就下潜到实体/能力层 | `reach` + `reachEntry` |
| 5 | **与既有实现对账** —— 避免重复造轮子 | `existingImpl` |

### ⚠️ 三个必须遵守的陷阱规避

| 陷阱 | 规避做法 |
|---|---|
| **分散坑**：一个模组的操作清单常分散在多处 | ★ 用「**载体 × 注册表**」交叉验证：凡模组里有 `DeferredRegister`/`Registry` 的，逐个对账"这个注册项对应哪个操作？"对不上的就是漏掉的（拔刀剑的 SA 就是这么找出来的） |
| **手写清单坑**：手写"哪些法术属于某类"会随附属过时 | ★ 优先用 `params.<x>.ref` 指向**接口/注册表闭包**。已实测：`IChargingSpell` 闭包随装的附属自动变化（26 / 30 / 38），**加模组自动扩表** |
| **文案坑**：`lang`/tooltip 会错 | 每条的机制都要有 `verified` 级的 `cite`（file:line 或 class#method）。已知文案有错的实例已记录（见 `docs/01` §4.3 的 `ghastly_focus`） |

---

## 3. 校验方式

> ⚠️ **先设 `$env:PYTHONIOENCODING='utf-8'`**，否则 GBK 控制台打印 ⚠ 会抛 `UnicodeEncodeError`。

```powershell
$env:PYTHONIOENCODING='utf-8'
python tools\validate_catalog.py      # ★ 语法 + 必填字段 + 内部一致性 + 条目数
python tools\vector_coverage.py       # ★ 评分覆盖率 + provenance（vectors.json）
python tools\check_executors.py       # ★ 执行器覆盖账本（executors.json 与清单交叉核对）
python tools\audit_durations.py       # ★★ 承诺时长 / 节奏审计
python tools\audit_reachability.py    # ★★ 前置条件可达性审计
python tools\check_axis_names.py      # ★ 轴名对账（写错会被 Gson 静默忽略）
```

```powershell
# 手工统计：语法 + 条目数
Get-ChildItem catalog\data\*.json | ForEach-Object {
  $j = Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
  "{0,-22} 条目={1}" -f $_.Name, $j.operations.Count
}

# 交叉校验：统计 reach 分布（用于与 docs/01 的汇总表对账）
Get-Content catalog\data\slashblade.json -Raw -Encoding UTF8 | ConvertFrom-Json |
  ForEach-Object { $_.operations } | Group-Object reach | Select-Object Name, Count
```

**Schema 校验**（可选，需一次性准备）：
```powershell
# 有 Node.js 时：
npx --yes ajv-cli@5 validate -s catalog\schema\operation.schema.json `
  -d "catalog\data\*.json" --spec=draft2020
```

**改完数据后的一键回归**（9 个自测 + 9 个校验脚本，见 [12 §8](../docs/12-项目参考.md)）：
```powershell
powershell -ExecutionPolicy Bypass -File tools\run_selftests.ps1
```

---

## 4. 与第二步的关系（为什么格式要这么设计）

第二步要的是「**可便捷搭建的行为逻辑架构**」。这直接决定了本清单必须是**数据**而不是 Java 枚举
（万法皆通的 31 个 `DirectSkill` 是枚举 ⇒ 改行为要改代码重编译，这正是它的天花板）。

因此本格式刻意做了三件事，都是为第二步铺路：

| 设计 | 为什么第二步需要它 |
|---|---|
| **族 + 参数域** | 决策层要面对一张**可管理的表**，而不是几千条平铺条目 |
| **`preconditions` 必须服务端可判定** | 行为逻辑的第一件事就是"这个动作现在能不能用"；不可判定的前置等于没有 |
| **`duration` / `interruptible` / `comboDeps` / `exclusivity`** | 决策层排时序与资源占用所需：多 tick 承诺能不能被打断、组合动作的前置窗口、同时只能用 N 个的互斥组 |
| **`effects` 含 `SET_AI_STATE`** | 仆从管理类操作是"**改状态**"而不是"造成伤害"，effects 的取值域必须能表达 |

---

## 5. 当前进度（★ 数字由 §3 的脚本复跑得到，2026-10-01）

| 文件 | 状态 |
|---|---|
| `schema/operation.schema.json` | ✅ 已定稿 v1（覆盖族/参数域/互斥/组合依赖/可达性/四档证据） |
| `data/maid_native.json` | ✅ **11 条**（基线，完整；`choice` 9 / `passive` 2） |
| `data/slashblade.json` | ✅ **30 条记录 / 31 实例**（`choice` 30；全部带 `verified` 级证据） |
| `data/guns.json` | ✅ **15 条**（`choice` 4 / `maintenance` 7 / `stance` 4）。★ 结论：**枪械不是"退化"载体**——TaCZ 的 10 个操作全对 `LivingEntity` 开放（R-A），TLM 只是没调 |
| `data/ironspells.json` | ✅ **12 条**（`choice` 3 / `mechanism` 9）。★ 结论：**recast 是最大行为差异**（R-B）；**同类法术书互斥是万法皆通自己的设计**，应解除 |
| `data/goety.json` | ⚠️ **7 条**：聚晶族 + 关键仆从操作。G1–G17 的其余条目待补（W8） |
| `data/fight_like_player.json` | ✅ **2 条**（**本项目新增**，非勘测所得）：`gait` 步法（`role=stance`，走位已从动作层移出，见 [04 Q18](../docs/04-开放问题.md)）/ `disengage` 撤退&脱战。`sourceMod = fight_like_player` |
| `data/tetra.json` | ⏳ 待勘测（B9） |
| `carriers.json` | ✅ **18 条载体规则**（物品 → 载体），其中 **3 条判据未查证**（见下） |
| `vectors.json` | ✅ **54 条 `v_x`**（评分表权威来源） |
| `executors.json` | ✅ **12 个 kind**（11 `implemented` + 1 `planned`[`tacz_gun`]） |

**当前合计：77 条**（6 个数据文件）**+ 18 条载体规则 + 54 条评分 + 11 个执行器 kind**。
按 `role`：`choice` **54** / `maintenance` 7 / `mechanism` 9 / `stance` 5 / `passive` 2；
按 `reach`：`RA` **50** / `RB` 16 / `RC` 11。

### `v_x` 覆盖率（★ 决策层的燃料）

清单条目只有带上**需求向量 `v_x`** 才能被决策层评分选中。当前覆盖率（`tools/vector_coverage.py`）：

| 项 | 值 |
|---|---|
| 动作总数 / 其中 `choice` | **77 / 54** |
| 评分表条目 | **54** ⇒ **`choice` 但缺评分：0 条** ✅ |
| `provenance` 分布 | **`agent` 53**（子代理打分，**待人工复核**）+ **`authored` 1**（人工判断） |
| 带 `overrides` 的族（5 个） | `goety:cast_focus`(8) · `irons:cast_spell`(3) · `irons:recast`(3) · `slashblade:slash_art`(9) · `tacz:shoot`(7) |

> ✅ **W2 已解决**：**54 个 `choice` 动作全部可评分**。
> 其余 23 条**不需要向量** —— 它们是 `maintenance`（换弹/拉栓/拔枪/配件/检视）、
> `stance`（ADS/切射击模式/开镜/匍匐/步法）、`mechanism`（学习门等）或 `passive`（引擎自动附带），
> **不是决策层会"选"的东西**，已用 `role` 字段标出，并被载体解析记为 `NOT_A_CHOICE` 后移出候选集。
> 判据见 [09 §2.4](../docs/09-动作空间与评分体系.md)。

> ⚠️ **`provenance` 必须看**：53 条是 **`agent`（子代理批量打分）**，**必须逐条人工复核后**才能作为行为调优的基础。
> 复核线索见 [09 §8.0b](../docs/09-动作空间与评分体系.md) 的 W13/W33。

### `carriers.json`（★ 与 data/ 方向相反的映射）

`data/*.json` 里是「**动作 → 载体**」；`carriers.json` 里是「**物品 → 载体**」。
两者合起来才能从"女仆持有什么"推出"她能做什么"。

| 项 | 值 |
|---|---|
| 规则数 | **18 条** |
| 匹配方式 | `always`（无物品）/ `itemId` / `itemTag` / `javaInstanceOf`（可 `simpleName`）/ `capability` / `itemAttribute` |
| ⚠️ 判据未查证 | **3 条**：`maid:danmaku`、`maid:throwable`、`pillagersgun:gun` —— 显式标 `kind: unresolved`（带 `reason`），**永不命中（fail-closed）**，不会假装能用。★ `goety:command_horn` / `goety:focus_recall` 的判据已于 2026-10-01 补齐（`javaInstanceOf CommandHorn` / `RecallFocus`） |

> ★ **`carrier` 是"能力"而不是"槽位"**：`maid:weapon`（有 `ATTACK_DAMAGE` 属性）、
> `maid:bow`、`maid:trident` 是三个不同载体，尽管它们都可能在主手。
> 若把它们混成一个"主手载体"，载体解析就无法回答"她到底能做什么"。

> ★★ **专精度抑制**（2026-10-01，见 [13 §1 第 17 条](../docs/13-更正记录与教训.md)）：
> **同一件物品可能同时命中多条规则**（一把拔刀剑既有 `ItemSlashBlade` 类型、又有 `ATTACK_DAMAGE` 属性），
> 此时**只有最"专"的那条（或同精度的几条）规则产动作**，其余被抑制：
>
> | 匹配方式 | `itemId` | `itemTag` | `javaInstanceOf` | `capability` | `itemAttribute` | `always` |
> |---|---|---|---|---|---|---|
> | 专精度 | **50** | **40** | **30** | **25** | **10** | **0** |
>
> **为什么必须这样**：泛化规则（`maid:weapon`）的向量是"贴身平砍"，与专化规则的动作向量高度重叠 ⇒
> 不抑制的话，**泛化动作会把专化动作的概率稀释掉**，实测观感就是"**拿着拔刀剑却只在平砍**"。
> 抑制次数写进 `Resolution#notes`（**说明而非静默消失**）。⇒ **新增载体规则时要问一句：它会不会和已有规则抢同一件物品？**

### 校验（★ 必须跑）

```powershell
$env:PYTHONIOENCODING='utf-8'
python tools\validate_catalog.py
```

`tools/validate_catalog.py` 做三类检查：**严格 JSON 语法**（给出准确行列）、**必填字段**、**内部一致性**
（重复 id / `family` 缺 `params` / 取值域为空 / `familyOf` 悬空 / `verified` 级证据缺 `cite` / `effects.type` 非法 …）。

> ⚠️ **一个真实踩过的坑**：JSON 不支持注释，而本清单满是中文说明 ⇒
> **中文引号误写成 ASCII `"` 会产生非法 JSON**。这类错误**只看 diff 发现不了**（长得几乎一样），
> 而 PS 5.1 的 `ConvertFrom-Json` 报错只给一个偏移量、极难定位。
> ⇒ **规则：中文里的引号一律用「」，不要用 `"`。** 校验脚本会抓到。

> 📌 **补全方式**：`docs/01` 的 Markdown 表格已经是**结构化**的（每列对应一个字段），
> 因此剩余条目可以**半自动转换**；但每条都必须补 `evidence.cite` 与 `reachEntry`，
> 这两项无法从表格机械生成。
