package cn.mythicland.framework.bukkit.world;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class GridPathfinder {
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[] HEIGHTS = {0, 1, -1, -2, -3};
    private static final double[] FOOTPRINT = {-0.3, 0.3};
    private final Long2DoubleOpenHashMap costs = new Long2DoubleOpenHashMap(256);
    private final Long2LongOpenHashMap parents = new Long2LongOpenHashMap(256);
    private final Long2ByteOpenHashMap walkability = new Long2ByteOpenHashMap(256);
    private final LongOpenHashSet closed = new LongOpenHashSet(256);
    private long[] heapKeys = new long[256];
    private double[] heapScores = new double[256];
    private int heapSize;

    public GridPathfinder() {
        this.costs.defaultReturnValue(Double.POSITIVE_INFINITY);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3ffffffL) << 38 | ((long) z & 0x3ffffffL) << 12 | y & 0xfffL;
    }

    private static int x(long key) {
        return (int) (key >> 38);
    }

    private static int y(long key) {
        return (int) (key & 0xfffL);
    }

    private static int z(long key) {
        return (int) (key << 26 >> 38);
    }

    public boolean canWalkDirectly(Location start, Location goal) {
        if (!start.getWorld().equals(goal.getWorld())) return false;
        if (start.getBlockY() != goal.getBlockY()) return false;
        return direct(
                start.getWorld(),
                start.getX(),
                start.getBlockY(),
                start.getZ(),
                goal.getX(),
                goal.getZ(),
                false
        );
    }

    public int directSegments(Location start, Location goal) {
        return Math.max(1, (int) Math.ceil(Math.hypot(goal.getX() - start.getX(), goal.getZ() - start.getZ()) / 3.5));
    }

    public boolean canWalkDirectly(Location start, Location goal, int maxSegments) {
        if (maxSegments <= 0) throw new IllegalArgumentException("maximum direct segments must be positive");
        int segments = this.directSegments(start, goal);
        if (segments > maxSegments) return false;
        if (!start.getWorld().equals(goal.getWorld()) || start.getBlockY() != goal.getBlockY()) return false;
        return this.directLine(start, goal, segments, false);
    }

    /**
     * Checks forward nodes on the current level, sharing terrain reads only within this call.
     */
    public int furthestDirectPoint(
            Location start,
            List<Location> path,
            int first,
            double rangeSquared
    ) {
        if (first < 0 || first > path.size()) throw new IndexOutOfBoundsException("invalid first path index: " + first);
        if (!Double.isFinite(rangeSquared) || rangeSquared < 0) {
            throw new IllegalArgumentException("squared direct range must be finite and nonnegative");
        }
        int end = first;
        while (end < path.size() && path.get(end).getBlockY() == start.getBlockY()) end++;
        this.walkability.clear();
        for (int index = end - 1; index >= first; index--) {
            Location point = path.get(index);
            if (start.distanceSquared(point) > rangeSquared) continue;
            if (this.directLine(start, point, this.directSegments(start, point), true)) return index;
        }
        return -1;
    }

    private boolean directLine(
            Location start,
            Location goal,
            int segments,
            boolean cached
    ) {
        World world = start.getWorld();
        double sx = start.getX(), sy = start.getY(), sz = start.getZ();
        double dx = goal.getX() - sx, dy = goal.getY() - sy, dz = goal.getZ() - sz;
        double fromX = sx, fromY = sy, fromZ = sz;
        for (int index = 1; index <= segments; index++) {
            double fraction = (double) index / segments;
            double toX = sx + dx * fraction, toY = sy + dy * fraction, toZ = sz + dz * fraction;
            int blockY = Location.locToBlock(fromY);
            if (blockY != Location.locToBlock(toY)) return false;
            if (!this.direct(world, fromX, blockY, fromZ, toX, toZ, cached)) return false;
            fromX = toX;
            fromY = toY;
            fromZ = toZ;
        }
        return true;
    }

    public boolean isWalkable(Location location) {
        World world = location.getWorld();
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return false;
        return walkable(world, location.getBlockX(), location.getBlockY(), location.getBlockZ(), false);
    }

    public List<Location> find(
            Location start,
            Location goal,
            int budget,
            double arrivalHeight,
            Traversal traversal
    ) {
        if (budget <= 0) throw new IllegalArgumentException("search budget must be positive");
        if (!start.getWorld().equals(goal.getWorld())) throw new IllegalArgumentException("path worlds differ");
        this.costs.clear();
        this.parents.clear();
        this.walkability.clear();
        this.closed.clear();
        this.heapSize = 0;
        World world = start.getWorld();
        long origin = key(start.getBlockX(), start.getBlockY(), start.getBlockZ());
        this.costs.put(origin, 0);
        offer(origin, estimate(start.getBlockX(), start.getBlockY(), start.getBlockZ(), goal));
        for (int searched = 0; this.heapSize > 0 && searched < budget; ) {
            long current = poll();
            if (!this.closed.add(current)) continue;
            searched++;
            int cx = x(current), cy = y(current), cz = z(current);
            if (cx == goal.getBlockX() && cz == goal.getBlockZ() && Math.abs(cy - goal.getY()) <= arrivalHeight) {
                ArrayList<Location> path = new ArrayList<>();
                for (long node = current; node != origin; node = this.parents.get(node)) {
                    path.add(new Location(world, x(node) + 0.5, y(node), z(node) + 0.5));
                }
                Collections.reverse(path);
                return path;
            }
            for (int[] delta : DIRECTIONS) {
                int nx = cx + delta[0], nz = cz + delta[1];
                if (!world.isChunkLoaded(nx >> 4, nz >> 4)) continue;
                int ny = Integer.MIN_VALUE;
                for (int height : HEIGHTS) {
                    int candidateY = cy + height;
                    if (!walkable(world, nx, candidateY, nz, true)) continue;
                    if (height == 1 && BlockMaterials.isSolid(world, cx, cy + 2, cz)) continue;
                    ny = candidateY;
                    break;
                }
                if (ny == Integer.MIN_VALUE || !traversal.allows(cx, cy, cz, nx, ny, nz)) continue;
                if (delta[0] != 0 && delta[1] != 0 && (ny != cy
                        || !direct(world, cx + .5, cy, cz + .5, nx + .5, nz + .5, true))) continue;
                long next = key(nx, ny, nz);
                if (this.closed.contains(next)) continue;
                double cost = this.costs.get(current) + Math.hypot(delta[0], delta[1]) + Math.abs(ny - cy) * .25;
                if (cost >= this.costs.get(next)) continue;
                this.costs.put(next, cost);
                this.parents.put(next, current);
                offer(next, cost + estimate(nx, ny, nz, goal));
            }
        }
        return List.of();
    }

    private boolean direct(
            World world,
            double sx,
            int sy,
            double sz,
            double gx,
            double gz,
            boolean cached
    ) {
        double dx = gx - sx, dz = gz - sz;
        double distance = Math.hypot(dx, dz);
        if (distance > 4) return false;
        int steps = Math.max(1, (int) Math.ceil(distance / .4));
        // The four-block segment and its footprint fit within an 8x8 column mask, local to this check.
        int minX = (int) Math.floor(Math.min(sx, gx) + FOOTPRINT[0]);
        int minZ = (int) Math.floor(Math.min(sz, gz) + FOOTPRINT[0]);
        long checked = 0;
        for (int step = 0; step <= steps; step++) {
            double px = sx + dx * step / steps, pz = sz + dz * step / steps;
            for (double ox : FOOTPRINT)
                for (double oz : FOOTPRINT) {
                    int bx = (int) Math.floor(px + ox), bz = (int) Math.floor(pz + oz);
                    long column = 1L << ((bx - minX) * 8 + bz - minZ);
                    if ((checked & column) != 0) continue;
                    if (!world.isChunkLoaded(bx >> 4, bz >> 4) || !walkable(world, bx, sy, bz, cached)) return false;
                    checked |= column;
                }
        }
        return true;
    }

    private boolean walkable(World world, int x, int y, int z, boolean cached) {
        if (y < 1 || y + 1 >= world.getMaxHeight()) return false;
        long key = key(x, y, z);
        byte known = cached ? this.walkability.get(key) : 0;
        if (known != 0) return known == 1;
        Material ground = Material.getMaterial(world.getBlockTypeIdAt(x, y - 1, z));
        Material feet = Material.getMaterial(world.getBlockTypeIdAt(x, y, z));
        boolean walkable = ground.isSolid() && ground != Material.CARPET
                && (feet == Material.CARPET || !feet.isSolid())
                && !BlockMaterials.isSolid(world, x, y + 1, z);
        if (cached) this.walkability.put(key, (byte) (walkable ? 1 : 2));
        return walkable;
    }

    private double estimate(int x, int y, int z, Location goal) {
        return Math.hypot(x + .5 - goal.getX(), z + .5 - goal.getZ()) + Math.abs(y - goal.getY()) * .25;
    }

    private void offer(long key, double score) {
        if (this.heapSize == this.heapKeys.length) {
            this.heapKeys = Arrays.copyOf(this.heapKeys, this.heapSize * 2);
            this.heapScores = Arrays.copyOf(this.heapScores, this.heapSize * 2);
        }
        int index = this.heapSize++;
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (this.heapScores[parent] <= score) break;
            this.heapKeys[index] = this.heapKeys[parent];
            this.heapScores[index] = this.heapScores[parent];
            index = parent;
        }
        this.heapKeys[index] = key;
        this.heapScores[index] = score;
    }

    private long poll() {
        long result = this.heapKeys[0];
        long key = this.heapKeys[--this.heapSize];
        double score = this.heapScores[this.heapSize];
        int index = 0;
        while (index * 2 + 1 < this.heapSize) {
            int child = index * 2 + 1;
            if (child + 1 < this.heapSize && this.heapScores[child + 1] < this.heapScores[child]) child++;
            if (score <= this.heapScores[child]) break;
            this.heapKeys[index] = this.heapKeys[child];
            this.heapScores[index] = this.heapScores[child];
            index = child;
        }
        this.heapKeys[index] = key;
        this.heapScores[index] = score;
        return result;
    }

    @FunctionalInterface
    public interface Traversal {
        boolean allows(int fromX, int fromY, int fromZ, int toX, int toY, int toZ);
    }
}
