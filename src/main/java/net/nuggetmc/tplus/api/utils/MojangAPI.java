package net.nuggetmc.tplus.api.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks up signed skin textures for a player name. This does blocking HTTP calls, so call it off the server thread.
 */
public class MojangAPI {

    private static final boolean CACHE_ENABLED = false;

    private static final Map<String, String[]> CACHE = new ConcurrentHashMap<>();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String[] PROFILE_LOOKUPS = {
            "https://api.mojang.com/users/profiles/minecraft/",
            "https://api.minecraftservices.com/minecraft/profile/lookup/name/"
    };

    /**
     * @return {@code {value, signature}} of the "textures" property, or {@code null} if the name has no skin.
     */
    @Nullable
    public static String[] getSkin(String name) {
        // -Dterminatorplus.fetchSkins=false: never call the Mojang API (offline servers, tests); bots get default skins
        if (!Boolean.parseBoolean(System.getProperty("terminatorplus.fetchSkins", "true"))) {
            return null;
        }

        if (CACHE_ENABLED && CACHE.containsKey(name)) {
            return CACHE.get(name);
        }

        String[] values = pullFromAPI(name);
        if (values != null) {
            CACHE.put(name, values);
        }
        return values;
    }

    @Nullable
    public static String[] pullFromAPI(String name) {
        try {
            String uuid = lookupUUID(name);

            if (uuid == null) {
                return null;
            }

            JsonElement profile = get("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false");

            if (profile == null) {
                return null;
            }

            JsonObject property = profile.getAsJsonObject().get("properties").getAsJsonArray().get(0).getAsJsonObject();
            return new String[]{property.get("value").getAsString(), property.get("signature").getAsString()};
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String lookupUUID(String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);

        for (String endpoint : PROFILE_LOOKUPS) {
            try {
                JsonElement json = get(endpoint + encoded);

                if (json != null && json.isJsonObject() && json.getAsJsonObject().has("id")) {
                    return json.getAsJsonObject().get("id").getAsString();
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    @Nullable
    private static JsonElement get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 || response.body().isBlank()) {
            return null;
        }

        return JsonParser.parseString(response.body());
    }
}
