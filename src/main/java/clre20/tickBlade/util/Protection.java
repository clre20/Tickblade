package clre20.tickBlade.util;

import clre20.tickBlade.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;

/** A 與 D 共用的保護規則：其他插件的生物、命名牌標記 */
public final class Protection {

    public static final String NAMESPACE = "tickblade";

    /** 只做紀錄、不管理生物的插件命名空間（CoreProtect 替每隻生成的生物寫 coreprotect:spawn 追蹤紀錄） */
    private static final java.util.Set<String> LOGGING_NAMESPACES = java.util.Set.of("coreprotect");

    private Protection() {
    }

    /** 其他插件或資料包管理的生物（NPC、MythicMobs、Shopkeepers…）；掃描時請改用 ForeignCache */
    public static boolean isForeign(Entity e, Settings s) {
        return foreignMarker(e, s) != null;
    }

    /**
     * 讓這隻生物被當成「其他插件或資料包管理」的標記，例如 {@code tag:xxx}、{@code pdc:plugin:key}、{@code metadata:NPC}；
     * null = 沒有。protection.ignored-* 列出的標記不算（某些插件會替所有生物加標記）。
     */
    public static String foreignMarker(Entity e, Settings s) {
        if (s.skipScoreboardTags) {
            for (String tag : e.getScoreboardTags()) {
                if (!s.ignoredScoreboardTags.contains(tag)) return "tag:" + tag;
            }
        }
        if (s.skipForeignPdc) {
            for (NamespacedKey k : e.getPersistentDataContainer().getKeys()) {
                String ns = k.getNamespace();
                if (ns.equals(NAMESPACE) || LOGGING_NAMESPACES.contains(ns) || s.ignoredPdcNamespaces.contains(ns)) continue;
                return "pdc:" + k;
            }
        }
        // metadata 查詢每個鍵都要組一次字串，放最後
        for (String key : s.metadataKeys) {
            if (e.hasMetadata(key)) return "metadata:" + key;
        }
        return null;
    }

    public static boolean hasKeyword(Entity e, Settings s) {
        if (s.nameKeyword == null || s.nameKeyword.isEmpty()) return false;
        Component name = e.customName();
        if (name == null) return false;
        return PlainTextComponentSerializer.plainText().serialize(name).contains(s.nameKeyword);
    }
}
