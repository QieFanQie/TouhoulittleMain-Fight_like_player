package com.touhoulittlemad.fightlikeplayer.compat.event;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;
import com.touhoulittlemad.fightlikeplayer.config.FlpConfig;
import com.touhoulittlemad.fightlikeplayer.decision.BossChatPolicy;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ★★ <b>击败强敌 ⇒ 主动触发一次女仆 LLM</b>（第十七轮续；委托方 2026-10-05 第 2 条）。
 *
 * <h2>入口（TLM 公开 API，javap 取证）</h2>
 * <pre>
 *   EntityMaid#getAiChatManager()                      → MaidAIChatManager
 *   MaidAIChatManager#chat(String, ChatClientInfo, ServerPlayer)
 *   ChatClientInfo.fromMaid(EntityMaid)
 * </pre>
 * ⇒ 我们**不自己造对话通道**：把"战斗记录"当成一轮用户消息交给 TLM，
 * 她的回复由 TLM 按它自己的规矩说给主人（TTS / 聊天气泡也一样生效）。
 *
 * <h2>三道把守（缺一条都会变成骚扰）</h2>
 * <ol>
 *   <li><b>配置开关</b>：{@code llm.bossChat}（默认开）+ {@code llm.bossHealthThreshold}（默认 100）；</li>
 *   <li><b>她配没配 LLM</b>：{@code getLLMSite() == null} ⇒ 静默跳过（否则 TLM 会往主人聊天栏里
 *       打印"AI 聊天被关闭/没配 key"这类提示 ⇒ 观感是我在刷屏）；</li>
 *   <li><b>主人在不在线</b>：{@code getOwner() instanceof ServerPlayer} —— 对话的接收者就是他，
 *       不在线时没有可说话的对象 ⇒ 跳过（并记一条 debug）。</li>
 * </ol>
 * ★ 另外：每条女仆一条**冷却窗口**（{@link BossChatPolicy#DEBOUNCE_TICKS}）——连杀不刷屏。
 */
public final class BossChatTrigger {

    private BossChatTrigger() {
    }

    /** 每女仆：上一次"因击败强敌而说话"的世界时间（0 = 还没说过）。 */
    private static final Map<UUID, Long> LAST_CHAT = new HashMap<>();

    /**
     * 她刚刚击败了一个东西 —— 如果是"强敌"就主动说一句。
     *
     * @param now 世界时间（tick）
     */
    public static void onKill(EntityMaid maid, LivingEntity dead, long now) {
        if (maid == null || dead == null) {
            return;
        }
        if (!FlpConfig.get(FlpConfig.LLM_BOSS_CHAT, Boolean.TRUE)) {
            return;
        }
        int threshold = FlpConfig.get(FlpConfig.LLM_BOSS_HEALTH_THRESHOLD, 100);
        boolean vanillaBoss = dead instanceof EnderDragon || dead instanceof WitherBoss;
        if (!BossChatPolicy.isBossLevel(dead.getMaxHealth(), vanillaBoss, threshold)) {
            return;
        }
        Long last = LAST_CHAT.get(maid.getUUID());
        if (last != null && BossChatPolicy.onCooldown(last, now)) {
            FightLikePlayer.LOGGER.debug("[FLP][boss] 跳过这次战报：距上次 {} tick（窗口 {}）",
                    now - last, BossChatPolicy.DEBOUNCE_TICKS);
            return;
        }
        // ② 她配了 LLM 吗（没配就静默跳过 —— 别让 TLM 往主人聊天栏里打印配置提示）
        var manager = maid.getAiChatManager();
        try {
            if (manager == null || manager.getLLMSite() == null) {
                FightLikePlayer.LOGGER.debug("[FLP][boss] 跳过战报：她没配置 LLM");
                return;
            }
        } catch (RuntimeException | LinkageError e) {
            FightLikePlayer.LOGGER.debug("[FLP][boss] 跳过战报：LLM 站点读取失败（{}）", e.toString());
            return;
        }
        // ③ 主人在线吗（对话要说给主人听）
        if (!(maid.getOwner() instanceof ServerPlayer owner)) {
            FightLikePlayer.LOGGER.debug("[FLP][boss] 跳过战报：主人不在线");
            return;
        }
        String prompt = BossChatPolicy.promptFor(dead.getName().getString(), dead.getMaxHealth());
        try {
            manager.chat(prompt,
                    com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo
                            .fromMaid(maid),
                    owner);
            LAST_CHAT.put(maid.getUUID(), now);
            FightLikePlayer.LOGGER.info("[FLP][boss] 她击败了强敌「{}」（{} 血）⇒ 已触发一次对话",
                    dead.getName().getString(), (long) dead.getMaxHealth());
        } catch (RuntimeException | LinkageError e) {
            // ★ 说话失败绝不能影响战斗（第三方 API 可能抛）
            FightLikePlayer.LOGGER.info("[FLP][boss] 触发对话失败（忽略）：{}", e.toString());
        }
    }

    /** 女仆被卸载/死亡时清掉冷却记录。 */
    public static void forget(UUID maidId) {
        LAST_CHAT.remove(maidId);
    }
}
