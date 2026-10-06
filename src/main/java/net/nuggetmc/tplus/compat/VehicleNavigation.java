package net.nuggetmc.tplus.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.MovementBounds;
import net.nuggetmc.tplus.compat.WarfareAccess.Vessel;

import javax.annotation.Nullable;
import java.util.*;

/** Loaded-world, bounded local A*. Paths guide native driving; they never move the vehicle. */
final class VehicleNavigation {
    private static final int CELL = 4, LIMIT = 768, PER_VEHICLE = 6, PER_TICK = 24;
    record Rejection(String reason, BlockPos position) {}
    private static Rejection rejection; // Geometry runs on the server thread; guide captures it immediately.
    private static final int[][] DIRECTIONS = {{0,1},{-1,1},{-1,0},{-1,-1},{0,-1},{1,-1},{1,0},{1,1}};
    private record Key(int x, int z, int heading, int height) {}
    private record SiteKey(int x, int z, int heading, int referenceHeight) {}
    private record Node(Key key, Vec3 position, float yaw, double cost, double estimate, @Nullable Node parent) {}
    private static final class Search {
        final Vec3 start, goal;
        final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.cost + n.estimate));
        final Map<Key, Double> costs = new HashMap<>();
        final Map<SiteKey, Optional<Vec3>> sites = new HashMap<>();
        int expanded; Node closest;
        Search(Entity e, Vec3 goal) {
            this.start = e.position(); this.goal = goal;
            Key key = new Key(0, 0, Math.floorMod(Math.round(e.getYRot() / 45), 8), Mth.floor(start.y * 2));
            Node node = new Node(key, start, e.getYRot(), 0, start.subtract(goal).horizontalDistance(), null);
            open.add(node); costs.put(key, 0.0); closest = node;
        }
    }
    static final class Route {
        Vec3 goal, progress; double bestDistance = Double.MAX_VALUE; float bestTurn = 180; int recoveries; long nextPlan, plannedAt, progressAt, reverseUntil, nextReverse;
        Search search; List<Vec3> points = List.of(); int index;
        String state = "DIRECT"; int expanded; Rejection problem;
        void reset() { search = null; points = List.of(); index = 0; nextPlan = 0; progress = null; }
    }
    private long budgetTick = Long.MIN_VALUE;
    private int spent, peak;
    private final Deque<Entity> waiting = new ArrayDeque<>();
    private final Map<Entity, Long> requests = new HashMap<>();
    private final Map<Entity, Long> served = new HashMap<>();
    void forget(Entity e) { waiting.remove(e); requests.remove(e); served.remove(e); }
    int peakExpansions() { return peak; }
    void clear() { budgetTick = Long.MIN_VALUE; spent = peak = 0; waiting.clear(); requests.clear(); served.clear(); }

    @Nullable Vec3 guide(Vessel v, Route route, Vec3 goal, long now) {
        Entity e = v.entity();
        if (route.goal == null || route.goal.distanceToSqr(goal) > 64) { route.reset(); waiting.remove(e); }
        route.goal = goal;
        if (!route.points.isEmpty() && now - route.plannedAt > 600) route.reset();
        while (route.index < route.points.size() && e.position().subtract(route.points.get(route.index)).horizontalDistance() < 1.75) route.index++;
        if (route.index < route.points.size()) {
            Vec3 point = route.points.get(route.index);
            // Smooth only over a swept footprint; never cut a corner through a wall or a ditch.
            for (int i = route.index + 1; i < Math.min(route.points.size(), route.index + 4); i++) {
                Vec3 candidate = route.points.get(i);
                if (e.position().subtract(candidate).horizontalDistance() > Mth.clamp(5 + e.getDeltaMovement().horizontalDistance() * 12, 5, 10) || !departure(v, e.position(), candidate, e.getYRot())) break;
                route.index = i; point = candidate;
            }
            if (departure(v, e.position(), point, e.getYRot())) { route.problem = null; route.state = "ROUTE"; return point.subtract(e.position()); }
            route.reset();
        }
        Vec3 delta = goal.subtract(e.position()).multiply(1, 0, 1);
        double lookahead = Math.min(delta.length(), Mth.clamp(6 + e.getDeltaMovement().horizontalDistance() * 20, 6, 16));
        if (departure(v, e.position(), e.position().add(delta.normalize().scale(lookahead)), e.getYRot())) {
            route.search = null; waiting.remove(e); route.problem = null; route.state = "DIRECT"; return delta;
        }
        route.problem = rejection;
        if (route.search == null && now >= route.nextPlan) {
            Vec3 localGoal = delta.length() > 48 ? e.position().add(delta.normalize().scale(48)) : goal;
            route.search = new Search(e, localGoal); route.plannedAt = now; route.points = List.of(); route.index = 0;
        }
        if (route.search == null) { waiting.remove(e); route.state = "NO_ROUTE"; return null; }
        if (budgetTick != now) { budgetTick = now; spent = 0; }
        // Rotate the actual pending searches, rather than privileging the first bots in tick order.
        requests.put(e, now);
        waiting.removeIf(other -> other.isRemoved() || now - requests.getOrDefault(other, Long.MIN_VALUE / 2) > 2);
        requests.entrySet().removeIf(entry -> entry.getKey().isRemoved() || now - entry.getValue() > 2);
        if (!waiting.contains(e)) waiting.addLast(e);
        served.entrySet().removeIf(entry -> entry.getKey().isRemoved() || now - entry.getValue() > 40);
        if (waiting.peekFirst() != e || spent >= PER_TICK || served.getOrDefault(e, Long.MIN_VALUE) == now) { route.state = "PLANNING"; return null; }
        waiting.removeFirst(); waiting.addLast(e); served.put(e, now);
        Search s = route.search;
        int allowance = Math.min(PER_VEHICLE, PER_TICK - spent);
        for (int n = 0; n < allowance && !s.open.isEmpty() && s.expanded < LIMIT; n++) {
            Node node = s.open.poll();
            spent++; peak = Math.max(peak, spent);
            if (node.cost > s.costs.getOrDefault(node.key, Double.MAX_VALUE)) continue;
            s.expanded++;
            double remaining = node.position.subtract(s.goal).horizontalDistance();
            if (remaining < s.closest.position.subtract(s.goal).horizontalDistance()) s.closest = node;
            if (remaining < CELL * 1.5 && node.parent != null && departure(v, node.position, s.goal, node.yaw)) {
                Vec3 end = site(v, new Vec3(s.goal.x, node.position.y, s.goal.z), directionYaw(s.goal.subtract(node.position)));
                finish(route, node, now);
                if (end != null && end.subtract(node.position).horizontalDistance() > 0.5) {
                    List<Vec3> points = new ArrayList<>(route.points); points.add(end); route.points = List.copyOf(points);
                }
                waiting.remove(e); return null;
            }
            for (int heading = 0; heading < DIRECTIONS.length; heading++) {
                int turn = Math.min(Math.floorMod(heading - node.key.heading, 8), Math.floorMod(node.key.heading - heading, 8));
                if (turn > (v.engine().equals("TRACK") ? 2 : 1)) continue;
                boolean track = v.engine().equals("TRACK");
                int signedTurn = Math.floorMod(heading - node.key.heading + 4, 8) - 4;
                float nextYaw = track ? heading * 45 : node.yaw + signedTurn * 45;
                Vec3 point;
                int x, z;
                if (track) {
                    x = node.key.x + DIRECTIONS[heading][0]; z = node.key.z + DIRECTIONS[heading][1];
                    if (Math.abs(x) > 16 || Math.abs(z) > 16) continue;
                    SiteKey lookup = new SiteKey(x, z, heading, Mth.floor(node.position.y * 2));
                    point = s.sites.computeIfAbsent(lookup, k -> Optional.ofNullable(site(v,
                            new Vec3(s.start.x + k.x * CELL, node.position.y, s.start.z + k.z * CELL), k.heading * 45))).orElse(null);
                    if (point == null) continue;
                } else {
                    // Wheel engines need translation to steer. Expand a swept arc instead of an imaginary pivot.
                    point = curve(v, node.position, node.yaw, nextYaw);
                    if (point == null) continue;
                    x = Math.round((float) ((point.x - s.start.x) / CELL)); z = Math.round((float) ((point.z - s.start.z) / CELL));
                }
                if (Math.abs(x) > 16 || Math.abs(z) > 16) continue;
                double cost = node.cost + node.position.subtract(point).horizontalDistance() + turn * 2.0;
                Key key = new Key(x, z, heading, Mth.floor(point.y * 2));
                if (cost >= s.costs.getOrDefault(key, Double.MAX_VALUE)) continue;
                if (track && !departure(v, node.position, point, node.yaw)) continue;
                double heuristic = point.subtract(s.goal).horizontalDistance();
                s.costs.put(key, cost); s.open.add(new Node(key, point, nextYaw, cost, heuristic * 1.15, node));
            }
        }
        route.expanded = s.expanded; route.state = "PLANNING";
        if (s.open.isEmpty() || s.expanded >= LIMIT || now - route.plannedAt > 1200) {
            waiting.remove(e);
            // A partial route is useful only if it makes actual progress; otherwise wait/reverse and retry.
            if (s.closest.parent != null && s.closest.position.subtract(s.goal).horizontalDistance() + 4 < s.start.subtract(s.goal).horizontalDistance()) finish(route, s.closest, now);
            else { route.search = null; route.nextPlan = now + 120; route.state = "NO_ROUTE"; }
        }
        return null;
    }
    private static void finish(Route route, Node end, long now) {
        List<Vec3> points = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) points.add(n.position);
        Collections.reverse(points); route.points = List.copyOf(points); route.index = 0;
        route.progress = null; route.expanded = route.search.expanded; route.search = null; route.plannedAt = now; route.state = "ROUTE";
    }

    static boolean sweep(Vessel v, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from).multiply(1, 0, 1);
        if (delta.lengthSqr() < 0.01) return site(v, to, v.entity().getYRot()) != null;
        float yaw = directionYaw(delta);
        return sweepFacing(v, from, to, yaw);
    }
    static boolean sweepFacing(Vessel v, Vec3 from, Vec3 to, float yaw) {
        Vec3 delta = to.subtract(from).multiply(1, 0, 1);
        int steps = Math.max(1, Mth.ceil(delta.length() / 1.5));
        double previous = from.y;
        for (int i = 0; i <= steps; i++) {
            Vec3 horizontal = from.add(delta.scale((double) i / steps));
            Vec3 p = site(v, new Vec3(horizontal.x, previous, horizontal.z), yaw);
            if (p == null) return false;
            if (Math.abs(p.y - previous) > Math.max(1.1, v.entity().maxUpStep())) { reject("STEP_HEIGHT", BlockPos.containing(p)); return false; }
            previous = p.y;
        }
        return true;
    }
    static boolean reverseClear(Vessel v, Vec3 from, Vec3 to, float yaw) {
        Vec3 delta = to.subtract(from).multiply(1, 0, 1);
        int steps = Math.max(1, Mth.ceil(delta.length() / 1.25)); double previous = from.y;
        // An already close bumper may occupy the planning margin. Validate the rear sweep after leaving it.
        for (int i = 1; i <= steps; i++) {
            Vec3 horizontal = from.add(delta.scale((double) i / steps));
            Vec3 point = site(v, new Vec3(horizontal.x, previous, horizontal.z), yaw, 0.05);
            if (point == null || Math.abs(point.y - previous) > Math.max(1.1, v.entity().maxUpStep())) return false;
            previous = point.y;
        }
        return true;
    }
    @Nullable static Vec3 site(Vessel v, Vec3 point, float yaw) { return site(v, point, yaw, 0.75); }
    @Nullable private static Vec3 site(Vessel v, Vec3 point, float yaw, double margin) {
        rejection = null;
        Entity e = v.entity(); var shape = v.footprint();
        double angle = Math.toRadians(yaw), sine = Math.sin(angle), cosine = Math.cos(angle);
        double sin = Math.abs(sine), cos = Math.abs(cosine);
        double hx = shape.halfSize().x + margin, hz = shape.halfSize().z + margin;
        double rx = cos * hx + sin * hz, rz = sin * hx + cos * hz;
        Vec3 offset = shape.center().yRot((float) -angle);
        double x = point.x + offset.x, z = point.z + offset.z;
        if (!loaded(e, new AABB(x - rx, point.y, z - rz, x + rx, point.y + 1, z + rz))) return null;
        // Query collision surfaces near the road, not the topmost heightmap: roofs are not roads.
        double y = -Double.MAX_VALUE, lowest = Double.MAX_VALUE;
        double rise = Math.max(0.6, e.maxUpStep()), drop = 1.1;
        for (double dx : new double[]{-hx + 0.4, 0, hx - 0.4}) for (double dz : new double[]{-hz + 0.4, 0, hz - 0.4}) {
            double floor = floor(e, x + dx * cosine - dz * sine, z + dx * sine + dz * cosine, point.y, rise, drop);
            if (!Double.isFinite(floor)) return null;
            y = Math.max(y, floor); lowest = Math.min(lowest, floor);
        }
        if (y - lowest > Math.min(1.1, rise) + 0.01) return reject("UNEVEN_SUPPORT", BlockPos.containing(point));
        // Keep extra planning room at water edges for native turning/inertia; walls use the normal chassis margin.
        if (margin >= 0.5) {
            double waterX = shape.halfSize().x + 2, waterZ = shape.halfSize().z + 2;
            for (double dx : new double[]{-waterX, 0, waterX}) for (double dz : new double[]{-waterZ, 0, waterZ}) {
                BlockPos floor = BlockPos.containing(x + dx * cosine - dz * sine, y - 0.05, z + dx * sine + dz * cosine);
                if (!e.level().hasChunkAt(floor)) return reject("UNLOADED_CHUNK", floor);
                if (!e.level().getFluidState(floor).isEmpty()) return reject("FLUID", floor);
            }
        }
        AABB body = new AABB(x - rx, y + Math.max(0.18, shape.center().y - shape.halfSize().y + 0.18), z - rz,
                x + rx, y + shape.center().y + shape.halfSize().y, z + rz);
        if (!loaded(e, body)) return null;
        for (var collision : e.level().getBlockCollisions(e, body)) for (AABB block : collision.toAabbs()) {
            if (block.maxY <= body.minY || block.minY >= body.maxY) continue;
            double bx = block.getCenter().x - x, bz = block.getCenter().z - z;
            double qx = block.getXsize() / 2, qz = block.getZsize() / 2;
            // Separating axes of the oriented chassis and the block; broad AABB corners are not chassis.
            if (Math.abs(bx) < rx + qx - 1e-5 && Math.abs(bz) < rz + qz - 1e-5
                    && Math.abs(bx * cosine + bz * sine) < hx + qx * cos + qz * sin - 1e-5
                    && Math.abs(-bx * sine + bz * cosine) < hz + qx * sin + qz * cos - 1e-5) return reject("COLLISION", BlockPos.containing(block.getCenter()));
        }
        return new Vec3(point.x, y, point.z);
    }
    private static double floor(Entity e, double x, double z, double reference, double rise, double drop) {
        double best = Double.NaN;
        int bottom = Math.max(e.level().getMinBuildHeight(), Mth.floor(reference - drop - 1));
        int top = Math.min(e.level().getMaxBuildHeight() - 1, Mth.floor(reference + rise));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(Mth.floor(x), top, Mth.floor(z));
        for (int h = top; h >= bottom; h--) {
            pos.setY(h);
            // Water above a solid bottom is still a hazard; don't use the bottom as a dry road.
            if (!e.level().getFluidState(pos).isEmpty() && h + 1 >= reference - drop) {
                rejection = new Rejection("FLUID", pos.immutable()); return Double.NaN;
            }
            var collision = e.level().getBlockState(pos).getCollisionShape(e.level(), pos);
            if (collision.isEmpty()) continue;
            for (AABB box : collision.toAabbs()) {
                if (x < pos.getX() + box.minX || x > pos.getX() + box.maxX || z < pos.getZ() + box.minZ || z > pos.getZ() + box.maxZ) continue;
                double surface = h + box.maxY;
                if (surface < reference - drop - 0.01 || surface > reference + rise + 0.01) continue;
                if (!Double.isFinite(best) || surface > best) best = surface;
            }
            // A lower block inside the same solid column is not a second drivable surface.
            if (Double.isFinite(best)) return best;
        }
        rejection = new Rejection("NO_SUPPORT", BlockPos.containing(x, reference - 0.05, z));
        return best;
    }
    @Nullable private static Vec3 reject(String reason, BlockPos position) { rejection = new Rejection(reason, position.immutable()); return null; }
    private static float directionYaw(Vec3 delta) { return (float) Math.toDegrees(Math.atan2(-delta.x, delta.z)); }
    /** The chassis must fit throughout a pivot, including its intermediate headings. */
    static boolean turnClear(Vessel v, Vec3 point, float fromYaw, float toYaw) {
        float change = Mth.wrapDegrees(toYaw - fromYaw);
        int steps = Math.max(1, Mth.ceil(Math.abs(change) / 15));
        for (int i = 0; i <= steps; i++) if (site(v, point, fromYaw + change * i / steps) == null) return false;
        return true;
    }
    @Nullable private static Vec3 curve(Vessel v, Vec3 from, float fromYaw, float toYaw) {
        double change = Math.toRadians(Mth.wrapDegrees(toYaw - fromYaw));
        if (Math.abs(change) < 0.01) {
            Vec3 to = from.add(-Math.sin(Math.toRadians(fromYaw)) * CELL, 0, Math.cos(Math.toRadians(fromYaw)) * CELL);
            return sweepFacing(v, from, to, fromYaw) ? site(v, to, fromYaw) : null;
        }
        double radius = Math.max(6, v.footprint().halfSize().z * 2.2), start = Math.toRadians(fromYaw);
        int steps = Math.max(1, Mth.ceil(radius * Math.abs(change) / 1.5));
        Vec3 point = from; double previous = from.y;
        for (int i = 0; i <= steps; i++) {
            double angle = start + change * i / steps, sign = Math.signum(change);
            Vec3 candidate = new Vec3(from.x + radius * (Math.cos(angle) - Math.cos(start)) / sign, previous,
                    from.z + radius * (Math.sin(angle) - Math.sin(start)) / sign);
            point = site(v, candidate, (float) Math.toDegrees(angle));
            if (point == null || Math.abs(point.y - previous) > Math.max(1.1, v.entity().maxUpStep())) return null;
            previous = point.y;
        }
        return point;
    }
    private static boolean departure(Vessel v, Vec3 from, Vec3 to, float yaw) {
        Vec3 delta = to.subtract(from).multiply(1, 0, 1);
        if (delta.lengthSqr() < 0.01) return site(v, from, yaw) != null;
        float heading = directionYaw(delta);
        if (!v.engine().equals("TRACK") && Math.abs(Mth.wrapDegrees(heading - yaw)) > 45) { reject("TURN_ARC_NEEDED", BlockPos.containing(from)); return false; }
        return turnClear(v, from, yaw, heading) && sweepFacing(v, from, to, heading);
    }
    static boolean loaded(Entity e, AABB box) {
        if (box.minY < e.level().getMinBuildHeight() || box.maxY > e.level().getMaxBuildHeight()) { reject("HEIGHT_LIMIT", BlockPos.containing(box.getCenter())); return false; }
        if (!MovementBounds.contains(e, new Vec3(box.minX, box.minY, box.minZ), 2)
                || !MovementBounds.contains(e, new Vec3(box.maxX, box.maxY, box.maxZ), 2)) { reject("WORLD_BORDER", BlockPos.containing(box.getCenter())); return false; }
        for (int x = Mth.floor(box.minX) >> 4; x <= Mth.floor(box.maxX) >> 4; x++)
            for (int z = Mth.floor(box.minZ) >> 4; z <= Mth.floor(box.maxZ) >> 4; z++)
                if (!e.level().hasChunkAt(new BlockPos(x << 4, Mth.floor(box.minY), z << 4))) { reject("UNLOADED_CHUNK", new BlockPos(x << 4, Mth.floor(box.minY), z << 4)); return false; }
        return true;
    }
}
