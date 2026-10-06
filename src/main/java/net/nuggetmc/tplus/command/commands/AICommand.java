package net.nuggetmc.tplus.command.commands;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.AIManager;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.IntelligenceAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;
import net.nuggetmc.tplus.api.utils.ChatUtils;
import net.nuggetmc.tplus.api.utils.Location;
import net.nuggetmc.tplus.api.utils.MathUtils;
import net.nuggetmc.tplus.command.CommandHandler;
import net.nuggetmc.tplus.command.CommandInstance;
import net.nuggetmc.tplus.command.CommandUtils;
import net.nuggetmc.tplus.command.annotation.*;

import java.util.ArrayList;
import java.util.List;

public class AICommand extends CommandInstance implements AIManager {

    /*
     * ideas
     * ability to export neural network data to a text file, and also load from them
     * maybe also have a custom extension like .tplus and encrypt it in base64
     */

    private IntelligenceAgent agent;

    public AICommand(CommandHandler handler, String name, String description, String... aliases) {
        super(handler, name, description, aliases);
    }

    @Command
    public void root(CommandSourceStack sender, List<String> args) {
        commandHandler.sendRootInfo(this, sender);
    }

    @Command(
            name = "random",
            desc = "Create bots with random neural networks, collecting feed data."
    )
    public void random(CommandSourceStack sender, List<String> args, @Arg("amount") int amount, @Arg("name") String name, @OptArg("skin") String skin, @OptArg("loc") @TextArg String loc) {
        if (sender.getPlayer() != null && args.size() < 2) {
            send(sender, ChatFormatting.RED + "Usage: /ai random <amount> <name> [skin] [spawnLoc: [player Player]/[x,y,z]]");
            return;
        }

        Location location = CommandUtils.parseSpawnLocation(sender, loc);

        if (location != null) {
            manager().createBots(sender, name, skin, amount, NeuralNetwork.RANDOM, location);
        }
    }

    @Command(
            name = "reinforcement",
            desc = "Begin an AI training session."
    )
    public void reinforcement(ServerPlayer sender, @Arg("population-size") int populationSize, @Arg("name") String name, @OptArg("skin") String skin) {
        //FIXME: Sometimes, bots will become invisible, or just stop working if they're the last one alive, this has been partially fixed (invis part) see Terminator#removeBot, which removes the bot.
        //This seems to fix it for the most part, but its still buggy, as the bot will sometimes still freeze
        //see https://cdn.carbonhost.cloud/6201479d7b237373ab269385/screenshots/javaw_DluMN4m0FR.png
        //Blocks are also not placeable where bots have died
        CommandSourceStack source = sender.createCommandSourceStack();

        if (agent != null) {
            send(source, "A session is already active.");
            return;
        }

        send(source, "Starting a new session...");

        agent = new IntelligenceAgent(this, populationSize, name, skin, manager());
        agent.addUser(source);
    }

    public IntelligenceAgent getSession() {
        return agent;
    }

    @Command(
            name = "stop",
            desc = "End a currently running AI training session."
    )
    public void stop(CommandSourceStack sender) {
        if (agent == null) {
            send(sender, "No session is currently active.");
            return;
        }

        send(sender, "Stopping the current session...");
        String name = agent.getName();
        clearSession();

        TerminatorPlus.getScheduler().runTaskLater(() -> send(sender, "The session " + ChatFormatting.YELLOW + name + ChatFormatting.RESET + " has been closed."), 10);
    }

    @Override
    public void clearSession() {
        if (agent != null) {
            agent.stop();
            agent = null;
        }
    }

    @Override
    public void clearSession(Object session) {
        if (agent == session) {
            clearSession();
        }
    }

    public boolean hasActiveSession() {
        return agent != null;
    }

    @Command(
            name = "info",
            desc = "Display neural network information about a bot.",
            autofill = "infoAutofill"
    )
    public void info(CommandSourceStack sender, @Arg("bot-name") String name) {
        send(sender, "Processing request...");

        try {
            Terminator bot = manager().getFirst(name, sender.getPlayer() != null ? Location.of(sender.getPlayer()) : null);

            if (bot == null) {
                send(sender, "Could not find bot " + ChatFormatting.GREEN + name + ChatFormatting.RESET + "!");
                return;
            }

            if (!bot.hasNeuralNetwork()) {
                send(sender, "The bot " + ChatFormatting.GREEN + name + ChatFormatting.RESET + " does not have a neural network!");
                return;
            }

            NeuralNetwork network = bot.getNeuralNetwork();
            List<String> strings = new ArrayList<>();

            network.nodes().forEach((nodeType, node) -> {
                strings.add("");
                strings.add(ChatFormatting.YELLOW + "\"" + nodeType.name().toLowerCase() + "\"" + ChatFormatting.RESET + ":");
                List<String> values = new ArrayList<>();
                node.getValues().forEach((dataType, value) -> values.add(ChatUtils.BULLET_FORMATTED + "node"
                        + dataType.getShorthand().toUpperCase() + ": " + ChatFormatting.RED + MathUtils.round2Dec(value)));
                strings.addAll(values);
            });

            send(sender, ChatUtils.LINE);
            send(sender, ChatFormatting.DARK_GREEN + "NeuralNetwork" + ChatUtils.BULLET_FORMATTED + ChatFormatting.GRAY + "[" + ChatFormatting.GREEN + name + ChatFormatting.GRAY + "]");
            strings.forEach(line -> send(sender, line));
            send(sender, ChatUtils.LINE);
        } catch (Exception e) {
            send(sender, ChatUtils.EXCEPTION_MESSAGE);
        }
    }

    @Autofill
    public List<String> infoAutofill(CommandSourceStack sender, String[] args) {
        return args.length == 2 ? manager().fetchNames() : new ArrayList<>();
    }
}
