package clre20.tickBlade.util;

import clre20.tickBlade.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;

/** A 與 D 共用的保護規則：其他插件的生物、命名牌標記 */
public final class Protection {

    public static final String NAMESPACE = "tickblade";

    private Protection() {
    }

    /** 其他插件或資料包管理的生物（NPC、MythicMobs、Shopkeepers…） */
    public static boolean isForeign(Entity e, Settings s) {
        if (s.skipScoreboardTags && !e.getScoreboardTags().isEmpty()) return true;
        for (String key : s.metadataKeys) {
            if (e.hasMetadata(key)) return true;
        }
        if (s.skipForeignPdc) {
            for (NamespacedKey k : e.getPersistentDataContainer().getKeys()) {
                if (!k.getNamespace().equals(NAMESPACE)) return true;
            }
        }
        return false;
    }

    public static boolean hasKeyword(Entity e, Settings s) {
        if (s.nameKeyword == null || s.nameKeyword.isEmpty()) return false;
        Component name = e.customName();
        if (name == null) return false;
        return PlainTextComponentSerializer.plainText().serialize(name).contains(s.nameKeyword);
    }
}
