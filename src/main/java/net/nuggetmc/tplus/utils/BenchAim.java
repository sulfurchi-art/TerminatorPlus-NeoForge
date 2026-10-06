package net.nuggetmc.tplus.utils;

import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.ArrowAim;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** Test-only offload of the existing mathematical bow solver. Workers receive primitive records only. */
public final class BenchAim {
    public record Request(int actor, int target, int tick, double sx, double sy, double sz,
                          double tx, double ty, double tz, double vx, double vy, double vz) { }
    public record Plan(@Nullable ArrowAim.Solution solution, double gx, double gy, double gz) { }
    private record Result(Request request, Plan plan) { }
    private static final class Slot {
        volatile boolean busy;
        volatile Result result;
    }
    private static final Map<Integer, Slot> slots = new HashMap<>();
    private static ThreadPoolExecutor pool;
    private static volatile long epoch;
    public static LongAdder submitted = new LongAdder(), completed = new LongAdder(), reused = new LongAdder(),
            fallback = new LongAdder(), stale = new LongAdder(), rejected = new LongAdder(),
            workerNanos = new LongAdder(), waitNanos = new LongAdder(), resultAge = new LongAdder();
    public static volatile Throwable failure;

    private BenchAim() { }

    public static void reset(int workers) {
        epoch++;
        if (pool != null) pool.shutdownNow();
        pool = null;
        slots.clear();
        submitted = new LongAdder(); completed = new LongAdder(); reused = new LongAdder(); fallback = new LongAdder();
        stale = new LongAdder(); rejected = new LongAdder(); workerNanos = new LongAdder(); waitNanos = new LongAdder(); resultAge = new LongAdder();
        failure = null;
        if (workers > 0) {
            pool = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), r -> {
                Thread t = new Thread(r, "tp-ai-bench-worker");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
            pool.prestartAllCoreThreads();
        }
    }

    public static Plan choose(Request request) {
        if (pool == null) return compute(request);
        Slot slot = slots.computeIfAbsent(request.actor(), ignored -> new Slot());
        Result result = slot.result;
        if (!slot.busy) {
            slot.busy = true;
            long taskEpoch = epoch;
            long queued = System.nanoTime();
            LongAdder submittedCounter = submitted, completedCounter = completed, timeCounter = workerNanos, waitCounter = waitNanos;
            try {
                pool.execute(() -> {
                    long start = System.nanoTime();
                    try {
                        Plan plan = compute(request);
                        if (epoch == taskEpoch) {
                            slot.result = new Result(request, plan);
                            completedCounter.increment();
                            timeCounter.add(System.nanoTime() - start);
                            waitCounter.add(start - queued);
                        }
                    } catch (Throwable t) { failure = t; }
                    finally { slot.busy = false; }
                });
                submittedCounter.increment();
            } catch (RejectedExecutionException ex) { slot.busy = false; rejected.increment(); }
        }
        if (result != null) {
            Request old = result.request();
            int age = request.tick() - old.tick();
            if (usable(request, old)) {
                reused.increment(); resultAge.add(age);
                return result.plan();
            }
            stale.increment();
        }
        // Startup or invalid results use the exact synchronous calculation. Never wait on a worker from the server thread.
        fallback.increment();
        return compute(request);
    }

    public static boolean usable(Request current, Request old) {
        int age=current.tick()-old.tick();
        double movement=distanceSquared(current.sx(),current.sy(),current.sz(),old.sx(),old.sy(),old.sz())
                + distanceSquared(current.tx(),current.ty(),current.tz(),old.tx(),old.ty(),old.tz());
        return current.actor()==old.actor() && current.target()==old.target() && age>=0 && age<=2 && movement<=0.25;
    }

    private static double distanceSquared(double x, double y, double z, double a, double b, double c) {
        return (x-a)*(x-a) + (y-b)*(y-b) + (z-c)*(z-c);
    }

    public static Plan compute(Request r) {
        double gx = r.tx(), gy = r.ty(), gz = r.tz();
        ArrowAim.Solution solution = fixed(r.sx(), r.sy(), r.sz(), gx, gy, gz);
        for (int i = 0; i < 2 && solution != null; i++) {
            gx = r.tx() + r.vx() * solution.ticks();
            gy = r.ty() + r.vy() * solution.ticks();
            gz = r.tz() + r.vz() * solution.ticks();
            solution = fixed(r.sx(), r.sy(), r.sz(), gx, gy, gz);
        }
        return new Plan(solution, gx, gy, gz);
    }

