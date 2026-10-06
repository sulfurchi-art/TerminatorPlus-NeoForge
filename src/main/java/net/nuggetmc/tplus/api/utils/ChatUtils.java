package net.nuggetmc.tplus.api.utils;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Messages are still written with legacy formatting codes (like the original plugin did with ChatColor) and turned into
 * proper styled components right before they are sent, so the console log stays free of section signs.
 */
public class ChatUtils {
    public static final String LINE = ChatFormatting.GRAY + "------------------------------------------------";
    public static final String BULLET = "▪";
    public static final String BULLET_FORMATTED = ChatFormatting.GRAY + " ▪ " + ChatFormatting.RESET;
    public static final String EXCEPTION_MESSAGE = ChatFormatting.RED + "An exception has occured. Please try again.";

    public static final NumberFormat NUMBER_FORMAT = NumberFormat.getNumberInstance(Locale.US);

    public static final String ON = ChatFormatting.GREEN.toString();
    public static final String OFF = ChatFormatting.GRAY.toString();

    public static String trim16(String str) {
        return str.length() > 16 ? str.substring(0, 16) : str;
    }

    public static String camelToDashed(String input) {
        StringBuilder result = new StringBuilder();

        for (char ch : input.toCharArray()) {
            if (Character.isUpperCase(ch)) {
                result.append("-").append(Character.toLowerCase(ch));
            } else {
                result.append(ch);
            }
        }

        return result.toString();
    }

    /**
     * Converts a string containing legacy {@code §} formatting codes into a styled component.
     */
    public static MutableComponent legacy(String text) {
        MutableComponent root = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder buffer = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == ChatFormatting.PREFIX_CODE && i + 1 < text.length()) {
                ChatFormatting formatting = ChatFormatting.getByCode(text.charAt(i + 1));

                if (formatting != null) {
                    if (!buffer.isEmpty()) {
                        root.append(Component.literal(buffer.toString()).setStyle(style));
                        buffer.setLength(0);
                    }

                    if (formatting == ChatFormatting.RESET) {
                        style = Style.EMPTY;
                    } else if (formatting.isColor()) {
                        // Like the vanilla client: a colour code also resets bold/italic/etc.
                        style = Style.EMPTY.withColor(formatting);
                    } else {
                        style = style.applyLegacyFormat(formatting);
                    }

                    i++;
                    continue;
                }
            }

            buffer.append(c);
        }

        if (!buffer.isEmpty()) {
            root.append(Component.literal(buffer.toString()).setStyle(style));
        }

        return root;
    }

    public static String stripColor(String text) {
        String stripped = ChatFormatting.stripFormatting(text);
        return stripped == null ? "" : stripped;
    }

    public static void send(CommandSourceStack source, String message) {
        source.sendSystemMessage(legacy(message));
    }
}
