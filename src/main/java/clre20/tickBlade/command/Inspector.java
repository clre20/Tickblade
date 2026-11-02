package clre20.tickBlade.command;

import clre20.tickBlade.TickBlade;
import clre20.tickBlade.ai.AiPauser;
import clre20.tickBlade.config.Messages;
import clre20.tickBlade.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

/**
 * /tickb inspect：看著一隻生物（可隔著玻璃、圍欄等方塊），顯示 TickBlade 對它的判斷。
 * 判斷與實際掃描使用同一套規則（AiPauser.diagnose、DespawnFixer.check），只讀取不改變狀態。
 */
final class Inspector {

    private Inspector() {
    }

    static void inspect(TickBlade plugin, Player p) {
        Messages m = plugin.messages();
        Settings s = plugin.settings();
        Entity target = p.getTargetEntity(s.inspectMaxDistance, true);
        if (!(target instanceof Mob mob)) {
            m.send(p, "inspect.no-target", "distance", s.inspectMaxDistance);
            return;
        }

        // 標題：種類用客戶端語言顯示；名字是玩家取的，只當純文字
        Component title = m.component("inspect.header", "type", "{type}", "name", nameSuffix(m, mob));
        title = title.replaceText(b -> b.matchLiteral("{type}").replacement(Component.translatable(mob.getType().translationKey())));
        p.sendMessage(title);
        m.sendPlain(p, "inspect.location", "world", mob.getWorld().getName(),
                "x", mob.getLocation().getBlockX(), "y", mob.getLocation().getBlockY(), "z", mob.getLocation().getBlockZ());

        // AI 暫停
        AiPauser.Diagnosis d = plugin.aiPauser().diagnose(mob);
        switch (d.state()) {
            case "paused" -> m.sendPlain(p, "inspect.state-paused", "reason",
                    m.raw(d.pausedReason() != null && d.pausedReason() == AiPauser.REASON_CONFINED
                            ? "inspect.reason-confined" : "inspect.reason-crowd"));
            case "other" -> m.sendPlain(p, "inspect.state-other");
            case "disabled" -> m.sendPlain(p, "inspect.state-disabled");
            default -> m.sendPlain(p, "inspect.state-awake");
        }
        if (!d.state().equals("other") && !d.state().equals("disabled")) {
            m.sendPlain(p, "inspect.crowd", "radius", s.crowdRadius, "count", d.crowd(),
                    "pause", s.crowdPauseAt, "resume", s.crowdResumeAt);
            m.sendPlain(p, d.confined() ? "inspect.confined-yes" : "inspect.confined-no");
            String why = d.why() == null ? "" : m.raw("inspect.why." + d.why().key(), d.why().vars());
            m.sendPlain(p, "inspect.verdict." + d.verdict(), "why", why);
            boolean expectPaused = d.verdict().startsWith("pause") || d.verdict().equals("keep-crowd");
            if (expectPaused != d.state().equals("paused")) {
                m.sendPlain(p, "inspect.pending", "seconds", s.aiScanIntervalSeconds);
            }
        }

        // 消失判斷
        String despawn;
        if (!(mob instanceof Monster mon)) {
            despawn = m.raw("inspect.despawn.not-monster");
        } else {
            String key = plugin.despawnFixer().check(mon);
            if (key == null) key = mob instanceof Enderman ? "will-fix-block" : "will-fix-item";
            despawn = m.raw("inspect.despawn." + key);
        }
        p.sendMessage(Messages.legacy(m.raw("inspect.despawn.label") + despawn));
    }

    private static String nameSuffix(Messages m, Mob mob) {
        Component name = mob.customName();
        if (name == null) return "";
        // 防止玩家用命名牌塞顏色碼
        String plain = PlainTextComponentSerializer.plainText().serialize(name).replace('&', '＆').replace('§', ' ');
        if (plain.length() > 32) plain = plain.substring(0, 32) + "…";
        return m.raw("inspect.name", "name", plain);
    }
}
