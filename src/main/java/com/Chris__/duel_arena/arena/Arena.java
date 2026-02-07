package com.Chris__.duel_arena.arena;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;

public final class Arena {

    public String id = "";
    public String world = "";

    public Aabb bounds = new Aabb();
    public Spawn spawnA = new Spawn();
    public Spawn spawnB = new Spawn();

    public Aabb spectatorBounds = new Aabb();
    public Spawn spectatorSpawn = new Spawn();

    public void normalize() {
        if (id == null) id = "";
        if (world == null) world = "";
        if (bounds == null) bounds = new Aabb();
        if (spawnA == null) spawnA = new Spawn();
        if (spawnB == null) spawnB = new Spawn();
        if (spectatorBounds == null) spectatorBounds = new Aabb();
        if (spectatorSpawn == null) spectatorSpawn = new Spawn();

        bounds.normalize();
        spectatorBounds.normalize();
    }

    public boolean isValidForUse() {
        if (id == null || id.isBlank()) return false;
        if (world == null || world.isBlank()) return false;
        return bounds.isValid() && spawnA.isValid() && spawnB.isValid() && spectatorSpawn.isValid() && spectatorBounds.isValid();
    }

    public static final class Aabb {
        public int[] min = new int[]{0, 0, 0};
        public int[] max = new int[]{0, 0, 0};

        public void normalize() {
            if (min == null || min.length != 3) min = new int[]{0, 0, 0};
            if (max == null || max.length != 3) max = new int[]{0, 0, 0};

            int minX = Math.min(min[0], max[0]);
            int minY = Math.min(min[1], max[1]);
            int minZ = Math.min(min[2], max[2]);
            int maxX = Math.max(min[0], max[0]);
            int maxY = Math.max(min[1], max[1]);
            int maxZ = Math.max(min[2], max[2]);
            min[0] = minX;
            min[1] = minY;
            min[2] = minZ;
            max[0] = maxX;
            max[1] = maxY;
            max[2] = maxZ;
        }

        public boolean isValid() {
            return min != null && max != null && min.length == 3 && max.length == 3;
        }

        public boolean containsBlock(int x, int y, int z) {
            if (!isValid()) return false;
            return x >= min[0] && x <= max[0]
                    && y >= min[1] && y <= max[1]
                    && z >= min[2] && z <= max[2];
        }

        public int minX() { return min[0]; }
        public int minY() { return min[1]; }
        public int minZ() { return min[2]; }
        public int maxX() { return max[0]; }
        public int maxY() { return max[1]; }
        public int maxZ() { return max[2]; }
    }

    public static final class Spawn {
        public double[] pos = new double[]{0.5, 0.0, 0.5};
        public float[] rot = new float[]{0f, 0f, 0f};

        public void normalize() {
            if (pos == null || pos.length != 3) pos = new double[]{0.5, 0.0, 0.5};
            if (rot == null || rot.length != 3) rot = new float[]{0f, 0f, 0f};
        }

        public boolean isValid() {
            return pos != null && rot != null && pos.length == 3 && rot.length == 3;
        }

        public Vector3d position() {
            return new Vector3d(pos[0], pos[1], pos[2]);
        }

        public Vector3f rotation() {
            return new Vector3f(rot[0], rot[1], rot[2]);
        }
    }
}

