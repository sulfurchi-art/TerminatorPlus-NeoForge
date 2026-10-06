package net.nuggetmc.tplus.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.MovementBounds;
import net.nuggetmc.tplus.compat.WarfareAccess.Vessel;

import javax.annotation.Nullable;
import java.util.*;

/** Loaded-world, bounded local A*. Paths guide native driving; they never move the vehicle. */
final class VehicleNavigation {
    private static final int CELL = 4, LIMIT = 768, PER_VEHICLE = 6, PER_TICK = 24;
    private static final int[][] DIRECTIONS = {{0,1},{-1,1},{-1,0},{-1,-1},{0,-1},{1,-1},{1,0},{1,1}};
    private record Key(int x, int z, int heading) {}
    private record Node(Key key, Vec3 position, double cost, double estimate, @Nullable Node parent) {}
    private static final class Search {
        final Vec3 start, goal;
        final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.cost + n.estimate));
        final Map<Key, Double> costs = new HashMap<>();
        final Map<Key, Optional<Vec3>> sites = new HashMap<>();
        int expanded; Node closest;
        Search(Entity e, Vec3 goal) {
            this.start = e.position(); this.goal = goal;
            Key key = new Key(0, 0, Math.floorMod(Math.round(e.getYRot() / 45), 8));
            Node node = new Node(key, start, 0, start.subtract(goal).horizontalDistance(), null);
            open.add(node); costs.put(key, 0.0); closest = node;
        }
    }
    static final class Route {
        Vec3 goal, progress; long nextPlan, plannedAt, progressAt, reverseUntil, nextReverse;
        Search search; List<Vec3> points = List.of(); int index;
        String state = "DIRECT"; int expanded;
        void reset() { search = null; points = List.of(); index = 0; nextPlan = 0; }
    }
    private long budgetTick = Long.MIN_VALUE;
    private int spent, peak;
    int peakExpansions() { return peak; }
    void clear() { budgetTick = Long.MIN_VALUE; spent = peak = 0; }

    @Nullable Vec3 guide(Vessel v, Route route, Vec3 goal, long now) {
        Entity e = v.entity();
        if (route.goal == null || route.goal.distanceToSqr(goal) > 64) route.reset();
        route.goal = goal;
        if (!route.points.isEmpty() && now - route.plannedAt > 200) route.reset();
        while (route.index < route.points.size() && e.position().subtract(route.points.get(route.index)).horizontalDistance() < 2.5) route.index++;
        if (route.index < route.points.size()) {
            Vec3 point = route.points.get(route.index);
            // Smooth only over a swept footprint; never cut a corner through a wall or a ditch.
            for (int i = route.index + 1; i < Math.min(route.points.size(), route.index + 4); i++) {
                Vec3 candidate = route.points.get(i);
                if (e.position().subtract(candidate).horizontalDistance() > 12 || !sweep(v, e.position(), candidate)) break;
                route.index = i; point = candidate;
            }
            if (sweep(v, e.position(), point)) { route.state = "ROUTE"; return point.subtract(e.position()); }
            route.reset();
        }
        Vec3 delta = goal.subtract(e.position()).multiply(1, 0, 1);
        double lookahead = Math.min(delta.length(), Mth.clamp(6 + e.getDeltaMovement().horizontalDistance() * 20, 6, 16));
        if (sweep(v, e.position(), e.position().add(delta.normalize().scale(lookahead)))) {
            route.search = null; route.state = "DIRECT"; return delta;
        }
        if (route.search == null && now >= route.nextPlan) {
            Vec3 localGoal = delta.length() > 48 ? e.position().add(delta.normalize().scale(48)) : goal;
            route.search = new Search(e, localGoal); route.plannedAt = now; route.points = List.of(); route.index = 0;
        }
        if (route.search == null) { route.state = "NO_ROUTE"; return null; }
        if (budgetTick != now) { budgetTick = now; spent = 0; }
        // Stagger requests so a large set of drivers cannot monopolize every tick in iteration order.
        if (Math.floorMod(now + e.getId(), 4) != 0) { route.state = "PLANNING"; return null; }
        Search s = route.search;
        int allowance = Math.min(PER_VEHICLE, PER_TICK - spent);
        for (int n = 0; n < allowance && !s.open.isEmpty() && s.expanded < LIMIT; n++) {
            Node node = s.open.poll();
            if (node.cost > s.costs.getOrDefault(node.key, Double.MAX_VALUE)) { n--; continue; }
            spent++; peak = Math.max(peak, spent); s.expanded++;
            double remaining = node.position.subtract(s.goal).horizontalDistance();
            if (remaining < s.closest.position.subtract(s.goal).horizontalDistance()) s.closest = node;
            if (remaining < CELL * 1.5 && node.parent != null) { finish(route, node, now); return null; }
            for (int heading = 0; heading < DIRECTIONS.length; heading++) {
                int turn = Math.min(Math.floorMod(heading - node.key.heading, 8), Math.floorMod(node.key.heading - heading, 8));
                if (!v.engine().equals("TRACK") && turn > 2) continue;
                int x = node.key.x + DIRECTIONS[heading][0], z = node.key.z + DIRECTIONS[heading][1];
                if (Math.abs(x) > 16 || Math.abs(z) > 16) continue;
                Key key = new Key(x, z, heading);
                double cost = node.cost + CELL * (heading % 2 == 0 ? 1 : Math.sqrt(2)) + turn * 1.5;
                if (cost >= s.costs.getOrDefault(key, Double.MAX_VALUE)) continue;
                Vec3 point = s.sites.computeIfAbsent(key, k -> Optional.ofNullable(site(v,
                        s.start.add(k.x * CELL, 0, k.z * CELL), k.heading * 45))).orElse(null);
                if (point == null || !sweep(v, node.position, point)) continue;
                double heuristic = point.subtract(s.goal).horizontalDistance();
                s.costs.put(key, cost); s.open.add(new Node(key, point, cost, heuristic * 1.15, node));
            }
        }
        route.expanded = s.expanded; route.state = "PLANNING";
        if (s.open.isEmpty() || s.expanded >= LIMIT || now - route.plannedAt > 600) {
            // A partial route is useful only if it makes actual progress; otherwise wait/reverse and retry.
            if (s.closest.parent != null && s.closest.position.subtract(s.goal).horizontalDistance() + 4 < s.start.subtract(s.goal).horizontalDistance()) finish(route, s.closest, now);
            else { route.search = null; route.nextPlan = now + 40; route.state = "NO_ROUTE"; }
        }
        return null;
    }
    private static void finish(Route route, Node end, long now) {
        List<Vec3> points = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) points.add(n.position);
        Collections.reverse(points); route.points = List.copyOf(points); route.index = 0;
        route.expanded = route.search.expanded; route.search = null; route.plannedAt = now; route.state = "ROUTE";
    }

    static boolean sweep(Vessel v, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from).multiply(1, 0, 1);
        if (delta.lengthSqr() < 0.01) return site(v, to, v.entity().getYRot()) != null;
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        int steps = Math.max(1, Mth.ceil(delta.length() / 1.5));
        double previous = from.y;
        for (int i = 0; i <= steps; i++) {
            Vec3 p = site(v, from.add(delta.scale((double) i / steps)), yaw);
            if (p == null || Math.abs(p.y - previous) > Math.max(1.1, v.entity().maxUpStep())) return false;
            previous = p.y;
        }
        return true;
    }
    @Nullable static Vec3 site(Vessel v, Vec3 point, float yaw) {
        Entity e = v.entity(); var shape = v.footprint();
        double angle = Math.toRadians(yaw), sin = Math.abs(Math.sin(angle)), cos = Math.abs(Math.cos(angle));
        double rx = cos * shape.halfSize().x + sin * shape.halfSize().z + 0.35;
        double rz = sin * shape.halfSize().x + cos * shape.halfSize().z + 0.35;
        Vec3 offset = shape.center().yRot((float) -angle);
        double x = point.x + offset.x, z = point.z + offset.z;
        if (!loaded(e, new AABB(x - rx, point.y, z - rz, x + rx, point.y + 1, z + rz))) return null;
        double y = e.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
        if (Math.abs(y - point.y) > Math.max(1.1, e.maxUpStep())) return null;
        // Check wheel/track support across the body, rather than a single center height.
        for (double dx : new double[]{-rx + 0.4, 0, rx - 0.4}) for (double dz : new double[]{-rz + 0.4, 0, rz - 0.4}) {
            BlockPos ground = BlockPos.containing(x + dx, y - 0.05, z + dz);
            double floor = e.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ground.getX(), ground.getZ());
            if (Math.abs(floor - y) > 1 || !e.level().getFluidState(ground).isEmpty()
                    || e.level().getBlockState(ground).getCollisionShape(e.level(), ground).isEmpty()) return null;
        }
        AABB body = new AABB(x - rx, y + Math.max(0.18, shape.center().y - shape.halfSize().y + 0.18), z - rz,
                x + rx, y + shape.center().y + shape.halfSize().y, z + rz);
        if (!loaded(e, body) || e.level().getBlockCollisions(e, body).iterator().hasNext()) return null;
        return new Vec3(point.x, y, point.z);
    }
    static boolean loaded(Entity e, AABB box) {
        if (box.minY < e.level().getMinBuildHeight() || box.maxY > e.level().getMaxBuildHeight()) return false;
        if (!MovementBounds.contains(e, new Vec3(box.minX, box.minY, box.minZ), 2)
                || !MovementBounds.contains(e, new Vec3(box.maxX, box.maxY, box.maxZ), 2)) return false;
        for (int x = Mth.floor(box.minX) >> 4; x <= Mth.floor(box.maxX) >> 4; x++)
            for (int z = Mth.floor(box.minZ) >> 4; z <= Mth.floor(box.maxZ) >> 4; z++)
                if (!e.level().hasChunkAt(new BlockPos(x << 4, Mth.floor(box.minY), z << 4))) return false;
        return true;
    }
}
