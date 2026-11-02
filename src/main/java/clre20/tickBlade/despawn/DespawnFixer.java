package clre20.tickBlade.despawn;

import clre20.tickBlade.TickBlade;
import clre20.tickBlade.config.Settings;
import clre20.tickBlade.util.Confinement;
import clre20.tickBlade.util.Protection;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Raider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A. 生物消失判斷
 * 原版中終界使者拿著方塊、怪物撿起物品穿上後，會變成不會自然消失，因此越積越多。
 * 對「可以自由走動」的這類怪物：把方塊／物品原地掉落，恢復成可以正常消失。
 */
public final class DespawnFixer implements Listener {

    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final float DEFAULT_DROP_CHANCE = 0.085f;
    private static final int MAX_QUEUE = 20_000;

    private final TickBlade plugin;
    private final NamespacedKey droppedKey;
    private final ArrayDeque<UUID> queue = new ArrayDeque<>();
    private final Set<UUID> queued = new HashSet<>();
    private BukkitTask worker;
    private BukkitTask sweeper;

    private long fixed;
    private long droppedBlocks;
    private long droppedItems;
    private long blockedPickups;

    public DespawnFixer(TickBlade plugin) {
        this.plugin = plugin;
        this.droppedKey = new NamespacedKey(plugin, "dropped");
    }

    public void start() {
        stop();
        Settings s = plugin.settings();
        if (!s.despawnEnabled) return;
        worker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::work, 1L, 1L);
        if (s.sweepIntervalMinutes > 0) {
            long period = s.sweepIntervalMinutes * 60L * 20L;
            sweeper = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweep, 200L, period);
        }
    }

    public void stop() {
        if (worker != null) worker.cancel();
        if (sweeper != null) sweeper.cancel();
        worker = null;
        sweeper = null;
        queue.clear();
        queued.clear();
    }

    // ---------- 事件 ----------

    /** 阻止終界使者撿起方塊（放下方塊不受影響） */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent e) {
        if (e.getEntityType() != EntityType.ENDERMAN) return;
        Settings s = plugin.settings();
        if (!s.despawnEnabled || !s.blockEndermanPickup) return;
        if (!e.getTo().isAir()) return;
        if (!s.despawnWorld(e.getBlock().getWorld().getName())) return;
        e.setCancelled(true);
        blockedPickups++;
    }

    /** 插件掉落的東西不讓怪物再撿回去（否則會一直重複變成不會消失） */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player) return;
        Item item = e.getItem();
        if (item.getPersistentDataContainer().has(droppedKey)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        Settings s = plugin.settings();
        if (!s.despawnEnabled || !s.fixOnChunkLoad) return;
        if (!s.despawnWorld(e.getWorld().getName())) return;
        for (Entity en : e.getEntities()) {
            if (en instanceof Monster m && quickCandidate(m)) enqueue(m.getUniqueId());
        }
    }

    // ---------- 處理 ----------

    /** 檢查所有已載入的怪物（定時或 /tb cleanup） */
    public int sweep() {
        Settings s = plugin.settings();
        if (!s.despawnEnabled) return 0;
        int n = 0;
        for (World w : plugin.getServer().getWorlds()) {
            if (!s.despawnWorld(w.getName())) continue;
            for (Monster m : w.getEntitiesByClass(Monster.class)) {
                if (quickCandidate(m) && enqueue(m.getUniqueId())) n++;
            }
        }
        return n;
    }

    private boolean enqueue(UUID id) {
        if (queue.size() >= MAX_QUEUE || !queued.add(id)) return false;
        queue.add(id);
        return true;
    }

    private void work() {
        if (queue.isEmpty()) return;
        int budget = plugin.settings().despawnMaxPerTick;
        while (budget-- > 0 && !queue.isEmpty()) {
            UUID id = queue.poll();
            queued.remove(id);
            Entity en = plugin.getServer().getEntity(id);
            if (en instanceof Monster m && m.isValid()) process(m);
        }
    }

    /** 便宜的初步過濾：只看記憶體中的欄位 */
    private boolean quickCandidate(Monster m) {
        if (m instanceof Enderman em) return em.getCarriedBlock() != null;
        return !m.getRemoveWhenFarAway();
    }

    private void process(Monster m) {
        Settings s = plugin.settings();
        if (!s.despawnWorld(m.getWorld().getName())) return;
        if (s.despawnExcluded.contains(m.getType())) return;
        if (m.customName() != null) return;
        if (m.isLeashed() || m.isInsideVehicle() || !m.getPassengers().isEmpty()) return;
        if (m instanceof Raider r && (r.getRaid() != null || r.isPatrolLeader())) return;
        if (Protection.isForeign(m, s)) return;

        boolean changed = false;
        if (m instanceof Enderman em) {
            BlockData data = em.getCarriedBlock();
            if (data == null) return;
            if (s.despawnSkipConfined && Confinement.isConfined(m)) return;
            Material mat = data.getPlacementMaterial();
            if (mat.isItem() && !mat.isAir()) drop(m, new ItemStack(mat));
            em.setCarriedBlock(null);
            droppedBlocks++;
            changed = true;
        } else {
            if (m.getRemoveWhenFarAway()) return;
            EntityEquipment eq = m.getEquipment();
            if (eq == null) return;
            // 撿起來的物品掉落率會被設成「必定掉落」（> 1）；沒有就代表是其他原因不會消失（例如生怪蛋），不處理
            boolean picked = false;
            for (EquipmentSlot slot : SLOTS) {
                ItemStack it = eq.getItem(slot);
                if (!it.isEmpty() && eq.getDropChance(slot) > 1.0f) {
                    picked = true;
                    break;
                }
            }
            if (!picked) return;
            if (s.despawnSkipConfined && Confinement.isConfined(m)) return;
            for (EquipmentSlot slot : SLOTS) {
                ItemStack it = eq.getItem(slot);
                if (it.isEmpty() || eq.getDropChance(slot) <= 1.0f) continue;
                drop(m, it.clone());
                eq.setItem(slot, null);
                eq.setDropChance(slot, DEFAULT_DROP_CHANCE);
                droppedItems++;
            }
            m.setRemoveWhenFarAway(true);
            changed = true;
        }
        if (changed) fixed++;
    }

    private void drop(Mob m, ItemStack stack) {
        m.getWorld().dropItemNaturally(m.getLocation(), stack, item ->
                item.getPersistentDataContainer().set(droppedKey, PersistentDataType.BYTE, (byte) 1));
    }

    public long fixed() {
        return fixed;
    }

    public long droppedBlocks() {
        return droppedBlocks;
    }

    public long droppedItems() {
        return droppedItems;
    }

    public long blockedPickups() {
        return blockedPickups;
    }
}
