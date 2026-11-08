package com.lunatech.tpcore.module.home.hook;

import java.util.UUID;
import java.util.function.Consumer;

public interface HomeNameForm {
    HomeNameForm UNAVAILABLE = new HomeNameForm() {
        @Override public boolean supports(UUID player) { return false; }
        @Override public boolean send(UUID player, Prompt prompt, Consumer<Reply> reply) { return false; }
    };

    boolean supports(UUID player);
    boolean send(UUID player, Prompt prompt, Consumer<Reply> reply);

    record Prompt(String title, String description, String label, String placeholder, String value) {}
    enum Outcome { SUBMITTED, CLOSED, MALFORMED }
    record Reply(Outcome outcome, String name) {}
}
