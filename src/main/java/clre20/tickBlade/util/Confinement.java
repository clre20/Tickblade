package clre20.tickBlade.util;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;

/**
 * 判斷生物是否被關住：坐在船／礦車上，或前後左右都走不過去。
 * 只讀取最多 8 個方塊，且不會載入區塊。
 */
public final class Confinement {

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final double STEP = 0.6; // 生物可以直接走上去的高度

    private Confinement() {
    }

    public static boolean isConfined(LivingEntity e) {
        if (e.isInsideVehicle()) return true;
        World w = e.getWorld();
        int x = (int) Math.floor(e.getX());
        int y = (int) Math.floor(e.getY());
        int z = (int) Math.floor(e.getZ());
        boolean tall = e.getHeight() > 1.0;
        for (int[] d : DIRS) {
            int nx = x + d[0];
            int nz = z + d[1];
            // 鄰居區塊沒載入時無法判斷，當作可以走（保守：不暫停、不視為農場）
            if (!w.isChunkLoaded(nx >> 4, nz >> 4)) return false;
            Block feet = w.getBlockAt(nx, y, nz);
            if (!walkable(feet)) continue;
            if (tall && !feet.getRelative(0, 1, 0).isPassable()) continue;
            return false;
        }
        return true;
    }

    /** 方塊沒有碰撞箱，或矮到可以直接走上去（地毯、半磚） */
    private static boolean walkable(Block b) {
        if (b.isPassable()) return true;
        double top = 0;
        for (BoundingBox box : b.getCollisionShape().getBoundingBoxes()) top = Math.max(top, box.getMaxY());
        return top <= STEP && b.getRelative(0, 1, 0).isPassable();
    }
}
