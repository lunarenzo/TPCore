package com.lunatech.tpcore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MessageFormatterTest {

    @Test
    @DisplayName("Should return empty string when input is null or empty")
    void testNullAndEmptyInput() {
        assertEquals("", MessageFormatter.toMiniMessage(null));
        assertEquals("", MessageFormatter.toMiniMessage(""));
    }

    @Test
    @DisplayName("Should return unchanged input on fast path when no ampersand or section symbol is present")
    void testFastPathNoLegacyCodes() {
        String pureMiniMessage = "<gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray>";
        assertEquals(pureMiniMessage, MessageFormatter.toMiniMessage(pureMiniMessage));
    }

    @Test
    @DisplayName("Should convert standard ampersand and section color codes to MiniMessage tags")
    void testStandardLegacyColorCodes() {
        assertEquals("<red>Red <green>Green <gold>Gold", MessageFormatter.toMiniMessage("&cRed &aGreen &6Gold"));
        assertEquals("<red>Red <green>Green <gold>Gold", MessageFormatter.toMiniMessage("§cRed §aGreen §6Gold"));
    }

    @Test
    @DisplayName("Should convert legacy formatting codes to MiniMessage tags")
    void testStandardLegacyFormattingCodes() {
        assertEquals("<bold>Bold <italic>Italic <reset>Reset", MessageFormatter.toMiniMessage("&lBold &oItalic &rReset"));
        assertEquals("<bold>Bold <italic>Italic <reset>Reset", MessageFormatter.toMiniMessage("§lBold §oItalic §rReset"));
    }

    @Test
    @DisplayName("Should convert Essentials/Bungee hex color format &#RRGGBB")
    void testEssentialsHexFormat() {
        assertEquals("<color:#FF0000>Red Text", MessageFormatter.toMiniMessage("&#FF0000Red Text"));
        assertEquals("<color:#00FF00>Green Text", MessageFormatter.toMiniMessage("§#00FF00Green Text"));
    }

    @Test
    @DisplayName("Should convert Spigot legacy hex color format &x&R&R&G&G&B&B")
    void testSpigotHexFormat() {
        assertEquals("<color:#ff0000>Red Text", MessageFormatter.toMiniMessage("&x&f&f&0&0&0&0Red Text"));
        assertEquals("<color:#00ff00>Green Text", MessageFormatter.toMiniMessage("§x§0§0§f§f§0§0Green Text"));
    }

    @Test
    @DisplayName("Should convert legacy gradient syntax &{#RRGGBB:#RRGGBB}")
    void testLegacyGradientFormat() {
        assertEquals("<gradient:#FF0000:#00FF00>Gradient Text", MessageFormatter.toMiniMessage("&{#FF0000:#00FF00}Gradient Text"));
    }

    @Test
    @DisplayName("Should cleanly parse mixed legacy and MiniMessage formatting into Adventure Component")
    void testParseWithResolvers() {
        MiniMessage mm = MiniMessage.miniMessage();
        String input = "&cHello <yellow><player></yellow> &#00FF00Welcome!";
        Component component = MessageFormatter.parse(
            mm,
            input,
            Placeholder.unparsed("player", "Steve")
        );
        assertNotNull(component);
    }
}
