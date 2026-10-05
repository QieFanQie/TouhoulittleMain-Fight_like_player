package com.touhoulittlemad.fightlikeplayer;

import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 载体探测（Carrier Detection）。
 *
 * <p><b>为什么需要它</b>：本模组的动作空间完全由「装了哪些载体模组」决定。
 * 出问题时第一个要问的就是「当时到底装了哪些载体」——如果不打日志，
 * 就只能靠人去回忆，而验收环境会变（P0/P1/P2 三层）。
 * ⇒ <b>把环境变成日志</b>。
 *
 * <p>每个载体的 modid、其"操作来源上限"与可达性结论都来自调研文档，
 * 这里只做<b>存在性</b>探测，不做任何行为（保持零硬依赖）。
 *
 * @see <a href="../../../../../../../docs/01-操作清单.md">docs/01-操作清单.md</a>
 */
public final class CarrierDetector {

    /**
     * 载体清单：modid → 说明。
     * <p>顺序 = 日志里的显示顺序，按"对本模组的重要性"排。
     */
    private static final Map<String, String> CARRIERS = new LinkedHashMap<>();

    static {
        // ── 三个主载体 ──
        CARRIERS.put("slashblade", "拔刀剑重锋：剑技（左键派生）+ SA（右键蓄力）｜31 个操作，R-A 14 / R-B 10 / R-C 7");
        CARRIERS.put("goety", "诡厄巫法：聚晶（1 族 + 5 变体维度 + 124 参数）+ 仆从管理｜绝大多数 R-A");
        CARRIERS.put("irons_spellbooks", "铁魔法：94 个法术（INSTANT 41 / LONG 43 / CONTINUOUS 10）｜recast 为 R-B");

        // ── 载体的前置（会影响载体本身能否工作）──
        CARRIERS.put("curios", "饰品槽（Goety 的 mandatory 前置 + 铁魔法的前置）");
        CARRIERS.put("geckolib", "模型库（铁魔法前置）");
        CARRIERS.put("caelus", "铁魔法前置");
        CARRIERS.put("playeranimator", "铁魔法前置（player-animation-lib）");

        // ── 扩展/兼容观察 ──
        CARRIERS.put("tacz", "TaCZ 枪械：10 个操作全对 LivingEntity 开放（R-A），TLM 只调了 5 个");
        CARRIERS.put("pillagers_gun", "Pillager's Gun：独立枪械体系，TLM 完全不识别（R-B 可支持）");
        CARRIERS.put("goetyawaken", "Goety 觉醒：+4 个长按法术（验证接口闭包自动扩表）");
        CARRIERS.put("goety_ladder", "Goety 阶梯：+2 个长按法术");
        CARRIERS.put("goety_cataclysm", "Goety 灾变：+1 个长按法术");
        CARRIERS.put("goetytwilight", "Goety 暮色：+4 个长按法术");

        // ── 需要隔离测试的重叠实现 ──
        CARRIERS.put("touhou_little_maid_spell", "⚠️ 万法皆通：与本模组功能重叠，同场会注册两套施法任务");
        CARRIERS.put("goetytuner", "调律师：本模组的机制来源（MIT，同一作者），同场会加自己的 Boss/仆从");
    }

    /** 会与本模组功能重叠、建议隔离测试的 modid。 */
    private static final List<String> CONFLICTING = List.of(
            "touhou_little_maid_spell", "goetytuner"
    );

    private CarrierDetector() {
    }

    /**
     * 探测并打印一次。只在 mod 构造期调用一次。
     */
    public static void detectAndLog() {
        ModList mods = ModList.get();
        List<String> present = new ArrayList<>();
        List<String> absent = new ArrayList<>();

        for (String id : CARRIERS.keySet()) {
            if (mods.isLoaded(id)) {
                present.add(id);
            } else {
                absent.add(id);
            }
        }

        FightLikePlayer.LOGGER.info("[FLP] ================= 载体探测 =================");
        FightLikePlayer.LOGGER.info("[FLP] 已安装载体 {} / {}", present.size(), CARRIERS.size());
        for (String id : present) {
            FightLikePlayer.LOGGER.info("[FLP]   ✔ {} — {}", id, CARRIERS.get(id));
        }
        if (!absent.isEmpty()) {
            FightLikePlayer.LOGGER.info("[FLP] 未安装 {} 个：{}", absent.size(), String.join(", ", absent));
        }

        // 重叠实现的显式告警：出问题时先排除"是不是它干的"
        List<String> clashes = present.stream().filter(CONFLICTING::contains).toList();
        for (String id : clashes) {
            FightLikePlayer.LOGGER.warn("[FLP] ⚠️ 检测到与本模组功能重叠的模组 `{}`。"
                    + "两者都会向女仆注册施法/战斗任务，出现异常行为时请先单独禁用一方以定位来源。", id);
        }

        // P0 的判定依据：三个主载体一个都没有
        boolean anyPrimary = present.stream()
                .anyMatch(id -> id.equals("slashblade") || id.equals("goety") || id.equals("irons_spellbooks"));
        if (!anyPrimary) {
            FightLikePlayer.LOGGER.info("[FLP] 当前无任何主载体 ⇒ 处于 P0（最小集）状态："
                    + "本模组应只提供不依赖载体的通用动作，且不得影响 TLM 原生行为。");
        }
        FightLikePlayer.LOGGER.info("[FLP] ==============================================");
    }

    /** @return 已安装的载体 modid 列表（供行为层剪枝用）。 */
    public static List<String> presentCarriers() {
        ModList mods = ModList.get();
        return CARRIERS.keySet().stream().filter(mods::isLoaded).toList();
    }
}
