package com.touhoulittlemad.fightlikeplayer.compat.data;

import com.google.gson.JsonObject;
import com.touhoulittlemad.fightlikeplayer.carrier.CatalogSource;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 从 Minecraft 的 {@link ResourceManager} 读清单 —— <b>支持数据包覆盖与 {@code /reload}</b>。
 *
 * <h2>路径约定</h2>
 * <pre>
 *   data/fight_like_player/catalog/maid_native.json
 *   data/fight_like_player/catalog/slashblade.json
 *   ...
 *   data/fight_like_player/catalog/carriers.json
 * </pre>
 * 内置数据随 jar 一起发布（见 {@code build.gradle} 的 {@code processResources}）；
 * 整合包作者只要在数据包里放同名文件即可覆盖，<b>逐文件生效</b>。
 *
 * <h2>覆盖优先级</h2>
 * 与 Minecraft 一致：{@link ResourceManager#getResourceStack} 返回的顺序是
 * <b>低优先级在前</b> ⇒ 取<b>最后一个</b>（最高优先级）作为胜者。
 *
 * <p>★ 本类<b>只做"读"，不做"解析"</b>：解析在纯 JVM 的
 * {@link com.touhoulittlemad.fightlikeplayer.carrier.CatalogLoader} 里，
 * 因此解析逻辑仍可离线自测。
 *
 * @see com.touhoulittlemad.fightlikeplayer.carrier.CatalogSource
 * @see <a href="../../../../../../../../docs/09-动作空间与评分体系.md">docs/09 §8 (M3)</a>
 */
public final class ResourceManagerCatalogSource implements CatalogSource {

    private final ResourceManager resourceManager;
    private final Map<String, JsonObject> cache = new TreeMap<>();
    private final Map<String, String> origins = new TreeMap<>();
    private final List<String> problems = new ArrayList<>();

    public ResourceManagerCatalogSource(ResourceManager resourceManager) {
        this.resourceManager = resourceManager;
        discover();
    }

    /** 扫描约定目录，按优先级解析出每个文件名的胜者。 */
    private void discover() {
        // ★★ 用 RESOURCE_PATH（= "catalog"），**不带** data/<ns>/ 前缀 ——
        //    否则 listResources 永远匹配不到。
        //    （这个错误让清单整整一版都加载不了：文件在 data/fight_like_player/catalog/，
        //      但对应的 ResourceLocation 的 path 是 "catalog/xxx.json"。）
        Map<ResourceLocation, Resource> found = resourceManager.listResources(
                CatalogSource.RESOURCE_PATH,
                loc -> loc.getPath().endsWith(".json"));

        if (found.isEmpty()) {
            // ★ 明确报出来：这是"清单为什么是空的"的第一手线索
            problems.add("listResources(\"" + CatalogSource.RESOURCE_PATH + "\") 返回空 —— 期望 jar 内有 "
                    + CatalogSource.RESOURCE_DIR_DISPLAY + "/*.json");
        }

        // ★ 按文件名分组，并保留"高优先级胜出"的语义
        Map<String, List<Map.Entry<ResourceLocation, Resource>>> byName = new TreeMap<>();
        for (Map.Entry<ResourceLocation, Resource> e : found.entrySet()) {
            String name = fileName(e.getKey());
            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(e);
        }

        for (Map.Entry<String, List<Map.Entry<ResourceLocation, Resource>>> e : byName.entrySet()) {
            String name = e.getKey();
            try {
                // getResourceStack：低优先级在前 ⇒ 取最后一个
                List<Resource> stack = resourceManager.getResourceStack(e.getValue().get(0).getKey());
                Resource winner = stack.isEmpty() ? e.getValue().get(0).getValue()
                        : stack.get(stack.size() - 1);
                try (Reader r = winner.openAsReader()) {
                    JsonObject json = GsonHelper.parse(r);
                    cache.put(name, json);
                    origins.put(name, winner.sourcePackId());
                }
            } catch (IOException | RuntimeException ex) {
                // ★ 一个文件坏了不能拖垮整张清单 —— 记下来并继续，但要【显式可见】
                problems.add(name + " 读取失败：" + ex);
            }
        }
    }

    private static String fileName(ResourceLocation loc) {
        String path = loc.getPath();
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    @Override
    public Map<String, JsonObject> files() {
        return cache;
    }

    /** ★ 每个文件来自哪个数据包 —— 排查"我的覆盖没生效"的第一手信息。 */
    public Map<String, String> origins() {
        return origins;
    }

    /** 读取失败的文件（不应静默）。 */
    public List<String> problems() {
        return problems;
    }

    @Override
    public String describe() {
        if (cache.isEmpty()) {
            return "★ 未找到任何清单文件（listResources(\"" + CatalogSource.RESOURCE_PATH
                    + "\") 为空；期望 jar 内有 " + CatalogSource.RESOURCE_DIR_DISPLAY + "/*.json）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("资源管理器，").append(cache.size()).append(" 个文件");
        for (Map.Entry<String, String> e : origins.entrySet()) {
            sb.append("\n      ").append(e.getKey()).append("  ← ").append(e.getValue());
        }
        for (String p : problems) {
            sb.append("\n      ⚠ ").append(p);
        }
        return sb.toString();
    }
}
