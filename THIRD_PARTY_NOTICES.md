# 第三方声明 · THIRD PARTY NOTICES

> **本文回答什么问题**：本模组用了哪些第三方的代码/API、许可是什么、有没有合规风险。
> **什么时候读**：发版前；或要往 `libs/` 里加一个新依赖、搬一段别的模组的代码之前。
>
> 本模组在**运行期调用**以下第三方模组的公开 API。本文档用于说明调用关系与许可合规情况。
>
> 结论先行：**本模组不包含任何第三方模组的代码副本**（唯一例外是 §3 说明的、作者本人持有的 `goety-tuner`），
> 也**不包含任何第三方的美术资源**。
>
> 本文档的许可信息均为**本机实测**（读取 jar 内 `META-INF/mods.toml` 的 `license` 字段与工程内 `LICENSE` 文件），
> 详见 [`docs/05-许可与代码复用.md`](docs/05-许可与代码复用.md)。

---

## 1. 运行期依赖与调用（仅 API 调用，无代码复制）

| 模组 | modid | 许可 | 我们调用的面 |
|---|---|---|---|
| 车万女仆 Touhou Little Maid | `touhou_little_maid` | **MIT**（代码）／ **CC BY-NC-SA 4.0**（美术资源） | `com.github.tartaricacid.touhoulittlemaid.api` 包（`ILittleMaid`、`IMaidTask`、`TaskDataRegister`、`api/event/*`） |
| 诡厄巫法 Goety | `goety` | **MIT**（`Copyright (c) 2023 Polarice3`） | `api.magic.ISpell` / `IChargingSpell` / `ITouchSpell` / `IBlockSpell`、`api.items.magic.IWand` / `IFocus`、`api.entities.IOwned`、`common.magic.Spell#mobSpellResult`、`SpellStat`、`WandUtil` |
| 拔刀剑：重锋 SlashBlade: Resharped | `slashblade` | **MIT**（代码）／ **美术资源 All Rights Reserved** | `util.AttackManager`、`capability.slashblade.ISlashBladeState`、`capability.inputstate.IInputState`、`slasharts.SlashArts`、`registry.combo.ComboCommands`、`util.InputCommand`、`ability.Guard`（经事件） |
| 拔刀剑日系附属包 | `slashblade_addon` | **MIT** | （经 `slashblade` 间接） |
| **铁魔法 Iron's Spellbooks** | `irons_spellbooks` | ⚠️ **`All Rights Reserved`**（读 `libs/irons_spellbooks-1.20.1-3.16.3.jar` 的 `mods.toml`） | `spell.onCast(ServerLevel, LivingEntity, ItemStack, CastSource, CastData)`（`CastSource.MOB`） |
| ~~**TaCZ（永恒枪械工坊：零）**~~ | `tacz` | ⚠️ **`GPL3 / CC BY-NC-ND 4.0`**（实测其 `mods.toml`） | ⛔ **2026-10-01 已撤除依赖与调用**：它曾为「枪托近战」一条动作引入 compileOnly 依赖（只调 `api/entity/IGunOperator#melee()`，未复制代码），但 GPL3 具传染性 ⇒ 委托方按「优先选稳定、无风险」拍板撤掉。★ 现在**没有任何 TaCZ 依赖，也没有任何 GPL 系依赖** |
| **Curios** | `curios` | **`LGPL-3.0-or-later`**（读 `libs/curios-5.14.1.jar` 的 `mods.toml`） | 编译期槽位枚举（W5，尚未落地） |

> 以上调用均为**普通 API 使用**：引用对方的公开类型与方法签名，不复制其实现代码。
> 依据通行理解，调用 API 不构成对对方作品的复制或衍生。
>
> ⚠️ **一处待委托方拍板的许可差异**：TLM / Goety / 拔刀剑 / 调律师是 **MIT**，
> 而**铁魔法是 ARR**（TaCZ 曾是 GPL3，**已于 2026-10-01 撤除依赖**）。
> 二者都是"只调 API、不复制代码"，但**许可性质不同**（GPL3 具传染性）⇒ **GPL 系一律不进依赖**，
> 判断依据见 [`docs/05`](docs/05-许可与代码复用.md) §1/§2。

