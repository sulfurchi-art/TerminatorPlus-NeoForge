package net.nuggetmc.tplus.api.scheduler;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Tick based scheduler that replaces the Bukkit scheduler. {@link #tick()} is driven once per server tick
 * (at the start of the tick, like Bukkit's heartbeat) and every task runs on the server thread.
 * <p>
 * Timing follows Bukkit: a delay of 0 or 1 means "next tick", a period is the number of ticks between runs.
 */
public final class TaskScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Queue<TickTask> pending = new ConcurrentLinkedQueue<>();
    private final List<TickTask> tasks = new ArrayList<>();

    private volatile long currentTick;

    public TickTask runTask(Runnable runnable) {
        return runTaskLater(runnable, 0);
    }

    public TickTask runTaskLater(Runnable runnable, long delay) {
        return schedule(TickTask.of(runnable), delay, -1);
    }

    public TickTask runTaskLater(TickTask task, long delay) {
        return schedule(task, delay, -1);
    }

    public TickTask runTaskTimer(Runnable runnable, long delay, long period) {
        return schedule(TickTask.of(runnable), delay, period);
    }

    public TickTask runTaskTimer(TickTask task, long delay, long period) {
        return schedule(task, delay, period);
    }

    private TickTask schedule(TickTask task, long delay, long period) {
        task.delay = Math.max(0, delay);
        task.period = period;
        task.nextRun = currentTick + task.delay;
        pending.add(task);
        return task;
    }

    public void tick() {
        currentTick++;

        TickTask added;
        while ((added = pending.poll()) != null) {
            tasks.add(added);
        }

        // Tasks scheduled while this loop runs end up in "pending" and are picked up next tick.
        Iterator<TickTask> iterator = tasks.iterator();
        while (iterator.hasNext()) {
            TickTask task = iterator.next();

            if (task.isCancelled()) {
                iterator.remove();
                continue;
            }

            if (task.nextRun > currentTick) {
                continue;
            }

            try {
                task.run();
            } catch (Throwable throwable) {
                LOGGER.error("A TerminatorPlus task generated an exception", throwable);
            }

            if (task.period <= 0 || task.isCancelled()) {
                task.cancel();
                iterator.remove();
            } else {
                task.nextRun = currentTick + task.period;
            }
        }
    }

    /**
     * Cancels everything. Cancelled tasks are dropped by the next {@link #tick()}, so this is safe to call from inside a task.
     */
    public void cancelAll() {
        pending.forEach(TickTask::cancel);
        tasks.forEach(TickTask::cancel);
    }
}
