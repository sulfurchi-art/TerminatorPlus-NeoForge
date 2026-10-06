package net.nuggetmc.tplus.command;

import com.google.common.collect.Sets;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.utils.ChatUtils;
import net.nuggetmc.tplus.api.utils.DebugLogUtils;
import net.nuggetmc.tplus.command.annotation.Command;
import net.nuggetmc.tplus.command.annotation.Require;
import net.nuggetmc.tplus.command.commands.AICommand;
import net.nuggetmc.tplus.command.commands.BotCommand;
import net.nuggetmc.tplus.command.commands.BotEnvironmentCommand;
import net.nuggetmc.tplus.command.commands.MainCommand;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Collects the annotated command classes and exposes each of them (plus aliases) to Brigadier as
 * {@code /<name> [args...]}, with a greedy argument so the reflective framework can keep doing its own parsing.
 */
public class CommandHandler {

    private final Map<String, List<String>> help;
    private final Map<String, CommandInstance> commandMap;

    public CommandHandler() {
        this.help = new HashMap<>();
        this.commandMap = new LinkedHashMap<>();
        this.registerCommands();
    }

    public Map<String, CommandInstance> getCommands() {
        return commandMap;
    }

    private void registerCommands() {
        registerCommands(
                new MainCommand(this, "terminatorplus", "The TerminatorPlus main command.", "tplus"),
                new BotCommand(this, "bot", "The root command for bot management.", "npc"),
                new AICommand(this, "ai", "The root command for bot AI training."),
                new BotEnvironmentCommand(this, "botenvironment", "Do /botenvironment help for more information.", "botenv")
        );
    }

    private void registerCommands(CommandInstance... commands) {
        for (CommandInstance command : commands) {
            commandMap.put(command.getName(), command);

            Method[] methods = command.getClass().getMethods();

            for (Method method : methods) {
                if (method.isAnnotationPresent(Command.class)) {
                    try {
                        method.setAccessible(true);
                    } catch (SecurityException e) {
                        DebugLogUtils.log("Failed to access method " + method.getName() + ".");
                        continue;
                    }

                    Command cmd = method.getAnnotation(Command.class);

                    String perm = "";
                    if (method.isAnnotationPresent(Require.class)) {
                        Require require = method.getAnnotation(Require.class);
                        perm = require.value();
                    }

                    String autofillName = cmd.autofill();
                    Method autofiller = null;

                    if (!autofillName.isEmpty()) {
                        for (Method m : methods) {
                            if (m.getName().equals(autofillName)) {
                                autofiller = m;
                            }
                        }
                    }

                    String methodName = cmd.name();
                    CommandMethod commandMethod = new CommandMethod(methodName, Sets.newHashSet(cmd.aliases()), cmd.desc(), perm, command, method, autofiller);

                    command.addMethod(methodName, commandMethod);
                    commandMethod.getAliases().forEach(alias -> command.addAlias(alias, methodName));
                }
            }

            setHelp(command);
        }
    }

    /**
     * Called from {@code RegisterCommandsEvent}, i.e. on every server start and datapack reload.
     */
    public void registerBrigadier(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (CommandInstance command : commandMap.values()) {
            List<String> labels = new ArrayList<>();
            labels.add(command.getName());
            labels.addAll(command.getAliases());

            for (String label : labels) {
                dispatcher.register(Commands.literal(label)
                        .requires(TerminatorPlus::canManage)
                        .executes(ctx -> command.execute(ctx.getSource(), label, new String[0]) ? 1 : 0)
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> suggest(command, label, ctx.getSource(), builder))
                                .executes(ctx -> command.execute(ctx.getSource(), label, splitArgs(StringArgumentType.getString(ctx, "args"))) ? 1 : 0)));
            }
        }
    }

    private static String[] splitArgs(String input) {
        return input.isEmpty() ? new String[0] : input.split(" ");
    }

    private static CompletableFuture<Suggestions> suggest(CommandInstance command, String label, CommandSourceStack source, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        // Like Bukkit's tab completion: a trailing space starts a new (empty) argument.
        String[] args = remaining.split(" ", -1);

        List<String> completions = command.tabComplete(source, label, args);

        SuggestionsBuilder offset = builder.createOffset(builder.getStart() + remaining.lastIndexOf(' ') + 1);
        completions.stream().distinct().sorted().forEach(offset::suggest);

        return offset.buildFuture();
    }

    public CommandInstance getCommand(String name) {
        return commandMap.get(name);
    }

    public void sendRootInfo(CommandInstance commandInstance, CommandSourceStack sender) {
        ChatUtils.send(sender, ChatUtils.LINE);
        ChatUtils.send(sender, ChatFormatting.GOLD + TerminatorPlus.NAME + ChatUtils.BULLET_FORMATTED + ChatFormatting.GRAY
                + "[" + ChatFormatting.YELLOW + "/" + commandInstance.getName() + ChatFormatting.GRAY + "]");
        help.get(commandInstance.getName()).forEach(line -> ChatUtils.send(sender, line));
        ChatUtils.send(sender, ChatUtils.LINE);
    }

    private void setHelp(CommandInstance commandInstance) {
        help.put(commandInstance.getName(), getCommandInfo(commandInstance));
    }

    private List<String> getCommandInfo(CommandInstance commandInstance) {
        List<String> output = new ArrayList<>();

        for (CommandMethod method : commandInstance.getMethods().values()) {
            if (!method.getMethod().getAnnotation(Command.class).visible() || method.getName().isEmpty()) {
                continue;
            }

            output.add(ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "/" + commandInstance.getName() + " " + method.getName()
                    + ChatUtils.BULLET_FORMATTED + method.getDescription());
        }

        return output.stream().sorted().collect(Collectors.toList());
    }
}
