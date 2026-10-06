package net.nuggetmc.tplus.api.scheduler;

/**
 * Stand-in for Bukkit's {@code BukkitRunnable}: a task that can be scheduled on the {@link TaskScheduler}
 * and cancel itself from inside {@link #run()}.
 */
public abstract class TickTask implements Runnable {

    private volatile boolean cancelled;

    long delay;
    long period;
    long nextRun;

    public static TickTask of(Runnable runnable) {
        return new TickTask() {
            @Override
            public void run() {
                runnable.run();
            }
        };
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
