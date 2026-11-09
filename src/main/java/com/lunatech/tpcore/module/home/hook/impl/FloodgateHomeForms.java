package com.lunatech.tpcore.module.home.hook.impl;

import com.lunatech.tpcore.module.home.hook.HomeForms;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.function.Consumer;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.response.CustomFormResponse;
import org.geysermc.floodgate.api.FloodgateApi;

public final class FloodgateHomeForms implements HomeForms {
    @Override public boolean supports(UUID player) {
        var api = FloodgateApi.getInstance();
        return api != null && api.isFloodgatePlayer(player);
    }

    @Override public boolean send(UUID player, Prompt prompt, Consumer<Reply> reply) {
        var api = FloodgateApi.getInstance();
        if (api == null) return false;
        // The transport may retain the form after Home invalidates its session.
        var receiver = new WeakReference<>(reply);
        return api.sendForm(player, CustomForm.builder()
            .title(prompt.title())
            .label(prompt.description())
            .input(prompt.label(), prompt.placeholder(), prompt.value())
            .closedResultHandler(() -> deliver(receiver, new Reply(Outcome.CLOSED, null)))
            .invalidResultHandler(() -> deliver(receiver, new Reply(Outcome.MALFORMED, null)))
            .validResultHandler(response -> deliver(receiver, submitted(response))));
    }

    private static Reply submitted(CustomFormResponse response) {
        try {
            String input = response.asInput();
            // Bound the data retained in the entity scheduler handoff.
            return input != null && input.length() > 32 ? new Reply(Outcome.MALFORMED, null)
                : new Reply(Outcome.SUBMITTED, input);
        } catch (IllegalStateException malformed) {
            return new Reply(Outcome.MALFORMED, null);
        }
    }

    @Override public boolean sendMenu(UUID player, Menu menu, Consumer<Integer> selection) {
        var api = FloodgateApi.getInstance();
        if (api == null) return false;
        var receiver = new WeakReference<>(selection);
        var builder = SimpleForm.builder().title(menu.title()).content(menu.description())
            .closedOrInvalidResultHandler(() -> deliver(receiver, -1))
            .validResultHandler(response -> deliver(receiver, response.clickedButtonId()));
        menu.buttons().forEach(builder::button);
        return api.sendForm(player, builder);
    }

    private static <T> void deliver(WeakReference<Consumer<T>> receiver, T reply) {
        var callback = receiver.get();
        if (callback != null) callback.accept(reply);
    }
}
