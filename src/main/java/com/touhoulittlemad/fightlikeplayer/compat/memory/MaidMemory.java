package com.touhoulittlemad.fightlikeplayer.compat.memory;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.touhoulittlemad.fightlikeplayer.FightLikePlayer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * ★★ <b>她的战斗记忆</b>（第十六轮）：击杀账本 · 物品履历 · 备忘录。
 *
 * <h2>委托方原话</h2>
 * > 「把**上一次对话到这次对话期间杀了什么，有多少**也纳入女仆 llm 的知晓内容。」
 * > 「**惰上下文**：比如除了这次对话的物品栏内道具，上次及以前的物品栏道具列表可以作为惰上下文；
 * > 以及上文提及的击杀内容，也是同理，**这次的是直接的输入，但此前的都作为可调用但不主动调用的惰上下文**。」
 * > 「**备忘录**：允许女仆对话 llm 自行编辑备忘录，记录任务或重大事件，当然是通过指令写入/删去等操作。」
 *
 * <h2>★★「一次对话」的边界在哪里（取证结论，不是猜的）</h2>
 * TLM 的 `UserPromptContexts#appendContext` 在 `MaidAIChatManager#normalChat` 里被调用，
 * **每条玩家消息恰好一次**，它会遍历所有 **prompt 类**上下文并调用 `getValue(maid)`。
 * ⇒ <b>我们注册的 prompt 类上下文的 `getValue()` 被调用那一刻，就是"上一次对话 → 这一次对话"的分界</b>：
 * 在里面把"本窗口"的击杀/物品结算掉、翻到历史里即可 —— 不依赖任何事件、不需要 mixin、零 tick 延迟
 * （能进**本次**对话的 prompt；tick 轮询做不到，因为 `addContext` 与发 HTTP 是同一 tick 同步完成的）。
 * ★ 惰上下文（`isPromptContext=false`）**只有**模型主动调 `query_game_context` 时才会取值
 * （全树只有两处调 `getContext`：那个工具，以及只遍历 prompt 类的 `appendContext`）
 * ⇒ 它们**不会**消耗对话边界 —— 这正是"惰"的机制保证。
 */
public final class MaidMemory {

    private MaidMemory() {
    }

    // ───────────────────────── 击杀账本 ─────────────────────────

    /** 一次对话窗口里的击杀统计。 */
    public record KillWindow(long atTick, Map<String, Integer> counts) {

        public int total() {
            int n = 0;
            for (int v : counts.values()) {
                n += v;
            }
            return n;
        }

