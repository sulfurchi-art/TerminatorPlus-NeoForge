package net.nuggetmc.tplus.command.commands;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.utils.ChatUtils;
import net.nuggetmc.tplus.command.CommandHandler;
import net.nuggetmc.tplus.command.CommandInstance;
import net.nuggetmc.tplus.command.annotation.Command;
import net.nuggetmc.tplus.utils.MCLogs;

import java.util.concurrent.CompletableFuture;

public class MainCommand extends CommandInstance {

    private Component rootInfo;

    public MainCommand(CommandHandler handler, String name, String description, String... aliases) {
        super(handler, name, description, aliases);
    }

    @Command
    public void root(CommandSourceStack sender) {
        if (rootInfo == null) {
            rootInfoSetup();
        }

        sender.sendSystemMessage(rootInfo);
    }

    @Command(
            name = "debuginfo",
            desc = "Upload debug info to mclo.gs"
    )
    public void debugInfo(CommandSourceStack sender) {
        send(sender, ChatFormatting.GREEN + "Uploading debug info to mclogs...");

        MinecraftServer server = sender.getServer();
        String info = MCLogs.collectInfo(server);

        CompletableFuture.supplyAsync(() -> {
            try {
                return MCLogs.pasteText(info);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, Util.ioPool()).whenCompleteAsync((url, throwable) -> {
            if (throwable != null) {
                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                send(sender, ChatFormatting.RED + "Failed to upload debug info to mclogs: " + cause.getMessage());
                return;
            }

            send(sender, ChatFormatting.GREEN + "Debug info uploaded to " + url);
        }, server);
    }

    private static MutableComponent link(String label, ChatFormatting color, String url, String hover) {
        return Component.literal(label).withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, ChatUtils.legacy(hover))));
    }

    private void rootInfoSetup() {
        MutableComponent message = Component.empty();
        String pluginName = TerminatorPlus.NAME;

        message.append(ChatUtils.legacy(ChatUtils.LINE + "\n"));
        message.append(ChatUtils.legacy(ChatFormatting.GOLD + pluginName + ChatFormatting.GRAY + " [v" + TerminatorPlus.getVersion() + "]\n"));
        message.append(ChatUtils.legacy("\nPlugin Information:\n"));
        message.append(ChatUtils.legacy(ChatUtils.BULLET_FORMATTED + "Author" + ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "HorseNuggets\n"));
        message.append(ChatUtils.legacy(ChatUtils.BULLET_FORMATTED + "Links" + ChatUtils.BULLET_FORMATTED));
        message.append(link("YouTube", ChatFormatting.RED, "https://youtube.com/horsenuggets",
                "Click to visit HorseNuggets' " + ChatFormatting.RED + "YouTube" + ChatFormatting.RESET + "!"));
        message.append(Component.literal(", "));
        message.append(link("Discord", ChatFormatting.BLUE, "https://discord.gg/vZVSf2D6mz",
                "Click to visit HorseNuggets' " + ChatFormatting.BLUE + "Discord" + ChatFormatting.RESET + "!"));
        message.append(Component.literal("\n"));
        message.append(ChatUtils.legacy("\nPlugin Commands:\n"));

        commandHandler.getCommands().forEach((name, command) -> {
            if (!name.equalsIgnoreCase(pluginName)) {
                message.append(ChatUtils.legacy(ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "/" + name + ChatUtils.BULLET_FORMATTED + command.getDescription() + "\n"));
            }
        });

        message.append(ChatUtils.legacy(ChatUtils.LINE));

        this.rootInfo = message;
    }
}
