package net.nuggetmc.tplus.command;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.nuggetmc.tplus.api.utils.ChatUtils;
import net.nuggetmc.tplus.api.utils.Location;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Helpers shared by the commands (the plugin had these copy-pasted in every command class).
 */
public final class CommandUtils {

    private CommandUtils() {
    }

    /**
     * Where a command sender "is": the player's position, or (0, 0, 0) in the overworld for the console.
     */
    public static Location senderLocation(CommandSourceStack sender) {
        ServerPlayer player = sender.getPlayer();
        return player != null ? Location.of(player) : new Location(sender.getServer().overworld(), 0, 0, 0);
    }

    /**
     * Parses the optional spawn location argument of {@code /bot create}, {@code /bot multi} and {@code /ai random}:
     * either a player name or {@code <x> <y> <z> [dimension]}. Sends an error and returns {@code null} if it's invalid.
     */
    @Nullable
    public static Location parseSpawnLocation(CommandSourceStack sender, @Nullable String loc) {
        Location location = senderLocation(sender);
        boolean isPlayer = sender.getPlayer() != null;

        if (loc != null && !loc.isEmpty()) {
            ServerPlayer player = sender.getServer().getPlayerList().getPlayerByName(loc);
            if (player != null) {
                return Location.of(player);
            }

            String[] split = loc.split(" ");
            if (split.length >= 3) {
                Location relativeTo = isPlayer ? location : null;
                try {
                    double x = parseDoubleOrRelative(split[0], relativeTo, 0);
                    double y = parseDoubleOrRelative(split[1], relativeTo, 1);
                    double z = parseDoubleOrRelative(split[2], relativeTo, 2);
                    ServerLevel level = split.length >= 4 ? getLevel(sender.getServer(), split[3]) : location.level();
                    if (level == null) {
                        ChatUtils.send(sender, "The dimension '" + ChatFormatting.YELLOW + split[3] + ChatFormatting.RESET + "' does not exist!");
                        return null;
                    }
                    return new Location(level, x, y, z);
                } catch (NumberFormatException e) {
                    ChatUtils.send(sender, "The location '" + ChatFormatting.YELLOW + loc + ChatFormatting.RESET + "' is not valid!");
                    return null;
                }
            } else {
                ChatUtils.send(sender, "The location '" + ChatFormatting.YELLOW + loc + ChatFormatting.RESET + "' is not valid!");
                return null;
            }
        } else if (!isPlayer) {
            ChatUtils.send(sender, "Spawning bot at 0, 0, 0 in world " + location.level().dimension().location() + " because no location was specified.");
        }

        return location;
    }

    /**
     * Resolves a dimension id ({@code minecraft:the_nether}, {@code the_nether}) or a Bukkit world name
     * ({@code world}, {@code world_nether}, {@code world_the_end}).
     */
    @Nullable
    public static ServerLevel getLevel(MinecraftServer server, String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        switch (lower) {
            case "world":
                return server.getLevel(Level.OVERWORLD);
            case "world_nether":
                return server.getLevel(Level.NETHER);
            case "world_the_end":
                return server.getLevel(Level.END);
        }

        ResourceLocation id = ResourceLocation.tryParse(lower);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    /**
     * Parses a coordinate that may be relative ({@code ~}, {@code ~-2.5}) to {@code loc}.
     *
     * @param type 0 = x, 1 = y, 2 = z
     */
    public static double parseDoubleOrRelative(String pos, @Nullable Location loc, int type) {
        if (loc == null || pos.isEmpty() || pos.charAt(0) != '~')
            return Double.parseDouble(pos);
        double relative = pos.length() == 1 ? 0 : Double.parseDouble(pos.substring(1));
        return switch (type) {
            case 0 -> relative + Math.round(loc.x() * 1000) / 1000D;
            case 1 -> relative + Math.round(loc.y() * 1000) / 1000D;
            case 2 -> relative + Math.round(loc.z() * 1000) / 1000D;
            default -> 0;
        };
    }
}
