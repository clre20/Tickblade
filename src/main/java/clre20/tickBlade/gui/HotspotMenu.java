package clre20.tickBlade.gui;

import clre20.tickBlade.TickBlade;
import clre20.tickBlade.config.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 熱點區塊介面（6 排）。
 * InventoryHolder 綁定插件實例作為簽署，物品也帶有 tickblade:menu 標籤。
 */
public final class HotspotMenu implements InventoryHolder {

    /** 分類：要看哪一種數量 */
    public enum Mode { ENTITIES, TILES, HOPPERS }

    /** 排序：多到少、少到多、離自己最近 */
    public enum Sort { MOST, LEAST, NEAREST }

    static final int PER_PAGE = 45;
    static final int SLOT_PREV = 45;
    static final int SLOT_MODE = 47;
    static final int SLOT_SORT = 48;
    static final int SLOT_REFRESH = 49;
    static final int SLOT_CLOSE = 51;
    static final int SLOT_NEXT = 53;

    private final TickBlade plugin;
    private final Mode mode;
    private final Sort sort;
    private final int page;
    private final List<HotspotScanner.Entry> entries;
    private Inventory inventory;

    private HotspotMenu(TickBlade plugin, Mode mode, Sort sort, int page, List<HotspotScanner.Entry> entries) {
        this.plugin = plugin;
        this.mode = mode;
        this.sort = sort;
        this.page = page;
        this.entries = entries;
    }

    /** 插件簽署：其他插件可以用這個辨識介面來源 */
    public Plugin owningPlugin() {
        return plugin;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static void open(TickBlade plugin, Player p, Mode mode, Sort sort, int page) {
        List<HotspotScanner.Entry> list = new ArrayList<>();
        for (HotspotScanner.Entry e : plugin.hotspots().result()) {
            if (value(e, mode) > 0) list.add(e);
        }
        Comparator<HotspotScanner.Entry> byCount = Comparator.comparingInt(e -> value(e, mode));
        switch (sort) {
            case MOST -> list.sort(byCount.reversed());
            case LEAST -> list.sort(byCount);
            case NEAREST -> {
                // 同世界依距離由近到遠，其他世界排在後面（依世界名稱、數量多到少）
                String world = p.getWorld().getName();
                double px = p.getX();
                double pz = p.getZ();
                list.sort(Comparator.<HotspotScanner.Entry>comparingInt(e -> e.world.equals(world) ? 0 : 1)
                        .thenComparingDouble(e -> e.world.equals(world) ? dist2(e, px, pz) : 0)
                        .thenComparing(e -> e.world)
                        .thenComparing(byCount.reversed()));
            }
        }
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));