    private static ArrowAim.Solution fixed(double sx, double sy, double sz, double gx, double gy, double gz) {
        double distance = Math.sqrt((sx-gx)*(sx-gx) + (sz-gz)*(sz-gz));
        double rise = gy-sy;
        float yaw = (float)Math.toDegrees(Math.atan2(-(gx-sx), gz-sz));
        double[] high = height(40, distance);
        if (high == null || high[0] < rise) return null;
        double low = -60, up = 40;
        double[] result = high;
        for (int i = 0; i < 24; i++) {
            double mid = (low+up)/2;
            double[] current = height(mid, distance);
            if (current == null || current[0] < rise) low = mid;
            else { up = mid; result = current; }
        }
        return new ArrowAim.Solution(yaw, (float)-up, (int)Math.ceil(result[1]));
    }

    private static double[] height(double elevation, double distance) {
        double rad = Math.toRadians(elevation), vh = Math.cos(rad)*3, vy = Math.sin(rad)*3, h=0, y=0;
        for (int tick=1; tick<=100; tick++) {
            double nh=h+vh, ny=y+vy;
            if (nh>=distance) {
                double part=vh<=0 ? 0 : (distance-h)/vh;
                return new double[]{y+vy*part, tick-1+part};
            }
            h=nh; y=ny; vh*=0.99; vy=vy*0.99-0.05;
        }
        return null;
    }

    /** Verify pure computations against the unmodified original private solver, including through an executor. */
    public static CompletableFuture<String> verify() {
        return CompletableFuture.supplyAsync(() -> {
            var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                Method method = ArrowAim.class.getDeclaredMethod("solveFixed", Vec3.class, Vec3.class);
                method.setAccessible(true);
                Random random = new Random(1042026);
                for (int i=0; i<256; i++) {
                    Request r = new Request(i, i+1, 1, random.nextDouble()*20, 70, random.nextDouble()*20,
                            random.nextDouble()*100-50, random.nextDouble()*12+64, random.nextDouble()*100-50,
                            random.nextDouble()*0.2-0.1, 0, random.nextDouble()*0.2-0.1);
                    Vec3 start = new Vec3(r.sx(),r.sy(),r.sz());
                    Vec3 center = new Vec3(r.tx(),r.ty(),r.tz());
                    Vec3 velocity = new Vec3(r.vx(),r.vy(),r.vz());
                    Vec3 goal = center;
                    ArrowAim.Solution expected=(ArrowAim.Solution)method.invoke(null,start,goal);
                    for(int n=0;n<2 && expected!=null;n++) {
                        goal=center.add(velocity.scale(expected.ticks()));
                        expected=(ArrowAim.Solution)method.invoke(null,start,goal);
                    }
                    Plan actual=workers.submit(() -> compute(r)).get(5,TimeUnit.SECONDS);
                    if (!java.util.Objects.equals(expected,actual.solution())
                            || distanceSquared(goal.x,goal.y,goal.z,actual.gx(),actual.gy(),actual.gz()) > 1e-20)
                        throw new IllegalStateException("Different result at snapshot " + i);
                }
                for(var component:Request.class.getRecordComponents())
                    if(!component.getType().isPrimitive()) throw new IllegalStateException("Mutable world reference in snapshot");
                Request old=new Request(1,2,10,0,70,0,30,70,0,0,0,0);
                if(!usable(new Request(1,2,11,0,70,0,30,70,0,0,0,0),old)
                        || usable(new Request(1,2,13,0,70,0,30,70,0,0,0,0),old)
                        || usable(new Request(1,3,11,0,70,0,30,70,0,0,0,0),old)
                        || usable(new Request(3,2,11,0,70,0,30,70,0,0,0,0),old)
                        || usable(new Request(1,2,11,0,70,0,31,70,0,0,0,0),old)
                        || usable(new Request(1,2,9,0,70,0,30,70,0,0,0,0),old))
                    throw new IllegalStateException("Invalid async result acceptance");
                return "PASS: 256 snapshots exactly match original math on workers; primitive-only snapshots and six validity cases pass";
            } catch (Exception ex) { throw new RuntimeException(ex); }
            finally { workers.shutdownNow(); }
        });
    }
}
