package com.lunatech.tpcore.module.home.hook.impl;

import com.lunatech.tpcore.module.home.hook.HomeNameForm;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.function.Consumer;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.response.CustomFormResponse;
import org.geysermc.floodgate.api.FloodgateApi;

public final class FloodgateHomeNameForm implements HomeNameForm {
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
            return new Reply(Outcome.SUBMITTED, response.asInput());
        } catch (IllegalStateException malformed) {
            return new Reply(Outcome.MALFORMED, null);
        }
    }

    private static void deliver(WeakReference<Consumer<Reply>> receiver, Reply reply) {
        var callback = receiver.get();
        if (callback != null) callback.accept(reply);
    }
}
