package com.touhoulittlemad.fightlikeplayer.carrier;

import com.google.gson.JsonObject;

import java.util.Map;

/**
 * 清单的<b>数据来源</b> —— 把"从哪读"与"怎么解析"分开。
 *
 * <h2>为什么要这层抽象（M3）</h2>
 * 载体解析与决策核心都是<b>纯 JVM</b>的，但清单的来源有三种，且它们互相冲突：
 *
 * <table border="1">
 *   <tr><th>来源</th><th>用途</th><th>优先级</th></tr>
 *   <tr><td>内置（jar 内 {@code data/fight_like_player/catalog/}）</td><td>默认数据</td><td>低</td></tr>
 *   <tr><td>数据包 / 资源包（同路径覆盖）</td><td>整合包作者改数值</td><td><b>高</b></td></tr>
 *   <tr><td>磁盘目录 {@code catalog/}（开发时）</td><td>自测与本地调试</td><td>——</td></tr>
 * </table>
 *
 * <p>⇒ 把这三种都收敛成"<b>一组 (文件名 → JsonObject)</b>"，解析逻辑就<b>只有一份</b>，
 * 且可以在没有 Minecraft 的环境里用假来源做断言。
 *
 * <h2>覆盖语义</h2>
 * 与 Minecraft 资源包一致：<b>同名文件、高优先级者胜</b>（实现见各来源）。
 * 因此整合包作者只需放一个 {@code slashblade.json} 就能只覆盖那一个文件。
 *
 * @see CatalogLoader
 * @see <a href="../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §8 (M3)</a>
 */
public interface CatalogSource {

    /**
     * ★★ <b>资源路径（传给 {@code ResourceManager#listResources} 的那个）</b>。
     *
     * <p>⚠️ <b>这里曾经写错，导致清单永远加载不了</b>（第一次进游戏实测时暴露）：
     * <pre>
     *   文件位置：data/fight_like_player/catalog/maid_native.json
     *   对应的 ResourceLocation：ResourceLocation("fight_like_player", "catalog/maid_native.json")
     *                            ↑ namespace                     ↑ path ★ 不含 data/&lt;ns&gt;/ 前缀
     * </pre>
     * 我原先写的是 {@code "data/fight_like_player/catalog"}（**带前缀**）⇒
     * {@code listResources} 找的是"以 data/fight_like_player/catalog 开头的 path" ⇒
     * <b>永远匹配不到任何文件</b> ⇒ 清单为空 ⇒ 决策层什么都不做。
     *
     * <p>⇒ <b>路径参数只写 namespace 之后的部分</b>（与 vanilla 的
     * {@code listResources("functions", …)} / {@code listResources("structures", …)} 一致）。
     */
    String RESOURCE_PATH = "catalog";

    /** 人读用的完整位置（仅用于日志与报错，<b>不要</b>传给 {@code listResources}）。 */
    String RESOURCE_DIR_DISPLAY = "data/fight_like_player/catalog";

    /** 载体规则的文件名（与动作数据同目录，便于一起覆盖）。 */
    String CARRIERS_FILE = "carriers.json";

    /**
     * ★ <b>评分表的文件名</b> —— 动作 → {@code v_x} 的权威来源。
     *
     * <p><b>为什么与动作数据分开存放</b>（委托方要求）：
     * <ul>
     *   <li>{@code data/*.json} 描述"这一招<b>是什么</b>"（事实，来自勘测，很少变）；</li>
     *   <li>{@code vectors.json} 描述"这一招<b>值多少</b>"（设计判断，要反复复核、重打分、实测校准）。</li>
     * </ul>
     * 两者<b>变更频率与复核者都不同</b>，混在一起会让"复核打分"变成要翻六个文件的事。
     * ⇒ 独立成表后，**一次 diff 就能看清"这轮改了哪些分数"**。
     */
    String VECTORS_FILE = "vectors.json";

    /**
     * ★★ <b>执行器账本的文件名</b> —— {@code kind → handles[]}，标明<b>哪些动作真的被实现过</b>。
     *
     * <p>★ <b>为什么解析器必须读它</b>（2026-10-01 实测暴露的结构性缺陷）：
     * 清单里 77 条动作中有一批是"<b>可达（RA/RB）、有向量、但没有执行器</b>"。
     * 它们照样进候选集 ⇒ 被选中 ⇒ 执行器回报 {@code NO_EXECUTOR} ⇒
     * <b>按设计"没做出来就不扣代价"⇒ 弹簧丝毫不移动 ⇒ 下一周期再次选中它</b>。
     * <pre>
     *   quick_charge(59 次) / summoned_sword(52) / combo_b_finish(50) /
     *   judgement_cut_just(49) / tacz:melee(49) / transfer_servant_ownership(35) / spiral_swords(11)
     *   —— 全部来自委托方 2026-10-01 的真实会话日志
     * </pre>
     * 观感就是「<b>女仆站着不动，法术也永远不放</b>」：真正能做的动作被这些"幽灵动作"饿死了。
     * ⇒ <b>没实现的动作不是"一个选项"，而是一个缺口</b>，必须在解析期就挡住。
     */
    String EXECUTORS_FILE = "executors.json";

    /**
     * ★★ <b>W32：法术意图表</b> —— {@code 法术类简单名 → 意图/类别}。
     *
     * <p>为什么它是<b>数据</b>而不是代码里的关键词表：运行期只能看到类名，
     * 而类名是有限的、可以从 jar 里一次枚举完的（铁魔法 113 + Goety 105）。
     * ⇒ 一次列清、逐条复核、写进数据文件；运行时只查表。
     * 新版本加了法术时 {@code tools/audit_spell_intent.py} 会报"有 N 条没分类"而不是静默兜底。
     */
    String SPELL_INTENT_FILE = "spell_intent.json";

    /**
     * 已按优先级解析好的文件集合：<b>文件名 → 内容</b>。
     * <p>不含子目录；文件名形如 {@code slashblade.json} / {@code carriers.json} / {@code vectors.json}。
     */
    Map<String, JsonObject> files();

    /** 来源描述，用于启动日志（"每个文件来自哪个包"是排查覆盖问题的第一手信息）。 */
    String describe();
}
