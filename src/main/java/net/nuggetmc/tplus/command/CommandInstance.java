package net.nuggetmc.tplus.command;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.utils.ChatUtils;
import net.nuggetmc.tplus.bot.BotManagerImpl;
import net.nuggetmc.tplus.command.annotation.Arg;
import net.nuggetmc.tplus.command.annotation.OptArg;
import net.nuggetmc.tplus.command.annotation.TextArg;
import net.nuggetmc.tplus.command.exception.ArgCountException;
import net.nuggetmc.tplus.command.exception.ArgParseException;
import net.nuggetmc.tplus.command.exception.NonPlayerException;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A root command ({@code /bot}, {@code /ai}...) whose sub commands are the {@code @Command} methods of the subclass.
 * Parameters are filled from the space separated arguments, exactly like the Bukkit version of the framework:
 * {@link CommandSourceStack} (the sender), {@link ServerPlayer} (player-only command), {@link List} (raw arguments),
 * String/int/double/float/boolean.
 */
public abstract class CommandInstance {

    private static final Logger LOGGER = LogUtils.getLogger();

    protected final CommandHandler commandHandler;
    private final String name;
    private final String description;
    private final List<String> aliases;
    private final Map<String, CommandMethod> methods;
    private final Map<String, String> aliasesToNames;

    public CommandInstance(CommandHandler handler, String name, String description, @Nullable String... aliases) {
        this.commandHandler = handler;
        this.name = name;
        this.description = description;
        this.aliases = aliases == null ? new ArrayList<>() : Arrays.asList(aliases);
        this.methods = new HashMap<>();
        this.aliasesToNames = new HashMap<>();
    }