        /** 一行中文（给 prompt / 上下文用）。 */
        public String describe() {
            if (total() == 0) {
                return "没有击杀";
            }
            StringBuilder sb = new StringBuilder();
            counts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> {
                        if (sb.length() > 0) {
                            sb.append('、');
                        }
                        sb.append(e.getKey()).append('×').append(e.getValue());
                    });
            return sb.toString();
        }
    }

    private static final class Book {
        /** 本窗口（还没被任何一次对话"认领"）的击杀。 */
        final Map<String, Integer> pending = new LinkedHashMap<>();
        /** 已认领的历史窗口（**惰上下文**读它），新的在前。 */
        final Deque<KillWindow> history = new ArrayDeque<>();
        int totalKills;
    }

    private static final Map<UUID, Book> KILLS = new WeakHashMap<>();

    /** 历史窗口保留几条（★ 有界：这是要进上下文的东西）。 */
    private static final int MAX_HISTORY_WINDOWS = 8;

    private static Book book(UUID id) {
        return KILLS.computeIfAbsent(id, k -> new Book());
    }

    /**
     * ★ 记一次击杀。**归因判据见 {@code KillListener}**（她亲手 / 她的仆从 / 她的刀气 / 她的法术）。
     */
    public static void noteKill(EntityMaid maid, String what) {
        Book b = book(maid.getUUID());
        b.pending.merge(what == null || what.isBlank() ? "未知生物" : what, 1, Integer::sum);
        b.totalKills++;
    }

    /**
     * ★★ <b>认领本窗口</b>：把"自上次对话以来的击杀"结算成一个窗口并推进边界。
     *
     * <p>★ 由 prompt 类上下文的 `getValue` 调用（那一刻 = 一次对话开始，见类注释）。
     */
    public static KillWindow claimWindow(EntityMaid maid) {
        Book b = book(maid.getUUID());
        Map<String, Integer> snapshot = new LinkedHashMap<>(b.pending);
        b.pending.clear();
        KillWindow w = new KillWindow(maid.level().getGameTime(), snapshot);
        b.history.addFirst(w);
        while (b.history.size() > MAX_HISTORY_WINDOWS) {
            b.history.removeLast();
        }
        return w;
    }

    /** 本窗口**当前**的击杀（不推进边界；诊断用）。 */
    public static KillWindow peekWindow(EntityMaid maid) {
        Book b = book(maid.getUUID());
        return new KillWindow(maid.level().getGameTime(), new LinkedHashMap<>(b.pending));
    }

    /** 历史窗口（**惰上下文**读它）。 */
    public static List<KillWindow> history(EntityMaid maid) {
        return new ArrayList<>(book(maid.getUUID()).history);
    }

    /** 她这一辈子杀了多少（含未认领的）。 */
    public static int totalKills(EntityMaid maid) {
        return book(maid.getUUID()).totalKills;
    }

    // ───────────────────────── 物品履历（惰上下文）─────────────────────────

    private static final class Items {
        String current = "";
        /** 上一版物品清单（新的在前），最多留几条。 */
        final Deque<String> previous = new ArrayDeque<>();
        /** ★ 上一次算出的"物品清单指纹"（第二十四轮，性能）——见 {@link #itemFingerprintChanged}。 */
        long fingerprint;
        /** 指纹是否已经算过一次（第一次必须算，不能拿 0 当"没变"）。 */
        boolean hasFingerprint;
    }

    private static final Map<UUID, Items> ITEMS = new WeakHashMap<>();
    private static final int MAX_ITEM_SNAPSHOTS = 4;

    /**
     * ★★ <b>「她的物品清单变了吗」—— 变之前不要做那套贵的翻译</b>（第二十四轮，性能）。
     *
     * <p>为什么要单独一个入口：调用方（{@code SlashBladeTicker#feedItemSnapshot}）
     * 每 tick 都要喂一次物品清单，而**生成那份清单**（{@link
     * com.touhoulittlemad.fightlikeplayer.compat.maid.MaidSnapshot#possessed}）要为每件物品做
     * 枪身份反射 / 类型名继承链 / 属性附魔 / 显示名 —— 每秒 20 遍是纯浪费（清单其实几分钟才变一次）。
     * ⇒ 先问这里（只比一个 long），没变就别算。
     *
     * @return {@code true} = 变了（调用方应该去生成清单并调 {@link #noteItems}）
     */
    public static boolean itemFingerprintChanged(EntityMaid maid, long fingerprint) {
        Items it = ITEMS.computeIfAbsent(maid.getUUID(), k -> new Items());
        if (it.hasFingerprint && it.fingerprint == fingerprint) {
            return false;
        }
        it.fingerprint = fingerprint;
        it.hasFingerprint = true;
        return true;
    }

    /**
     * ★ 每 tick 喂一次当前物品清单（**只在它变化时**才动）。
     *
     * <p>★ 这样"这次对话的物品栏"是 prompt 类（实时），而"上次及以前的"落在
     * {@link #previousItems} 里当**惰上下文** —— 正是委托方要的形态。
     */
    public static void noteItems(EntityMaid maid, String snapshot) {
        Items it = ITEMS.computeIfAbsent(maid.getUUID(), k -> new Items());
        if (snapshot == null || snapshot.isBlank() || snapshot.equals(it.current)) {
            return;
        }
        if (!it.current.isEmpty()) {
            it.previous.addFirst(it.current);
            while (it.previous.size() > MAX_ITEM_SNAPSHOTS) {
                it.previous.removeLast();
            }
        }
        it.current = snapshot;
    }

    public static String currentItems(EntityMaid maid) {
        Items it = ITEMS.get(maid.getUUID());
        return it == null || it.current.isEmpty() ? "(还没记录)" : it.current;
    }

    /** 上次及以前的物品清单（新的在前）—— **惰上下文**的内容。 */
    public static List<String> previousItems(EntityMaid maid) {
        Items it = ITEMS.get(maid.getUUID());
        return it == null ? List.of() : new ArrayList<>(it.previous);
    }

    // ───────────────────────── 备忘录（持久）─────────────────────────

    /**
     * ★★ <b>备忘录</b>：允许女仆的对话模型自己写/删。
     *
     * <h2>★ 存在哪（取证后的取舍）</h2>
     * 用 Forge 的 {@code Entity#getPersistentData()}（NBT 键 {@code flp:memo}）——
     * ★ 它**随存档保存**，键名自己加前缀防冲突（项目里 {@code GoetyServantOps} 已经在用同一招）。
     * 取舍：TLM 自己的 {@code TaskData} 更"正规"（有 codec 与同步），但它要求
     * **codec 编码成 CompoundTag**（写错就是 ClassCastException），且存档重载后会把数据
     * **无条件同步到客户端**；备忘录是我们自己的私事，用 NBT 更省事、也够稳。
     */
    private static final String MEMO_KEY = "flp:memo";
    private static final int MAX_MEMO_LINES = 24;
    private static final int MAX_MEMO_LEN = 200;

    /** 读全部备忘（新的在前）。 */
    public static List<String> memo(EntityMaid maid) {
        CompoundTag tag = maid.getPersistentData();
        ListTag list = tag.getList(MEMO_KEY, Tag.TAG_STRING);
        List<String> out = new ArrayList<>(list.size());
        for (int i = list.size() - 1; i >= 0; i--) {
            out.add(list.getString(i));
        }
        return out;
    }

    /** 追加一条（返回**可读**结果给模型/玩家）。 */
    public static String memoAdd(EntityMaid maid, String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            return "没写内容 ⇒ 没记";
        }
        if (t.length() > MAX_MEMO_LEN) {
            t = t.substring(0, MAX_MEMO_LEN) + "…";
        }
        CompoundTag tag = maid.getPersistentData();
        ListTag list = tag.getList(MEMO_KEY, Tag.TAG_STRING);
        if (list.size() >= MAX_MEMO_LINES) {
            // ★ 有界：满了就丢**最旧**的一条（并说明，不静默）
            ListTag trimmed = new ListTag();
            for (int i = 1; i < list.size(); i++) {
                trimmed.add(list.get(i));
            }
            list = trimmed;
            tag.put(MEMO_KEY, list);
        }
        list.add(StringTag.valueOf(t));
        tag.put(MEMO_KEY, list);
        FightLikePlayer.LOGGER.info("[FLP][memo] 记下：{}", t);
        return "已记下：" + t;
    }

    /** 删一条（按序号，1 = 最新；或按内容包含匹配）。 */
    public static String memoRemove(EntityMaid maid, String which) {
        List<String> cur = memo(maid);                 // 已倒序（新的在前）
        if (cur.isEmpty()) {
            return "备忘录是空的";
        }
        int index = -1;
        try {
            int n = Integer.parseInt(which == null ? "" : which.strip());
            if (n >= 1 && n <= cur.size()) {
                index = n - 1;
            }
        } catch (NumberFormatException ignore) {
            for (int i = 0; i < cur.size(); i++) {
                if (cur.get(i).contains(which == null ? "" : which.strip())) {
                    index = i;
                    break;
                }
            }
        }
        if (index < 0) {
            return "没找到要删的那一条（可以给序号，或给内容里的一段字）";
        }
        String removed = cur.remove(index);
        writeBack(maid, cur);
        return "已删掉：" + removed;
    }

    /** 清空。 */
    public static String memoClear(EntityMaid maid) {
        int n = memo(maid).size();
        writeBack(maid, List.of());
        return "已清空备忘录（" + n + " 条）";
    }

    private static void writeBack(EntityMaid maid, List<String> newestFirst) {
        ListTag list = new ListTag();
        for (int i = newestFirst.size() - 1; i >= 0; i--) {     // 存"旧的在前"
            list.add(StringTag.valueOf(newestFirst.get(i)));
        }
        maid.getPersistentData().put(MEMO_KEY, list);
    }

    // ───────────────────────── 诊断 ─────────────────────────

    /** 一行摘要（命令/日志）。 */
    public static String describe(EntityMaid maid) {
        KillWindow now = peekWindow(maid);
        return "本窗口击杀 " + now.total() + "（" + now.describe() + "）"
                + "　累计 " + totalKills(maid)
                + "　历史窗口 " + history(maid).size()
                + "　备忘录 " + memo(maid).size() + " 条";
    }

    /** 清干净（女仆卸载/死亡时；★ 与弹簧、指令同样的纪律）。 */
    public static void forget(UUID maidId) {
        KILLS.remove(maidId);
        ITEMS.remove(maidId);
    }

    /** 所有记过的女仆（诊断用）。 */
    public static Set<String> trackedKeys() {
        Set<String> s = new LinkedHashSet<>();
        s.add("kills=" + KILLS.size());
        s.add("items=" + ITEMS.size());
        return s;
    }
}
