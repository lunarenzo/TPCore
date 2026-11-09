package com.lunatech.tpcore.module.home.hook;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public interface HomeForms {
    HomeForms UNAVAILABLE = new HomeForms() {
        @Override public boolean supports(UUID player) { return false; }
        @Override public boolean send(UUID player, Prompt prompt, Consumer<Reply> reply) { return false; }
        @Override public boolean sendMenu(UUID player, Menu menu, Consumer<Integer> selection) { return false; }
    };

    boolean supports(UUID player);
    boolean send(UUID player, Prompt prompt, Consumer<Reply> reply);
    boolean sendMenu(UUID player, Menu menu, Consumer<Integer> selection);

    record Prompt(String title, String description, String label, String placeholder, String value) {}
    record Menu(String title, String description, List<String> buttons) {
        public Menu { buttons = List.copyOf(buttons); }
    }
    enum Outcome { SUBMITTED, CLOSED, MALFORMED }
    record Reply(Outcome outcome, String name) {}
}