        Messages m = plugin.messages();
        HotspotMenu menu = new HotspotMenu(plugin, mode, sort, page, list);
        Component title = m.component("hotspots.menu-title", "mode", m.raw(modeKey(mode)), "sort", m.raw(sortKey(sort)),
                "page", page + 1, "pages", pages);
        Inventory inv = plugin.getServer().createInventory(menu, 54, title);
        menu.inventory = inv;

        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < list.size(); i++) {
            inv.setItem(i, entryItem(plugin, list.get(from + i), mode));
        }
        if (list.isEmpty()) inv.setItem(22, item(plugin, Material.BARRIER, m.component("hotspots.empty"), List.of()));
        if (page > 0) inv.setItem(SLOT_PREV, item(plugin, Material.ARROW, m.component("hotspots.prev"), List.of()));
        if (page < pages - 1) inv.setItem(SLOT_NEXT, item(plugin, Material.ARROW, m.component("hotspots.next"), List.of()));
        inv.setItem(SLOT_MODE, item(plugin, icon(mode), m.component("hotspots.category-button", "mode", m.raw(modeKey(mode))),
                m.components("hotspots.category-button-lore")));
        inv.setItem(SLOT_SORT, item(plugin, Material.COMPARATOR, m.component("hotspots.sort-button", "sort", m.raw(sortKey(sort))),
                m.components("hotspots.sort-button-lore")));
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date(plugin.hotspots().scannedAt()));
        inv.setItem(SLOT_REFRESH, item(plugin, Material.CLOCK, m.component("hotspots.refresh"),
                m.components("hotspots.refresh-lore", "time", time)));
        inv.setItem(SLOT_CLOSE, item(plugin, Material.BARRIER, m.component("hotspots.close"), List.of()));
        p.openInventory(inv);
    }

    /** 由 MenuListener 呼叫；只會收到左鍵點擊上方介面的格子 */
    void click(Player p, int slot) {
        if (!p.hasPermission(TickBlade.PERM_HOTSPOTS)
                || !clre20.tickBlade.util.OpLevel.atLeast(p, plugin.settings().hotspotMinOpLevel)) {
            p.closeInventory();
            return;
        }
        switch (slot) {
            case SLOT_PREV -> open(plugin, p, mode, sort, page - 1);
            case SLOT_NEXT -> open(plugin, p, mode, sort, page + 1);
            case SLOT_MODE -> open(plugin, p, Mode.values()[(mode.ordinal() + 1) % Mode.values().length], sort, 0);
            case SLOT_SORT -> open(plugin, p, mode, Sort.values()[(sort.ordinal() + 1) % Sort.values().length], 0);
            case SLOT_REFRESH -> {
                p.closeInventory();
                plugin.hotspots().open(p, true, mode, sort);
            }
            case SLOT_CLOSE -> p.closeInventory();
            default -> {
                if (slot < 0 || slot >= PER_PAGE) return;
                int idx = page * PER_PAGE + slot;
                if (idx >= entries.size()) return;
                teleport(p, entries.get(idx));
            }
        }
    }

    private void teleport(Player p, HotspotScanner.Entry e) {
        World w = plugin.getServer().getWorld(e.world);
        Messages m = plugin.messages();
        if (w == null) {
            m.send(p, "hotspots.teleport-failed");
            return;
        }
        Location loc;
        if (!Double.isNaN(e.sx)) {
            loc = new Location(w, e.sx, e.sy, e.sz, p.getLocation().getYaw(), p.getLocation().getPitch());
        } else {
            int bx = (e.cx << 4) + 8;
            int bz = (e.cz << 4) + 8;
            loc = new Location(w, bx + 0.5, w.getHighestBlockYAt(bx, bz) + 1, bz + 0.5);
        }
        p.closeInventory();
        p.teleportAsync(loc).thenAccept(ok -> {
            if (!p.isOnline()) return;
            if (ok) m.send(p, "hotspots.teleported", "world", w.getName(),
                    "x", loc.getBlockX(), "y", loc.getBlockY(), "z", loc.getBlockZ());
            else m.send(p, "hotspots.teleport-failed");
        });
    }

    private static int value(HotspotScanner.Entry e, Mode mode) {
        return switch (mode) {
            case ENTITIES -> e.entities;
            case TILES -> e.tiles;
            case HOPPERS -> e.hoppers;
        };
    }

    private static double dist2(HotspotScanner.Entry e, double px, double pz) {
        double dx = (e.cx << 4) + 8 - px;
        double dz = (e.cz << 4) + 8 - pz;
        return dx * dx + dz * dz;
    }

    private static String sortKey(Sort sort) {
        return switch (sort) {
            case MOST -> "hotspots.sort-most";
            case LEAST -> "hotspots.sort-least";
            case NEAREST -> "hotspots.sort-nearest";
        };
    }

    private static String modeKey(Mode mode) {
        return switch (mode) {
            case ENTITIES -> "hotspots.mode-entities";
            case TILES -> "hotspots.mode-tiles";
            case HOPPERS -> "hotspots.mode-hoppers";
        };
    }

    private static Material icon(Mode mode) {
        return switch (mode) {
            case ENTITIES -> Material.ZOMBIE_HEAD;
            case TILES -> Material.CHEST;
            case HOPPERS -> Material.HOPPER;
        };
    }

    private static ItemStack entryItem(TickBlade plugin, HotspotScanner.Entry e, Mode mode) {
        Messages m = plugin.messages();
        int bx = (e.cx << 4) + 8;
        int bz = (e.cz << 4) + 8;
        Object[] vars = {"world", e.world, "cx", e.cx, "cz", e.cz, "x", bx, "z", bz,
                "entities", e.entities, "tiles", e.tiles, "hoppers", e.hoppers};
        List<Component> lore = new ArrayList<>();
        for (String line : m.rawList("hotspots.entry-lore", vars)) {
            if (line.trim().equals("{types}")) {
                List<Map.Entry<EntityType, Integer>> top = e.topTypes(5);
                if (top.isEmpty()) lore.add(m.component("hotspots.type-none"));
                for (Map.Entry<EntityType, Integer> t : top) lore.add(typeLine(m, t.getKey(), t.getValue()));
            } else {
                lore.add(Messages.legacy(line));
            }
        }
        return item(plugin, icon(mode), m.component("hotspots.entry-name", vars), lore);
    }

    /** 「{type}」換成客戶端語言的生物名稱 */
    private static Component typeLine(Messages m, EntityType type, int count) {
        String raw = m.raw("hotspots.type-line", "count", count);
        int idx = raw.indexOf("{type}");
        Component name = Component.translatable(type.translationKey());
        if (idx < 0) return Messages.legacy(raw);
        String before = raw.substring(0, idx);
        String after = raw.substring(idx + 6);
        Component pre = Messages.legacy(before);
        // 名稱沿用前段最後的顏色
        Component colored = name.style(pre.children().isEmpty() ? pre.style() : pre.children().get(pre.children().size() - 1).style());
        return pre.append(colored).append(Messages.legacy(lastColor(before) + after));
    }

    private static String lastColor(String s) {
        int i = s.lastIndexOf('&');
        return i >= 0 && i + 1 < s.length() ? s.substring(i, i + 2) : "";
    }

    private static ItemStack item(TickBlade plugin, Material mat, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        if (!lore.isEmpty()) meta.lore(lore);
        meta.getPersistentDataContainer().set(plugin.menuKey(), PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }
}
