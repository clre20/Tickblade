package clre20.tickBlade.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

/** messages.yml 讀取與變數替換 */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private YamlConfiguration yaml = new YamlConfiguration();
    private String prefix = "";

    public void load(YamlConfiguration yaml, String prefix) {
        this.yaml = yaml;
        this.prefix = prefix == null ? "" : prefix;
    }

    /** 原始文字（含 & 顏色碼，已替換變數） */
    public String raw(String key, Object... vars) {
        String s = yaml.getString(key);
        if (s == null) s = key;
        return apply(s, vars);
    }

    public List<String> rawList(String key, Object... vars) {
        List<String> list = yaml.getStringList(key);
        List<String> out = new ArrayList<>(list.size());
        for (String s : list) out.add(apply(s, vars));
        return out;
    }

    public Component component(String key, Object... vars) {
        return noItalic(LEGACY.deserialize(raw(key, vars)));
    }

    public List<Component> components(String key, Object... vars) {
        List<String> lines = rawList(key, vars);
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            // 單一行變數可能含換行（例如實體種類清單），拆開成多行
            for (String part : line.split("\n", -1)) out.add(noItalic(LEGACY.deserialize(part)));
        }
        return out;
    }

    public static Component legacy(String text) {
        return noItalic(LEGACY.deserialize(text));
    }

    /** 不加前綴直接傳送 */
    public void sendPlain(CommandSender to, String key, Object... vars) {
        to.sendMessage(LEGACY.deserialize(raw(key, vars)));
    }

    /** 加前綴傳送 */
    public void send(CommandSender to, String key, Object... vars) {
        to.sendMessage(LEGACY.deserialize(prefix + raw(key, vars)));
    }

    public void sendRaw(CommandSender to, String text) {
        to.sendMessage(LEGACY.deserialize(prefix + text));
    }

    /** 給後台用：去掉顏色碼 */
    public String console(String key, Object... vars) {
        return stripColor(raw(key, vars));
    }

    public static String stripColor(String s) {
        return s.replaceAll("(?i)&[0-9A-FK-ORX]", "");
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static String apply(String s, Object... vars) {
        if (vars.length == 0 || s.indexOf('{') < 0) return s;
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i + 1 < vars.length; i += 2) {
            String token = "{" + vars[i] + "}";
            String value = String.valueOf(vars[i + 1]);
            int idx;
            int from = 0;
            while ((idx = sb.indexOf(token, from)) >= 0) {
                sb.replace(idx, idx + token.length(), value);
                from = idx + value.length();
            }
        }
        return sb.toString();
    }
}
