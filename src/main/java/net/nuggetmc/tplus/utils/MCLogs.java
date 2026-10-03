package net.nuggetmc.tplus.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion;
import net.nuggetmc.tplus.TerminatorPlus;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Collectors;

/**
 * Used for debug logs.
 */
public class MCLogs {

    private static final String FORMAT =
            """
                    ====== TERMINATOR PLUS DEBUG INFO ======
                    Mod Version: %s
                    Minecraft Version: %s
                    Server Software: %s
                    Dedicated Server: %s
                    Mods: %s
                    Server TPS: %s (%s mspt)
                    Memory: %s/%s
                    Bots: %s
                    ====== TERMINATOR PLUS DEBUG INFO ======
                    """;

    /**
     * Must be called on the server thread.
     */
    public static String collectInfo(MinecraftServer server) {
        String modVersion = TerminatorPlus.getVersion();
        String mcVersion = SharedConstants.getCurrentVersion().getName();
        String software = server.getServerModName() + " " + NeoForgeVersion.getVersion();
        String mods = ModList.get().getMods().stream()
                .map(mod -> mod.getModId() + " v" + mod.getVersion())
                .collect(Collectors.joining(", "));
        double mspt = server.getAverageTickTimeNanos() / 1_000_000D;
        String tps = String.format("%.2f", Math.min(20, 1000 / Math.max(mspt, 1e-9)));
        String usedMemory = String.format("%.2f", (double) (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024) + "MB";
        String maxMemory = String.format("%.2f", (double) Runtime.getRuntime().maxMemory() / 1024 / 1024) + "MB";
        int bots = TerminatorPlus.getManager() == null ? 0 : TerminatorPlus.getManager().fetch().size();

        return String.format(FORMAT, modVersion, mcVersion, software, server.isDedicatedServer(), mods, tps,
                String.format("%.2f", mspt), usedMemory, maxMemory, bots);
    }

    /**
     * Uploads the text to mclo.gs and returns the URL. Blocking, call it off the server thread.
     */
    public static String pasteText(String text) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.mclo.gs/1/log"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("content=" + URLEncoder.encode(text, StandardCharsets.UTF_8)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

        if (!json.has("url")) {
            throw new IOException(json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode());
        }

        return json.get("url").getAsString();
    }
}