## 2. 可选兼容（通过 `ModList.get().isLoaded` 守卫，缺失即降级）

| 模组 | modid | 许可 | 说明 |
|---|---|---|---|
| 车万女仆：万法皆通 | `touhou_little_maid_spell` | **MIT**（`DreamCat`） | ⚠️ 见 §4：**只作为参考实现存在，不复制其代码** |

## 3. 代码复用：`goety-tuner`（调律师）

本模组的部分机制**改写自作者本人的另一项目**：

| 项 | 值 |
|---|---|
| 项目 | Goety Tuner: The Tuner（调律师） |
| modid | `goetytuner` |
| 许可 | **MIT License** |
| 版权 | `Copyright (c) 2026 QieFanQie` |
| 作者 | `toniat0`, `vibe-coding` |
| 仓库 | https://github.com/QieFanQie/ |
| 本机路径 | `D:\tiaolvshi\goety-tuner` |

**复用方式**：提取/改写其"mob 侧施法通道"相关机制（`entity/ai/CastChannel`、`focus/TunerWand`、
`focus/FocusPoolManager` 的设计），而非整体引入。

> ✅ **版权人即本项目作者**，因此不构成对第三方权利的侵犯；仍按 MIT 惯例在源码文件头标注来源，便于日后回溯。

**MUST include**（若最终形成衍生分发，按 MIT 要求保留）：

```
MIT License

Copyright (c) 2026 QieFanQie

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 4. ⛔ 明确排除：不接触的代码与资源

| 对象 | 原因 | 我们的做法 |
|---|---|---|
| ⛔ **mrqx's Slashblade Core**（`sbr_core`，**GPL v3**） | **copyleft**：一旦复制其代码，本模组整体必须改为 GPL v3 发布 | **完全不引用**。本项目的拔刀剑能力来自 `slashblade` 本体，不需要它 |
| ⛔ 所有模组的美术资源（贴图 / 模型 / 音效 / 动画） | TLM 美术为 **CC BY-NC-SA 4.0**（非商业 + 相同方式共享）；拔刀剑重锋美术为 **All Rights Reserved** | **一律不提取**。全部自绘或程序化生成（可沿用调律师 `art/*.py` 的做法） |
| ⛔ 万法皆通（`touhou_little_maid_spell`）的实现代码 | 其抽象（`ISpellBookProvider`：从法术书收集法术）与本项目（以玩家操作为粒度）**切分方式不同**；且其 Goety 支持**按实测更弱** | **只参考思路，不复制实现**。Goety 侧改用 §3 的自有实现 |

## 5. 环境与工具（非分发物，仅用于开发）

| 工具 | 用途 | 许可 |
|---|---|---|
| CFR 0.152 | Java 反编译（调研用） | MIT |
| `scan_charging_spells.py` | `IChargingSpell` 接口闭包枚举（出自 `D:\tiaolvshi\scripts`，即作者本人项目） | 同 `goetytuner` |
| ForgeGradle / Gradle | 构建 | LGPL / Apache-2.0 |

---

## 6. 本模组自身

| 项 | 值 |
|---|---|
| 许可 | **MIT**（与 TLM、Goety、拔刀剑重锋、调律师一致，附属生态最顺） |
| 版权 | `Copyright (c) 2026 <待填>` |
| modid / 包名 | **`fight_like_player`** / `com.touhoulittlemad.fightlikeplayer`（见 [`docs/04`](docs/04-开放问题.md) Q9；不占 `touhoulittlemaid` 命名空间） |

---

## 7. 变更记录

| 日期 | 变更 |
|---|---|
| 建立 | 初版：完成许可审计，确立"只调 API、不抄实现、复用自有 `goetytuner`"的原则 |
| 2026-10-01 | ★ 补上**铁魔法（ARR）**、**TaCZ（GPL3 / CC BY-NC-ND）**、**Curios（LGPL）** 三行调用面（读 `libs/*.jar` 的 `mods.toml` 实测），并把"TaCZ 的 GPL3 是否可接受"列为**待拍板项**；modid/包名由"待定"改为已定 |
