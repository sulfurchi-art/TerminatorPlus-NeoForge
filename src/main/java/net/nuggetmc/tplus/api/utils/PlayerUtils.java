package net.nuggetmc.tplus.api.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyMats;

import javax.annotation.Nullable;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class PlayerUtils {

    private static final Set<String> USERNAME_CACHE = new HashSet<>();

    public static boolean isInvincible(@Nullable GameType mode) {
        return mode != GameType.SURVIVAL && mode != GameType.ADVENTURE && mode != null;
    }

    public static boolean isInvincible(Player player) {
        return player instanceof ServerPlayer serverPlayer && isInvincible(serverPlayer.gameMode.getGameModeForPlayer());
    }

    public static String randomName(MinecraftServer server) {
        if (USERNAME_CACHE.isEmpty()) {
            fillUsernameCache(server);
        }

        return MathUtils.getRandomSetElement(USERNAME_CACHE);
    }

    public static void fillUsernameCache(MinecraftServer server) {
        Path file = server.getServerDirectory().resolve("usercache.json");

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();

            for (JsonElement element : array) {
                USERNAME_CACHE.add(element.getAsJsonObject().get("name").getAsString());
            }
        } catch (Exception e) {
            DebugLogUtils.log("Failed to fetch from the usercache.");
        }
    }

    public static Location findAbove(Location loc, int amount) {
        boolean check = false;

        for (int i = 0; i <= amount; i++) {
            if (LegacyMats.isSolid(loc.level().getBlockState(loc.add(0, i, 0).blockPos()).getBlock())) {
                check = true;
                break;
            }
        }

        if (check) {
            return loc;
        } else {
            return loc.add(0, amount, 0);
        }
    }

    public static Location findBottom(Location loc) {
        loc = loc.withY(Math.floor(loc.y()));
        int minY = loc.level().getMinBuildHeight();

        for (int i = 0; i < 255; i++) {
            Location check = loc.add(0, -i, 0);

            if (check.y() <= minY) {
                break;
            }

            if (LegacyMats.isSolid(loc.level().getBlockState(check.blockPos()).getBlock())) {
                return check.add(0, 1, 0);
            }
        }

        return loc;
    }
}
