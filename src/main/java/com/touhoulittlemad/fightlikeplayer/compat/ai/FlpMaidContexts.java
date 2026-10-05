package com.touhoulittlemad.fightlikeplayer.compat.ai;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.AbstractMaidContext;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.carrier.CatalogHolder;
import com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder;
import com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeChannel;
import com.touhoulittlemad.fightlikeplayer.compat.exec.slashblade.SlashBladeExecutors;
import com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot;
import com.touhoulittlemad.fightlikeplayer.compat.memory.MaidMemory;
import com.touhoulittlemad.fightlikeplayer.decision.DirectiveSpec;

import mods.flammpfeil.slashblade.capability.slashblade.CapabilitySlashBlade;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.SwordType;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>把"她自己的战斗实况"注册成 TLM 的 maid context</b>（第十六轮）。
 *
 * <h2>委托方原话（两个问题一起答）</h2>
 * > 「我观察到了 SA，但女仆的对话 llm 似乎**不知道自己有拔刀剑能释放 SA**，乃至于**什么 SA**；
 * > 同时她**也不知道自己能否释放幻影剑**。（动作有 id 吗？有的话，可以进一步写：
 * > 接下来几秒只进行这些动作）」
 * > 「我注意到女仆对话 llm 有获取背包内容物的能力，但似乎**没办法实时获取**（或者说，
 * > 比起依赖获取物品列表，更依赖上下文？）」
 *
 * <h2>★★ 为什么此前她"不知道"（这是机制问题，不是提示词问题）</h2>
 * 我们之前只把战斗场景喂给了**指挥 LLM**（{@code llm.*} 那一套，见 {@code CombatScene}）。
 * TLM 的**对话 LLM** 拿到的是 TLM 自己注册的那几类上下文
 * —— 其中 {@code equipment} 类**只报物品名与数量**（`Main-hand item` / `Backpack items`…），
 * 它<b>不认识"这是不是妖刀、上面配了什么 SA、能不能放幻影剑"</b>。
 * ⇒ 修法：用 TLM 的公开 API {@code ILittleMaid#registerAIMaidContext(GameContextRegister)}
 * 注册我们自己的上下文。
 *
 * <h2>★ 实时性（第二个问题的答案）</h2>
 * TLM 的 {@code getValue(maid)} 是**每次取值时现算**的 ⇒ 数据本身永远是实时的。
 * 真正的区别在**什么时候会去取**：
 * <ul>
 *   <li>{@code isPromptContext=false}（TLM 的 {@code equipment} 就是这一类）——
 *       <b>只有模型主动调 {@code query_game_context} 才会取</b>：模型不调，它就看不到；
 *       调过一次之后如果模型"记着上次的结果"，那就是**模型自己的记忆过期**，不是数据过期；</li>
 *   <li>{@code isPromptContext=true} —— 每次对话都**自动进 prompt**（适合"每个回合都必须知道"的少量关键事实）。</li>
 * </ul>
 * ⇒ 我们**两类都注册**：关键的少量事实（武器/SA/幻影剑可否/当前指令）走 prompt 类，
 * 长清单（全部动作 id、全部物品）走工具类按需查。
 *
 * <h2>★ 动作 id（委托方问"有 id 吗"）</h2>
 * <b>有</b>：清单里每条动作都有自己的 id。这里把它列出来，模型才填得出
 * {@code only_actions}（"接下来几秒只做这些动作"）。
 */
public final class FlpMaidContexts {

    private FlpMaidContexts() {
    }

    /** prompt 类：每个回合都该知道的那几句。 */
    public static final String CATEGORY_NOW = "flp_combat_now";

    /** 工具类：长清单（按需查，实时代价低）。 */
    public static final String CATEGORY_DETAIL = "flp_combat_detail";

    /** 注册（由 {@code LittleMaidCompat#registerAIMaidContext} 调用）。 */
    public static void registerAll(GameContextRegister register) {
        register.registerCategory(CATEGORY_NOW,
                "This maid's live combat readiness: her blade, the slash art (SA) she can use, "
                        + "whether she can cast the phantom sword, and the combat directives "
                        + "currently in force.", true);
        register.registerContext(CATEGORY_NOW, new BladeContext());
        register.registerContext(CATEGORY_NOW, new PhantomSwordContext());
        register.registerContext(CATEGORY_NOW, new ActiveDirectivesContext());
        // ★★ 第十六轮：击杀（本窗口）+ 备忘录（她该随时记得自己的任务）
        //   ★★ 这个 getValue 被调用 == TLM 正在为**这一次**对话收集 prompt
        //      ⇒ 它同时是"上一次对话 → 这一次对话"的边界（见 MaidMemory 的类注释）
        register.registerContext(CATEGORY_NOW, new KillsThisWindowContext());
        register.registerContext(CATEGORY_NOW, new MemoContext());
        // ★★ 第十六轮（委托方第 2 条）：“谁该打”的名单（自定义部分）
        register.registerContext(CATEGORY_NOW, new AttackRulesContext());
        // ★★ 第十七轮续：**四条硬规矩**放进"每回合都在"的那一类。
        //   为什么必须在这里：skill 正文**不是自动进 prompt 的**（TLM 只把技能的
        //   name + description 放进 `<available_skills>`，正文要靠模型主动 `use_skill` 才读得到）
        //   ⇒ 光把规矩写在正文里，等于"看运气"。
        register.registerContext(CATEGORY_NOW, new CommandRulesContext());

        register.registerCategory(CATEGORY_DETAIL,
                "This maid's detailed combat data: every action id she can currently perform, "
                        + "her full item list, and the combat directives she can be given.",
                false);
        register.registerContext(CATEGORY_DETAIL, new ActionIdsContext());
        register.registerContext(CATEGORY_DETAIL, new ItemsContext());
        register.registerContext(CATEGORY_DETAIL, new DirectiveIdsContext());
        // ★★ 惰上下文（委托方要的那两类）：**只有模型主动来问才有内容**
        register.registerContext(CATEGORY_DETAIL, new PastKillsContext());
        register.registerContext(CATEGORY_DETAIL, new PastItemsContext());
        register.registerContext(CATEGORY_DETAIL, new MemoReadContext());
        register.registerContext(CATEGORY_DETAIL, new NearbyMonstersContext());
        // ★★ 第十七轮续（委托方第 2 条）：**她要知道"清理仆从只能由那条指令完成"**
        register.registerContext(CATEGORY_DETAIL, new ServantsContext());
        // ★★ 第十七轮续（委托方第 2 / 2.1 条）：她能报出**自己有哪些聚晶 / 法术书里有哪些法术**
        register.registerContext(CATEGORY_DETAIL, new SpellsContext());
        register.registerContext(CATEGORY_DETAIL, new SpellDescriptionsContext());
        FightLikePlayer.LOGGER.info("[FLP] 已注册女仆对话上下文（{} + {}）",
                CATEGORY_NOW, CATEGORY_DETAIL);
    }

    // ───────────────────────── prompt 类 ─────────────────────────

    /**
     * ★★ <b>四条硬规矩</b>（第十七轮续）—— 每回合都在 prompt 里，**必须短**。
     *
     * <p>内容取舍：只放"模型最容易做错、且做错会让玩家以为功能坏了"的几条
     * （id 不许编 / 枪怎么称呼 / 只说自己真做了的 / 战斗只走拟人模式 /
     * 新旧指令冲突 / 备忘录做完划掉 / 清除仆从 vs 召集仆从 / 没明说就不许禁召唤 /
     * 指名哪一系魔法）。
     * ★ 措辞刻意与 TLM 内置系统提示词**对齐**：那里有 `Zero Tool Reporting`
     * （不许向玩家汇报工具结果）与"不许说系统词"的硬禁令 ⇒ 我们说的是
     * **"用你自己的口吻说清真实情况"**，而不是"把工具回话念出来"。
     * ★★ 第二十一轮补的三条（8/9/10）来自委托方原话：
     * ① 「主人要求清除仆从，就是清除仆从的指令。主人要求召集仆从才是召集仆从的指令」；
     * ② 「且如果主人没明说禁用召唤，则不进行禁用召唤的指令」；
     * ③ 「女仆似乎做不到"指定使用铁魔法"，对于她而言，铁魔法和巫法是一个类的」
     *    ⇒ 新增两条体系指令，这里负责让她**知道什么时候用哪条**。
     */
    private static final class CommandRulesContext extends AbstractMaidContext {
        private CommandRulesContext() {
            super("flp_command_rules", "Combat command rules (always follow)");
        }

        @Override
        public String getValue(EntityMaid maid) {
            return "1. Directive ids: use ONLY ids listed in 'Combat directive ids' "
                    + "(never invent one such as no_summon; the real id is no_summons). "
                    + "2. Items: copy the exact id from 'Her items'. A gun's id is its own id "
                    + "(tacz:rpg7, tacz:m249) - never tacz:modern_kinetic_gun, and gun types "
                    + "(mg / smg / rifle / rpg, or 机枪 / 冲锋枪) are also accepted. "
                    + "3. Speak the truth in your own voice: if something could not be done, "
                    + "say so plainly (e.g. 「我手上只有火箭筒，没有机枪」) - never claim you did "
                    + "something you did not do. "
                    + "4. All combat always runs in the player-like combat task; change how you "
                    + "fight with directives, never by switching to another work mode. "
                    + "5. If a NEW directive conflicts with an OLD one, cancel the old one first "
                    + "(cancel <id>) and say so in one short sentence - do not leave both in force. "
                    + "6. Memory: when a memo task is finished, DELETE that memo entry "
                    + "(flp_memo remove) - never keep finished tasks in her notes. "
                    + "7. To make her USE something continuously, issue only_item with that"
                    + " item's id (a gun's id is its own id such as tacz:fn_fal). switch_item"
                    + " only swaps once and can be overridden by the next action. Cancel any old"
                    + " only_item/ban_item whose item she no longer has before issuing a new one. "
                    + "8. Servants: 「清除/解散/清掉仆从」 (clear, dismiss, get rid of her servants)"
                    + " means the directive dismiss_servants (this KILLS them permanently). "
                    + "「召集/召回/集合仆从」 (call them back, gather them) means recall_servants."
                    + " These two are never the same thing: pick by what the owner asked for,"
                    + " and do not answer either of them with no_summons. "
                    + "9. no_summons = 「不许/别再召唤」. Issue it ONLY when the owner explicitly"
                    + " says not to summon. If the owner did not explicitly forbid summoning,"
                    + " NEVER issue no_summons (and do not keep an old one in force). "
                    + "10. Magic systems: 「用魔法 / 别用刀 / 用法术打」 (the owner names NO system)"
                    + " = magic_only, and that means BOTH systems: 铁魔法 (Iron's Spells 'n"
                    + " Spellbooks) AND 诡厄巫法 (Goety) - they are both magic. Use irons_only"
                    + " ONLY when the owner names 铁魔法, goety_only ONLY when the owner names"
                    + " 诡厄巫法. ★ NEVER issue both of those in the same answer: they are in one"
                    + " mutually-exclusive group, so the second one silently REPLACES the first"
                    + " and she would end up using the system the owner did not ask for."
                    + " If you are unsure which system the owner means, ask instead of guessing."
                    + " (Both sub-class directives also stop non-spell actions, so if she has no"
                    + " spell of that system, say so instead of leaving her standing still.) "
                    // ★★ 第二十六轮（委托方实测：她把自己的刀说成「不算拔刀剑」）
                    + "11. ★★ WHAT SHE CAN USE IS DECIDED BY WHAT SHE OWNS, NOT BY WHAT IS IN"
                    + " HER HAND. Items in her inventory / backpack / curio slots count fully:"
                    + " before any action that needs an item, the executor equips the carrier"
                    + " into her hand by itself (you do NOT need to issue switch_item first)."
                    + " So: if a context says 「not in hand」 but her item list shows she owns it,"
                    + " she DOES own it - say so (「在背包里，用的时候我会先换上」) and never claim"
                    + " she lacks that kind of item, and never invent an explanation such as"
                    + " 「那把不算拔刀剑」. Only 「she owns none at all」 means she cannot do it. "
                    // ★★★ 第二十七轮（委托方实测：她想"仅用先锋聚晶施法"，模型编了一个物品 id）
                    + "12. ★★ A SINGLE SPELL/FOCUS IS ADDRESSED BY ITS **label**, NOT BY AN ITEM"
                    + " ID: use only_focus (only that one) or ban_focus (ban it) with"
                    + " label = the exact `label=` value from her spells list (query it first)."
                    + " ★ NEVER invent a focus item id such as goety:vanguard_focus: foci usually"
                    + " sit INSIDE her focus bag or wand, so they are not separate items and"
                    + " only_item can never reach them (the tool will answer 「她身上没有」 and the"
                    + " order silently expires). If the label you wrote is not one of hers, the"
                    + " reply lists the labels she really has - copy one of those instead.";
        }
    }

    /**
     * ★★ <b>刀与刀技</b>（每回合都看得见）。
     *
     * <h2>★★★ 第二十六轮修的那件事（委托方实测）</h2>
     * > 她：「主人~ 可是我现在**手头没有可用的拔刀剑**呢，仓库里的魔剑「阎魔刀」**好像不算拔刀剑**哦…」
     *
     * 根因是**这个上下文与自己的候选集口径不一致**：这里原来只看**主手**
     * （{@code isUsableBlade}），而她的刀在背包里 ⇒ 上下文说"手上没有可用的刀"；
     * 同时物品清单（{@code flp_items}）把同一把刀列在「拔刀剑」类别里
     * ⇒ 她看到两条互相矛盾的信息，就**自己编了个解释**（"那把不算拔刀剑"）。
     *
     * <p>而决策层**一直**是按"**拥有物**"算候选的（{@code LoopSplit.mainItems} 含背包与饰品，
     * 执行器还有**换手前置**）⇒ 她**能**用背包里的刀放刀技。
     * ★ 这就是 docs/13 第 18 条的纪律（"解析期按拥有物判定，执行期才看主手"）
     *   **没有落到提示词上**：提示词仍然按主手报 ⇒ 她读到的是错的。
     *
     * <p>⇒ 现在报三件事：① 手上有没有；② **身上别处有没有（能不能换手上用）**；
     * ③ 那把刀的状态（损坏/封印/刀技/耀魂）—— 状态**按实际那把刀读**，不是在手上那把。
     */
    private static final class BladeContext extends AbstractMaidContext {
        private BladeContext() {
            super("flp_blade", "Blade and slash art (SA)");
        }

        @Override
        public String getValue(EntityMaid maid) {
            List<SlashBladeExecutors.OwnedBlade> blades = SlashBladeExecutors.ownedBlades(maid);
            if (blades.isEmpty()) {
                return "She owns NO slashblade at all (not in hand, not in her inventory/backpack)."
                        + " A slash art needs a slashblade.";
            }
            SlashBladeExecutors.OwnedBlade current = blades.get(0);
            StringBuilder sb = new StringBuilder();
            if (current.inHand()) {
                sb.append("Blade in hand: ");
            } else {
                // ★★ 这一句就是这次修的落点：**说清"在背包里也算"，并且明说"不要说她没有"**
                sb.append("Blade in hand: none. ★ But she DOES own a slashblade (in ")
                        .append(current.slot().name()).append("): ");
            }
            sb.append(name(current));
            ISlashBladeState state = current.state();
            if (state == null) {
                sb.append(" (no slashboard state - cannot use it)");
                return sb.toString();
            }
            if (state.isBroken()) {
                sb.append(" (broken - cannot use it)");
            }
            if (state.isSealed()) {
                sb.append(" (sealed - cannot use slash arts)");
            }
            var art = state.getSlashArtsKey();
            boolean hasArt = SlashBladeChannel.hasSlashArt(current.stack(), state);
            sb.append("; slash art = ").append(hasArt ? art : "none configured")
                    .append("; proud soul = ").append(state.getProudSoulCount());
            if (!current.inHand()) {
                sb.append(". ★ She CAN use it like this: the executor equips the carrier into her hand"
                        + " automatically before a blade action, so never tell the owner"
                        + " 「你没有拔刀剑」 just because it is not in hand.");
            }
            if (SlashBladeChannel.isRunning(maid)) {
                sb.append("; RIGHT NOW: ").append(SlashBladeChannel.describe(maid));
            }
            // ★ 身上还有别的刀时列一下（"只许用某一把"这类指令要靠身份 id 指名）
            if (blades.size() > 1) {
                List<String> others = new ArrayList<>();
                for (int i = 1; i < blades.size(); i++) {
                    others.add(name(blades.get(i)) + "@" + blades.get(i).slot().name());
                }
                sb.append("; she also owns: ").append(String.join(", ", others));
            }
            return sb.toString();
        }

        /** 「身份 id(人读名)」—— 身份 id 是**刀自己的 id**（`slashblade:yamato`），与枪同一口径。 */
        private static String name(SlashBladeExecutors.OwnedBlade b) {
            String display = b.displayName() == null || b.displayName().isBlank()
                    ? "" : "(" + b.displayName() + ")";
            return b.identityId() + display;
        }
    }

    /** ★ 幻影剑能不能放（三条前置逐条报，别只说"不能"）。 */
    private static final class PhantomSwordContext extends AbstractMaidContext {
        private PhantomSwordContext() {
            super("flp_phantom_sword", "Phantom sword readiness");
        }

        @Override
        public String getValue(EntityMaid maid) {
            // ★★ 第二十六轮：**按"拥有物"判**（与 {@link BladeContext} 同一条修法）——
            //   刀在背包里时，执行器会先把它换到手上，所以"能不能放幻影剑"要按那把刀算。
            List<SlashBladeExecutors.OwnedBlade> blades = SlashBladeExecutors.ownedBlades(maid);
            if (blades.isEmpty()) {
                return "Cannot cast: she owns no slashblade at all.";
            }
            SlashBladeExecutors.OwnedBlade blade = blades.get(0);
            ItemStack stack = blade.stack();
            ISlashBladeState state = blade.state();
            if (state == null) {
                return "Cannot cast: this item is not a slashboard.";
            }
            List<String> missing = new ArrayList<>();
            try {
                if (!SwordType.from(stack).contains(SwordType.BEWITCHED)) {
                    missing.add("the blade is not BEWITCHED (needs to be a cursed/enchanted blade)");
                }
            } catch (RuntimeException | LinkageError e) {
                missing.add("blade type could not be read");
            }
            int power = stack.getEnchantmentLevel(Enchantments.POWER_ARROWS);
            if (power <= 0) {
                missing.add("no Power enchantment");
            }
            int cost = 2;
            try {
                cost = mods.flammpfeil.slashblade.SlashBladeConfig.SUMMON_SWORD_COST.get();
            } catch (RuntimeException | LinkageError ignore) {
                // 配置读不到 ⇒ 用官方默认值 2
            }
            if (state.getProudSoulCount() < cost) {
                missing.add("not enough proud soul (" + state.getProudSoulCount() + "/" + cost + ")");
            }
            String where = blade.inHand() ? "" : " ★ the blade is in " + blade.slot().name()
                    + " - the executor equips it automatically, so this is NOT a blocker.";
            if (missing.isEmpty()) {
                return "Ready: yes (blade " + blade.identityId() + ", Power " + power
                        + ", proud soul " + state.getProudSoulCount() + "/" + cost + ")."
                        + " The phantom sword is a light poke, not a heavy hit." + where;
            }
            return "Ready: no - " + String.join("; ", missing) + "." + where;
        }
    }

    /** ★ 现在生效的战斗指令（她该知道自己被怎么指挥着）。 */
    private static final class ActiveDirectivesContext extends AbstractMaidContext {
        private ActiveDirectivesContext() {
            super("flp_directives", "Combat directives in force");
        }

        @Override
        public String getValue(EntityMaid maid) {
            return DirectiveHolder.describe(maid, maid.level().getGameTime());
        }
    }

    /**
     * ★★ <b>本窗口的击杀</b>（"上一次对话到现在"杀了什么、多少）。
     *
     * <p>★★ 这个 `getValue` 被调用 = 一次对话开始（TLM 正在收集 prompt）
     * ⇒ 顺手**认领窗口**（把本窗口结算进历史、边界前移）。见 {@code MaidMemory}。
     */
    private static final class KillsThisWindowContext extends AbstractMaidContext {
        private KillsThisWindowContext() {
            super("flp_kills_now", "Kills since your last conversation");
        }

        @Override
        public String getValue(EntityMaid maid) {
            var w = MaidMemory.claimWindow(maid);          // ← 边界在这里前移
            return w.total() == 0
                    ? "You killed nothing since the last conversation (lifetime total "
                            + MaidMemory.totalKills(maid) + ")."
                    : "Since the last conversation you killed " + w.total() + ": " + w.describe()
                            + " (lifetime total " + MaidMemory.totalKills(maid) + ").";
        }
    }

    /** ★ 备忘录（她要随时记得自己的任务与大事）。写入/删除由 {@code flp_memo} 工具做。 */
    private static final class MemoContext extends AbstractMaidContext {
        private MemoContext() {
            super("flp_memo", "Your own memo");
        }

        @Override
        public String getValue(EntityMaid maid) {
            var lines = MaidMemory.memo(maid);
            if (lines.isEmpty()) {
                return "Empty (use the flp_memo tool to write down tasks or important events).";
            }
            return String.join(" | ", lines);
        }
    }

    /** ★★ 她自己定的“谁该打”名单（第十六轮）。 */
    private static final class AttackRulesContext extends AbstractMaidContext {
        private AttackRulesContext() {
            super("flp_attack_rules", "Creature groups you set yourself");
        }

        @Override
        public String getValue(EntityMaid maid) {
            return com.touhoulittlemad.fightlikeplayer.compat.ai.MaidAttackList
                    .describeRules(maid);
        }
    }

    // ───────────────────────── 惰上下文（只有被问起才取）─────────────────────────

    /** ★ 以前几次对话窗口的击杀（委托方要的"此前的都作为惰上下文"）。 */
    private static final class PastKillsContext extends AbstractMaidContext {
        private PastKillsContext() {
            super("flp_kills_past", "Kills in earlier conversations");
        }

        @Override
        public String getValue(EntityMaid maid) {
            var hist = MaidMemory.history(maid);
            if (hist.isEmpty()) {
                return "No earlier conversation窗口 recorded yet.";
            }
            List<String> out = new ArrayList<>();
            for (var w : hist) {
                out.add(w.describe() + "（共 " + w.total() + "）");
            }
            return String.join("；之前：", out);
        }
    }

    /** ★ 上次及以前的物品清单（"惰上下文"的那一半）。 */
    private static final class PastItemsContext extends AbstractMaidContext {
        private PastItemsContext() {
            super("flp_items_past", "Her item list in earlier states");
        }

        @Override
        public String getValue(EntityMaid maid) {
            var prev = MaidMemory.previousItems(maid);
            if (prev.isEmpty()) {
                return "No earlier item list recorded yet.";
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < prev.size(); i++) {
                out.add((i == 0 ? "上次" : (i + 1) + " 次前") + "：" + prev.get(i));
            }
            return String.join(" ｜ ", out);
        }
    }

    /** ★ ★附近怪物实况（“获得怪物信息”的那一半；惰上下文，按需查）。 */
    private static final class NearbyMonstersContext extends AbstractMaidContext {
        private NearbyMonstersContext() {
            super("flp_nearby_monsters", "Creatures nearby and their groups");
        }

        @Override
        public String getValue(EntityMaid maid) {
            return com.touhoulittlemad.fightlikeplayer.compat.ai.MaidAttackList
                    .describeNearby(maid, 24.0);
        }
    }

    /** ★ 备忘录的完整读法（写/删走工具；这里只是“再念一遍”）。 */
    private static final class MemoReadContext extends AbstractMaidContext {
        private MemoReadContext() {
            super("flp_memo_full", "Your memo, full list");
        }

        @Override
        public String getValue(EntityMaid maid) {
            var lines = MaidMemory.memo(maid);
            if (lines.isEmpty()) {
                return "Empty";
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                out.add((i + 1) + ". " + lines.get(i));
            }
            return String.join("\n", out);
        }
    }

    // ───────────────────────── 工具类（按需查、长清单）─────────────────────────

    /** ★★ 她现在能做的**全部动作 id**（`only_actions` 的参数来源）。 */
    private static final class ActionIdsContext extends AbstractMaidContext {
        private ActionIdsContext() {
            super("flp_action_ids", "Action ids she can perform");
        }

        @Override
        public String getValue(EntityMaid maid) {
            var resolver = CatalogHolder.resolver();
            if (resolver == null || resolver.actions().isEmpty()) {
                return "Action catalog not loaded.";
            }
            List<String> out = new ArrayList<>();
            for (var a : resolver.actions()) {
                out.add(a.id());
            }
            return String.join(", ", out);
        }
    }

    /** ★ 她的物品（用**全量口径**：可用范围 ∪ 全部 36 格，见 MaidInventory）。 */
    private static final class ItemsContext extends AbstractMaidContext {
        private ItemsContext() {
            super("flp_items", "Her items (all of them, grouped by what they are for)");
        }

        @Override
        public String getValue(EntityMaid maid) {
            // ★★ 第二十五轮（委托方第 2 条）：**从"一串物品"改成"按用途分类"**。
            //   原话：「即从『物品栏里有：A、B、C』到『物品栏内有：拔刀剑相关：A、B；
            //   枪械相关：C、D（每个枪支消耗的弹药种类，以及拥有的弹药及多少（检测弹药箱））』」
            //   ⇒ 分类判据与"枪械弹药那一句"全在 MaidItems / AmmoInfo 里（口径一处）。
            //   ★ 每件物品那一段的格式**没变**（`id(名字)x数量@槽位`）—— 模型要照抄的是 id。
            return com.touhoulittlemad.fightlikeplayer.compat.maid.MaidItems.describe(maid);
        }
    }

    /**
     * ★★ <b>她的仆从</b>（第十七轮续，委托方第 2 条）。
     *
     * <p>委托方原话：「关于女仆对于清理仆从方式的**自知之明**，即女仆知道她的通过
     * '清理仆从指令'来实现的清理仆从，而非什么别的。」
     *
     * <p>⇒ 这个上下文回答三件事：
     * <ol>
     *   <li><b>她名下有哪些</b>（Goety 仆从 + 铁魔法召唤物）；</li>
     *   <li>★ <b>清理它们的唯一方式</b>是那条**瞬间指令** {@code dismiss_servants} ——
     *       她自己那个动作已经改成 {@code role=directive}（永不进候选）⇒ <b>她不会也不能主动清她们</b>；</li>
     *   <li>想让她们回来是 {@code recall_servants}。</li>
     * </ol>
     */
    private static final class ServantsContext extends AbstractMaidContext {
        private ServantsContext() {
            super("flp_servants", "Her servants (and how they can be dismissed)");
        }

        @Override
        public String getValue(EntityMaid maid) {
            java.util.List<String> out = new java.util.ArrayList<>();
            int goety = 0;
            int irons = 0;
            try {
                goety = com.touhoulittlemad.fightlikeplayer.compat.exec.goety.GoetyServantOps
                        .servantCount(maid);
            } catch (RuntimeException | LinkageError ignore) {
                // 没装 goety / API 变了 ⇒ 当 0
            }
            try {
                irons = com.touhoulittlemad.fightlikeplayer.compat.exec.irons.IronsServantOps
                        .countSummons(maid);
            } catch (RuntimeException | LinkageError ignore) {
                // 同上
            }
            int total = goety + irons;
            out.add("Servants under her command: " + total
                    + " (Goety " + goety + " + Iron's summons " + irons + ")");
            if (total > 0) {
                out.add("They are HERS: she summoned them, and they fight for her");
            }
            out.add("★ The ONLY way to dismiss them is the instant directive `dismiss_servants`"
                    + " (ordered by the player or her own chat). Her own dismiss action is"
                    + " disabled (role=directive) - she never clears them by herself, so if they"
                    + " vanish without that order, it was NOT her");
            out.add("Calling them back is the instant directive `recall_servants`");
            return String.join("; ", out);
        }
    }

    /**
     * ★★ <b>她能放什么法术</b>（第十七轮续，委托方第 2 / 2.1 条）。
     *
     * <p>为什么要有它：在此之前，聚晶与法术的清单只有<b>动态指挥 LLM</b> 那边的
     * {@code scene.spells_available} 有；<b>女仆自己（对话 LLM）没有任何出口</b>
     * ⇒ 她答不出"你能放什么法术"，也没法用 {@code ban_focus} 指定某一颗
     * （那条指令的参数就是这里的名字）。
     *
     * <p>名称的来源（服务端可解析，不需要客户端）：
     * <ul>
     *   <li>Goety 聚晶：物品的显示名（lang {@code item.goety.<x>_focus}）；</li>
     *   <li>铁魔法法术：{@code spell.<spellId>}（例如 {@code spell.irons_spellbooks.fireball}）；</li>
     * </ul>
     * ★ 每一条都标出"被指令挡着"（她自己下的 {@code ban_focus} / {@code magic_only} 等）。
     */
    private static final class SpellsContext extends AbstractMaidContext {
        private SpellsContext() {
            super("flp_spells", "Her spells (foci & spellbook)");
        }

        @Override
        public String getValue(EntityMaid maid) {
            List<String> out = new ArrayList<>();
            var bus = com.touhoulittlemad.fightlikeplayer.compat.directive.DirectiveHolder.of(maid);
            // ★★ 第二十七轮（委托方实测）：她"能说出自己有哪些聚晶"，但**说不出能用于指令的 id**
            //   ⇒ 想"仅用先锋聚晶施法"时只能**编一个物品 id**（`goety:vanguard_focus`），
            //     而聚晶常常在聚晶包里、不是独立物品 ⇒ `only_item` 结构上就指不到它。
            //   ⇒ 这份清单现在**开头就写明"针对某一颗要用 label，不是物品 id"**，
            //     并且每一行把 `label=` 放在最显眼的位置（那就是 `only_focus`/`ban_focus` 的参数）。
            out.add("★ 要针对**某一颗**法术/聚晶下指令 ⇒ 用 `only_focus`（只用这一颗）"
                    + "或 `ban_focus`（禁用这一颗），参数名是 `label`，值照抄下面每一行的 "
                    + "`label=`。★ **不要写物品 id** —— 聚晶常常装在聚晶包/魔杖里，"
                    + "那种情况下它不是一件独立物品，`only_item` 永远指不到它。");
            if (modLoaded("goety")) {
                try {
                    for (var f : com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                            .GoetyFocusOps.available(maid, null)) {
                        boolean blocked = !com.touhoulittlemad.fightlikeplayer.decision
                                .DirectiveFilter.focusAllowed(bus,
                                com.touhoulittlemad.fightlikeplayer.decision
                                        .DirectiveFilter.SpellSystem.GOETY,
                                f.category().name(), f.label());
                        out.add("focus: " + f.stack().getHoverName().getString()
                                + " [" + f.category() + "·" + f.source().zh + "]"
                                + " label=" + f.label()
                                + (blocked ? " (BLOCKED by her own directive)" : ""));
                    }
                } catch (RuntimeException | LinkageError e) {
                    out.add("goety foci unreadable: " + e);
                }
            }
            if (modLoaded("irons_spellbooks")) {
                try {
                    for (var o : com.touhoulittlemad.fightlikeplayer.compat.exec.irons
                            .IronsSpells.options(maid, null)) {
                        boolean blocked = !com.touhoulittlemad.fightlikeplayer.decision
                                .DirectiveFilter.focusAllowed(bus,
                                com.touhoulittlemad.fightlikeplayer.decision
                                        .DirectiveFilter.SpellSystem.IRONS,
                                o.intent().name(), o.label());
                        out.add("spell: " + spellName(o.label()) + " (Lv" + o.level() + ", "
                                + o.intent() + ") id=" + o.label()
                                + (blocked ? " (BLOCKED by her own directive)" : ""));
                    }
                } catch (RuntimeException | LinkageError e) {
                    out.add("irons spells unreadable: " + e);
                }
            }
            if (out.isEmpty()) {
                return "She has no usable focus and no spell in her books right now.";
            }
            return String.join("; ", out);
        }
    }

    /**
     * ★ <b>法术/聚晶的文字介绍</b>（委托方第 2 条的「可选 WIP」）—— 惰上下文，按需查。
     *
     * <p>来源（lang 键，服务端可解析）：
     * <ul>
     *   <li>Goety 聚晶：{@code <物品 descriptionId>.info}
     *       （实测 {@code item.goety.vexing_focus.info} = 「生成与你友好的恼鬼，潜行施法会杀死你所有的恼鬼，」）；</li>
     *   <li>铁魔法法术：{@code spell.<spellId>.guide}
     *       （实测 {@code spell.irons_spellbooks.fireball.guide} = 「吟唱后发射一枚火球，命中时爆炸并造成巨大伤害。」）。</li>
     * </ul>
     * ★ 键不存在时 {@code Component#getString()} 会**原样返回键**，我们据此判定"没有介绍"并跳过；
     * ★ 有上限（{@value #MAX_DESC_LINES} 条）—— 这段文字是给模型看的，不能让 prompt 膨胀。
     * ⚠️ 已知边界：语言取的是**服务端**当前语言（单人游戏即客户端语言，中文可读；
     * 专用服务器上是 en_us）—— 这是 WIP，写在这里免得以后当成 bug。
     */
    private static final class SpellDescriptionsContext extends AbstractMaidContext {
        private SpellDescriptionsContext() {
            super("flp_spell_descriptions", "Spell descriptions (foci & spellbook)");
        }

        @Override
        public String getValue(EntityMaid maid) {
            List<String> out = new ArrayList<>();
            if (modLoaded("goety")) {
                try {
                    for (var f : com.touhoulittlemad.fightlikeplayer.compat.exec.goety
                            .GoetyFocusOps.available(maid, null)) {
                        String key = f.stack().getDescriptionId() + ".info";
                        String text = translate(key);
                        if (text != null) {
                            out.add("focus " + f.stack().getHoverName().getString() + ": " + text);
                        }
                        if (out.size() >= MAX_DESC_LINES) {
                            return String.join("; ", out);
                        }
                    }
                } catch (RuntimeException | LinkageError e) {
                    out.add("goety foci unreadable: " + e);
                }
            }
            if (modLoaded("irons_spellbooks")) {
                try {
                    for (var o : com.touhoulittlemad.fightlikeplayer.compat.exec.irons
                            .IronsSpells.options(maid, null)) {
                        String text = translate("spell." + o.label() + ".guide");
                        if (text != null) {
                            out.add("spell " + spellName(o.label()) + ": " + text);
                        }
                        if (out.size() >= MAX_DESC_LINES) {
                            return String.join("; ", out);
                        }
                    }
                } catch (RuntimeException | LinkageError e) {
                    out.add("irons spells unreadable: " + e);
                }
            }
            if (out.isEmpty()) {
                return "No description available for her current spells/foci.";
            }
            return String.join("; ", out);
        }
    }

    /** 介绍条数上限（★ 这段文字会进 prompt，必须有界）。 */
    private static final int MAX_DESC_LINES = 12;

    /** 铁魔法法术的显示名（{@code spell.<id>}）；取不到就退回 id。 */
    private static String spellName(String spellId) {
        String t = translate("spell." + spellId);
        return t == null ? spellId : t;
    }

    /**
     * 按 lang 键取一段**服务端可读**的文本；键不存在 ⇒ {@code null}。
     *
     * <p>判据：{@code Component#getString()} 对未知键会**原样返回键本身**
     * ⇒ 结果等于键就说明没有这条翻译。
     */
    private static String translate(String key) {
        try {
            String s = net.minecraft.network.chat.Component.translatable(key).getString();
            return s == null || s.isBlank() || s.equals(key) ? null : s;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static boolean modLoaded(String id) {
        return net.minecraftforge.fml.ModList.get().isLoaded(id);
    }

    /** ★ 她能接受的指令 id 与含义（模型不必去猜）。 */
    private static final class DirectiveIdsContext extends AbstractMaidContext {
        private DirectiveIdsContext() {
            super("flp_directive_ids", "Combat directive ids");
        }

        @Override
        public String getValue(EntityMaid maid) {
            List<String> out = new ArrayList<>();
            for (DirectiveSpec.Spec s : DirectiveSpec.all()) {
                out.add(s.id() + "(" + s.zh() + ")");
            }
            return String.join(", ", out);
        }
    }
}
