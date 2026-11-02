package clre20.tickBlade.util;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;

/**
 * 判斷生物是否被關住：坐在船／礦車上，或前後左右都走不過去。
 * 走得過去 = 鄰格的地面不比腳下高超過 0.6 格（可以直接走上去），
 * 而且從那個地面往上，有足夠這隻生物身高的空間（例如床上方只剩 1.5 格，身高 1.95 的村民就過不去）。
 * 只讀取最多十幾個方塊，且不會載入區塊。
 */
public final class Confinement {

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final double STEP = 0.6; // 生物可以直接走上去的高度
    private static final double EPS = 1.0E-4;

    private Confinement() {
    }

    public static boolean isConfined(LivingEntity e) {
        if (e.isInsideVehicle()) return true;
        World w = e.getWorld();
        double feetY = e.getY();
        int x = (int) Math.floor(e.getX());
        int y = (int) Math.floor(feetY);
        int z = (int) Math.floor(e.getZ());
        double height = e.getHeight();
        for (int[] d : DIRS) {
            int nx = x + d[0];
            int nz = z + d[1];
            // 鄰居區塊沒載入時無法判斷，當作可以走（保守：不暫停、不視為農場）
            if (!w.isChunkLoaded(nx >> 4, nz >> 4)) return false;
            if (canEnter(w, nx, y, nz, feetY, height)) return false;
        }
        return true;
    }

    /** 這隻生物能不能走進 (nx, nz) 這一格 */
    private static boolean canEnter(World w, int nx, int y, int nz, double feetY, double height) {
        Block feet = w.getBlockAt(nx, y, nz);
        double floor = y + (feet.isPassable() ? 0 : topOf(feet));
        if (floor - feetY > STEP + EPS) return false; // 太高，走不上去（圍欄、整格方塊）
        double ceil = Math.max(floor, feetY) + height;
        int last = (int) Math.ceil(ceil - EPS);
        for (int by = y; by < last; by++) {
            Block b = by == y ? feet : w.getBlockAt(nx, by, nz);
            if (b.isPassable()) continue;
            for (BoundingBox box : b.getCollisionShape().getBoundingBoxes()) {
                double minY = by + box.getMinY();
                double maxY = by + box.getMaxY();
                // 碰撞箱和「地面到頭頂」這段高度重疊 → 擋住
                if (maxY > floor + EPS && minY < ceil - EPS) return false;
            }
        }
        return true;
    }

    private static double topOf(Block b) {
        double top = 0;
        for (BoundingBox box : b.getCollisionShape().getBoundingBoxes()) top = Math.max(top, box.getMaxY());
        return top;
    }
}
