package net.nuggetmc.tplus.api.agent;

import net.minecraft.world.entity.player.Player;
import net.nuggetmc.tplus.api.BotManager;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.event.BotDamageByPlayerEvent;
import net.nuggetmc.tplus.api.event.BotDeathEvent;
import net.nuggetmc.tplus.api.event.BotFallDamageEvent;
import net.nuggetmc.tplus.api.event.BotKilledByPlayerEvent;
import net.nuggetmc.tplus.api.scheduler.TaskScheduler;
import net.nuggetmc.tplus.api.scheduler.TickTask;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public abstract class Agent {

    protected final BotManager manager;
    protected final TaskScheduler scheduler;
    protected final Set<TickTask> taskList;
    protected final Random random;

    protected boolean enabled;
    protected TickTask tickTask;
    private long agentTicks;

    protected boolean drops;

    public Agent(BotManager manager) {
        this.manager = manager;
        this.scheduler = manager.getScheduler();
        this.taskList = new HashSet<>();
        this.random = new Random();

        setEnabled(true);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean b) {
        enabled = b;

        if (b) {
            if (tickTask == null || tickTask.isCancelled()) {
                tickTask = scheduler.runTaskTimer(this::tickAgent, 0, 1);
            }
        } else {
            if (tickTask != null) {
                tickTask.cancel();
                tickTask = null;
            }
            stopAllTasks();
        }
    }

    private void tickAgent() {
        // Finished tasks are only ever removed by stopAllTasks() in the plugin; prune them so the set doesn't grow forever.
        if (++agentTicks % 100 == 0 && !taskList.isEmpty()) {
            taskList.removeIf(TickTask::isCancelled);
        }

        tick();
    }

    public void stopAllTasks() {
        if (!taskList.isEmpty()) {
            taskList.stream().filter(t -> !t.isCancelled()).forEach(TickTask::cancel);
            taskList.clear();
        }
    }

    public void setDrops(boolean enabled) {
        this.drops = enabled;
    }

    protected abstract void tick();

    public void onFallDamage(BotFallDamageEvent event) {
    }

    public void onPlayerDamage(BotDamageByPlayerEvent event) {
    }

    public void onBotDeath(BotDeathEvent event) {
    }

    /**
     * Called once a bot has been removed from the world, so the agent can drop any state it keeps about it.
     */
    public void onBotRemoved(Terminator bot) {
    }

    public void onBotKilledByPlayer(BotKilledByPlayerEvent event) {
        Player player = event.getPlayer();
        Terminator bot = manager.getBot(player.getId());

        if (bot != null) {
            bot.incrementKills();
        }
    }
}