    public static String getArgumentName(Parameter parameter) {
        if (parameter.isAnnotationPresent(OptArg.class)) {
            OptArg arg = parameter.getAnnotation(OptArg.class);

            if (!arg.value().isEmpty()) {
                return "[" + ChatUtils.camelToDashed(arg.value()) + "]";
            }
        } else if (parameter.isAnnotationPresent(Arg.class)) {
            Arg arg = parameter.getAnnotation(Arg.class);

            if (!arg.value().isEmpty()) {
                return "<" + ChatUtils.camelToDashed(arg.value()) + ">";
            }
        }

        return "<" + ChatUtils.camelToDashed(parameter.getName()) + ">";
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public Map<String, CommandMethod> getMethods() {
        return methods;
    }

    protected void addMethod(String name, CommandMethod method) {
        methods.put(name, method);
    }

    protected void addAlias(String alias, String name) {
        aliasesToNames.put(alias, name);
    }

    protected static BotManagerImpl manager() {
        return TerminatorPlus.getManager();
    }

    protected static void send(CommandSourceStack sender, String message) {
        ChatUtils.send(sender, message);
    }

    public boolean execute(CommandSourceStack sender, String label, String[] args) {
        if (TerminatorPlus.getManager() == null) {
            send(sender, ChatFormatting.RED + "TerminatorPlus is not running on this server yet.");
            return false;
        }

        CommandMethod method;

        if (args.length == 0) {
            method = methods.get("");
        } else if (methods.containsKey(aliasesToNames.getOrDefault(args[0], args[0]))) {
            method = methods.get(aliasesToNames.getOrDefault(args[0], args[0]));
        } else {
            method = methods.get("");
        }

        if (method == null) {
            send(sender, ChatFormatting.RED + "There is no root command present for the " + ChatFormatting.YELLOW + getName() + ChatFormatting.RED + " command.");
            return true;
        }

        List<String> arguments = new ArrayList<>(Arrays.asList(args));

        if (!arguments.isEmpty()) {
            arguments.remove(0);
        }

        List<Object> parsedArguments = new ArrayList<>();

        int index = 0;

        try {
            for (Parameter parameter : method.getMethod().getParameters()) {
                Class<?> type = parameter.getType();

                boolean required = !parameter.isAnnotationPresent(OptArg.class);

                if (type == CommandSourceStack.class) {
                    parsedArguments.add(sender);
                } else if (type == ServerPlayer.class) {
                    ServerPlayer player = sender.getPlayer();

                    if (player == null) {
                        throw new NonPlayerException();
                    }

                    parsedArguments.add(player);
                } else if (type == List.class) {
                    parsedArguments.add(arguments);
                } else {
                    if (parameter.isAnnotationPresent(TextArg.class)) {
                        if (index >= arguments.size()) {
                            parsedArguments.add("");
                        } else {
                            parsedArguments.add(String.join(" ", arguments.subList(index, arguments.size())));
                        }

                        continue;
                    }

                    if (index >= arguments.size() && required) {
                        throw new ArgCountException();
                    }

                    String arg;

                    if (index >= arguments.size()) {
                        arg = null;
                    } else {
                        arg = arguments.get(index);
                    }

                    index++;

                    if (type == String.class) {
                        parsedArguments.add(arg);
                    } else if (type == int.class) {
                        if (arg == null) {
                            parsedArguments.add(0);
                            continue;
                        }

                        try {
                            parsedArguments.add(Integer.parseInt(arg));
                        } catch (NumberFormatException e) {
                            throw new ArgParseException(parameter);
                        }
                    } else if (type == double.class) {
                        if (arg == null) {
                            parsedArguments.add(0D);
                            continue;
                        }

                        try {
                            parsedArguments.add(Double.parseDouble(arg));
                        } catch (NumberFormatException e) {
                            throw new ArgParseException(parameter);
                        }
                    } else if (type == float.class) {
                        if (arg == null) {
                            parsedArguments.add(0F);
                            continue;
                        }

                        try {
                            parsedArguments.add(Float.parseFloat(arg));
                        } catch (NumberFormatException e) {
                            throw new ArgParseException(parameter);
                        }
                    } else if (type == boolean.class) {
                        if (arg == null) {
                            parsedArguments.add(false);
                            continue;
                        }

                        if (arg.equalsIgnoreCase("true") || arg.equalsIgnoreCase("false")) {
                            parsedArguments.add(Boolean.parseBoolean(arg));
                        } else {
                            throw new ArgParseException(parameter);
                        }
                    } else {
                        parsedArguments.add(arg);
                    }
                }
            }
        } catch (NonPlayerException e) {
            send(sender, "This is a player-only command.");
            return true;
        } catch (ArgParseException e) {
            Parameter parameter = e.getParameter();
            String name = getArgumentName(parameter);
            send(sender, "The parameter " + ChatFormatting.YELLOW + name + ChatFormatting.RESET + " must be of type " + ChatFormatting.YELLOW + parameter.getType().toString() + ChatFormatting.RESET + ".");
            return true;
        } catch (ArgCountException e) {
            List<String> usageArgs = new ArrayList<>();

            Arrays.stream(method.getMethod().getParameters()).forEach(parameter -> {
                Class<?> type = parameter.getType();

                if (type != CommandSourceStack.class && type != ServerPlayer.class && type != List.class) {
                    usageArgs.add(getArgumentName(parameter));
                }
            });

            send(sender, "Command Usage: " + ChatFormatting.YELLOW + "/" + getName() + (method.getName().isEmpty() ? "" : " " + method.getName())
                    + " " + String.join(" ", usageArgs));
            return true;
        }

        try {
            method.getMethod().invoke(method.getHandler(), parsedArguments.toArray());
        } catch (InvocationTargetException | IllegalAccessException e) {
            send(sender, ChatFormatting.RED + "Failed to perform command.");
            LOGGER.error("Failed to perform command /{} {}", label, String.join(" ", args), e instanceof InvocationTargetException ite ? ite.getCause() : e);
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    public List<String> tabComplete(CommandSourceStack sender, String label, String[] args) {
        if (args.length == 1) {
            List<String> result = methods.keySet().stream().filter(c -> !c.isEmpty() && c.contains(args[0])).collect(Collectors.toList());
            if (result.isEmpty()) {
                // Add aliases also
                methods.forEach((s, m) -> result.addAll(m.getAliases()));
                return result.stream().filter(c -> c.contains(args[0])).collect(Collectors.toList());
            }
            return result;
        }

        if (args.length > 1) {
            CommandMethod commandMethod = methods.get(args[0]);
            if (commandMethod == null)
                commandMethod = methods.values().stream().filter(m -> m.getAliases().contains(args[0])).findFirst().orElse(null);
            if (commandMethod == null) return new ArrayList<>();
            Method autofiller = commandMethod.getAutofiller();

            if (autofiller != null && TerminatorPlus.getManager() != null) {
                try {
                    return ((List<String>) autofiller.invoke(commandMethod.getHandler(), sender, args)).stream().filter(c -> c.contains(args[args.length - 1])).collect(Collectors.toList());
                } catch (InvocationTargetException | IllegalAccessException e) {
                    LOGGER.error("Failed to tab complete /{} {}", label, String.join(" ", args), e);
                }
            }
        }

        return new ArrayList<>();
    }
}
