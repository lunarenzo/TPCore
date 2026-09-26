package com.lunatech.tpcore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageFormatter {

    private static final Pattern GRADIENT_PATTERN = Pattern.compile("[&§]\\{#([0-9a-fA-F]{6}):#([0-9a-fA-F]{6})\\}");
    private static final Pattern SPIGOT_HEX_PATTERN = Pattern.compile("[&§]x[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])");
    private static final Pattern ESSENTIALS_HEX_PATTERN = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    private static final Pattern LEGACY_CODE_PATTERN = Pattern.compile("[&§]([0-9a-fA-Fk-orK-OR])");

    private MessageFormatter() {}

    public static String toMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        if (input.indexOf('&') == -1 && input.indexOf('§') == -1) {
            return input;
        }

        String result = input;

        // 1. Legacy Gradient conversion (&{#RRGGBB:#RRGGBB} -> <gradient:#RRGGBB:#RRGGBB>)
        Matcher gradientMatcher = GRADIENT_PATTERN.matcher(result);
        if (gradientMatcher.find()) {
            result = gradientMatcher.replaceAll("<gradient:#$1:#$2>");
        }

        // 2. Spigot Legacy Hex conversion (&x&f&f&0&0&0&0 -> <color:#ff0000>)
        Matcher spigotHexMatcher = SPIGOT_HEX_PATTERN.matcher(result);
        if (spigotHexMatcher.find()) {
            result = spigotHexMatcher.replaceAll("<color:#$1$2$3$4$5$6>");
        }

        // 3. Bungee / Essentials Hex conversion (&#RRGGBB -> <color:#RRGGBB>)
        Matcher essentialsHexMatcher = ESSENTIALS_HEX_PATTERN.matcher(result);
        if (essentialsHexMatcher.find()) {
            result = essentialsHexMatcher.replaceAll("<color:#$1>");
        }

        // 4. Legacy 16-color and format code conversion (&c -> <red>, &l -> <bold>, etc.)
        Matcher legacyMatcher = LEGACY_CODE_PATTERN.matcher(result);
        if (legacyMatcher.find()) {
            StringBuilder sb = new StringBuilder();
            do {
                char code = Character.toLowerCase(legacyMatcher.group(1).charAt(0));
                String replacement = switch (code) {
                    case '0' -> "<black>";
                    case '1' -> "<dark_blue>";
                    case '2' -> "<dark_green>";
                    case '3' -> "<dark_aqua>";
                    case '4' -> "<dark_red>";
                    case '5' -> "<dark_purple>";
                    case '6' -> "<gold>";
                    case '7' -> "<gray>";
                    case '8' -> "<dark_gray>";
                    case '9' -> "<blue>";
                    case 'a' -> "<green>";
                    case 'b' -> "<aqua>";
                    case 'c' -> "<red>";
                    case 'd' -> "<light_purple>";
                    case 'e' -> "<yellow>";
                    case 'f' -> "<white>";
                    case 'k' -> "<obfuscated>";
                    case 'l' -> "<bold>";
                    case 'm' -> "<strikethrough>";
                    case 'n' -> "<underlined>";
                    case 'o' -> "<italic>";
                    case 'r' -> "<reset>";
                    default -> legacyMatcher.group(0);
                };
                legacyMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } while (legacyMatcher.find());
            legacyMatcher.appendTail(sb);
            result = sb.toString();
        }

        return result;
    }

    public static Component parse(MiniMessage miniMessage, String rawTemplate, TagResolver... resolvers) {
        if (rawTemplate == null || rawTemplate.isEmpty()) {
            return Component.empty();
        }
        String normalized = toMiniMessage(rawTemplate);
        if (resolvers == null || resolvers.length == 0) {
            return miniMessage.deserialize(normalized);
        }
        return miniMessage.deserialize(normalized, TagResolver.resolver(resolvers));
    }
}
