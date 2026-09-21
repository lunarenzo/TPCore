package com.lunatech.tpcore.module.tpa.gui.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationMenuService;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import com.lunatech.tpcore.platform.ServerVersion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Supplier;

public final class PaperDialogConfirmationService implements TpaConfirmationMenuService {

    private final Supplier<TpaConfig> configSupplier;
    private final Supplier<TpaService> serviceSupplier;
    private final ChestGuiConfirmationService fallbackChestGui;
    private final Logger logger;
    private boolean loggedNotice = false;

    public PaperDialogConfirmationService(Supplier<TpaConfig> configSupplier, Logger logger) {
        this(configSupplier, () -> null, logger);
    }

    public PaperDialogConfirmationService(Supplier<TpaConfig> configSupplier, Supplier<TpaService> serviceSupplier, Logger logger) {
        this.configSupplier = configSupplier;
        this.serviceSupplier = serviceSupplier;
        this.fallbackChestGui = new ChestGuiConfirmationService(configSupplier);
        this.logger = logger;
    }

    @Override
    public void openAcceptConfirmation(Player target, TpaRequest request) {
        if (target == null || !target.isOnline() || request == null) {
            return;
        }

        Player senderPlayer = Bukkit.getPlayer(request.senderId());
        String senderName = (senderPlayer != null) ? senderPlayer.getName() : "Player";

        if (ServerVersion.IS_DIALOG_SUPPORTED) {
            TpaConfig config = this.configSupplier.get();
            String body = (request.type() == TpaType.TPA_HERE)
                ? config.dialogAcceptTpahereBodyText()
                : config.dialogAcceptTpaBodyText();
            if (body == null || body.isBlank()) {
                body = config.dialogBodyText();
            }
            if (body == null || body.isBlank()) {
                body = "<yellow><sender></yellow> <gray>sent a teleport request.\nDo you accept?</gray>";
            }
            body = body.replace("<sender>", senderName).replace("<target>", target.getName());

            String acceptText = (config.dialogAcceptText() != null && !config.dialogAcceptText().isBlank())
                ? config.dialogAcceptText()
                : "<green><bold>ACCEPT</bold></green>";
            String denyText = (config.dialogDenyText() != null && !config.dialogDenyText().isBlank())
                ? config.dialogDenyText()
                : "<red><bold>DENY</bold></red>";

            boolean success = this.tryShowDialog(
                target,
                config.dialogTitle(),
                body,
                acceptText,
                denyText,
                "tpcore:tpa_accept",
                "tpcore:tpa_deny"
            );
            if (success) {
                return;
            }
        }

        this.logFallbackNoticeOnce();
        this.fallbackChestGui.openAcceptConfirmation(target, request);
    }

    @Override
    public void openSendConfirmation(Player sender, Player target, TpaType type) {
        if (sender == null || !sender.isOnline() || target == null) {
            return;
        }

        if (ServerVersion.IS_DIALOG_SUPPORTED) {
            TpaConfig config = this.configSupplier.get();
            String body = (type == TpaType.TPA_HERE)
                ? config.dialogSendTpahereBodyText()
                : config.dialogSendTpaBodyText();
            if (body == null || body.isBlank()) {
                body = "<gray>Send a teleport request to </gray><yellow><target></yellow>?";
            }
            body = body.replace("<sender>", sender.getName()).replace("<target>", target.getName());

            String confirmText = (config.dialogSendConfirmText() != null && !config.dialogSendConfirmText().isBlank())
                ? config.dialogSendConfirmText()
                : "<green><bold>CONFIRM & SEND</bold></green>";
            String cancelText = (config.dialogSendCancelText() != null && !config.dialogSendCancelText().isBlank())
                ? config.dialogSendCancelText()
                : "<red><bold>CANCEL</bold></red>";

            String acceptKey = "tpcore:tpa_send_confirm:" + target.getUniqueId() + ":" + type.name();
            String denyKey = "tpcore:tpa_send_cancel";

            boolean success = this.tryShowDialog(
                sender,
                config.dialogTitle(),
                body,
                confirmText,
                cancelText,
                acceptKey,
                denyKey
            );
            if (success) {
                return;
            }
        }

        this.logFallbackNoticeOnce();
        this.fallbackChestGui.openSendConfirmation(sender, target, type);
    }

    private boolean tryShowDialog(Player player, String titleText, String bodyText, String acceptText, String denyText, String acceptKey, String denyKey) {
        try {
            Class<?> dialogClass = Class.forName("io.papermc.paper.dialog.Dialog");

            Component titleComp = MiniMessage.miniMessage().deserialize(titleText);
            Component bodyComp = MiniMessage.miniMessage().deserialize(bodyText);
            Component acceptComp = MiniMessage.miniMessage().deserialize(acceptText);
            Component denyComp = MiniMessage.miniMessage().deserialize(denyText);

            ClassLoader cl = dialogClass.getClassLoader();

            // DialogBase
            Class<?> dialogBaseClass = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase", true, cl);
            Object baseBuilder = dialogBaseClass.getMethod("builder", Component.class).invoke(null, titleComp);
            baseBuilder.getClass().getMethod("canCloseWithEscape", boolean.class).invoke(baseBuilder, true);

            Class<?> dialogBodyClass = Class.forName("io.papermc.paper.registry.data.dialog.body.DialogBody", true, cl);
            Object bodyItem = dialogBodyClass.getMethod("plainMessage", Component.class).invoke(null, bodyComp);

            baseBuilder.getClass().getMethod("body", List.class).invoke(baseBuilder, List.of(bodyItem));
            Object dialogBase = baseBuilder.getClass().getMethod("build").invoke(baseBuilder);

            // ActionButtons
            Class<?> actionButtonClass = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton", true, cl);

            // Accept button
            Object acceptBuilder = actionButtonClass.getMethod("builder", Component.class).invoke(null, acceptComp);
            Object acceptAction = createCustomClickAction(cl, acceptKey);
            if (acceptAction != null) {
                Method actionMethod = findMethod(acceptBuilder.getClass(), "action");
                if (actionMethod != null) {
                    actionMethod.invoke(acceptBuilder, acceptAction);
                }
            }
            Object acceptButton = acceptBuilder.getClass().getMethod("build").invoke(acceptBuilder);

            // Deny button
            Object denyBuilder = actionButtonClass.getMethod("builder", Component.class).invoke(null, denyComp);
            Object denyAction = createCustomClickAction(cl, denyKey);
            if (denyAction != null) {
                Method actionMethod = findMethod(denyBuilder.getClass(), "action");
                if (actionMethod != null) {
                    actionMethod.invoke(denyBuilder, denyAction);
                }
            }
            Object denyButton = denyBuilder.getClass().getMethod("build").invoke(denyBuilder);

            // DialogType
            Class<?> dialogTypeClass = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType", true, cl);
            Method confirmationMethod = findMethod(dialogTypeClass, "confirmation");
            Object dialogType = confirmationMethod.invoke(null, acceptButton, denyButton);

            // Build Dialog via Dialog.create(consumer)
            Class<?> consumerClass = Class.forName("java.util.function.Consumer");
            Object consumerProxy = Proxy.newProxyInstance(cl, new Class<?>[]{consumerClass}, (proxy, method, args) -> {
                if ("accept".equals(method.getName()) && args.length == 1) {
                    Object builder = args[0];
                    Object emptyBuilder = builder.getClass().getMethod("empty").invoke(builder);
                    Method setBase = findMethod(emptyBuilder.getClass(), "base");
                    setBase.invoke(emptyBuilder, dialogBase);
                    Method setType = findMethod(emptyBuilder.getClass(), "type");
                    setType.invoke(emptyBuilder, dialogType);
                }
                return null;
            });

            Method createMethod = dialogClass.getMethod("create", consumerClass);
            Object dialogInstance = createMethod.invoke(null, consumerProxy);

            // Show dialog to player
            Method showDialogMethod = findMethod(player.getClass(), "showDialog");
            if (showDialogMethod != null && dialogInstance != null) {
                showDialogMethod.invoke(player, dialogInstance);
                return true;
            }
        } catch (Throwable t) {
            this.logger.debug("Failed to invoke Paper Dialog API via reflection, falling back to Chest GUI", t);
        }
        return false;
    }

    private Object createCustomClickAction(ClassLoader cl, String keyString) {
        try {
            Class<?> keyClass = Class.forName("net.kyori.adventure.key.Key", true, cl);
            Object key = keyClass.getMethod("key", String.class).invoke(null, keyString);

            Class<?> dialogActionClass = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction", true, cl);
            for (Method m : dialogActionClass.getMethods()) {
                if (m.getName().equals("customClick")) {
                    Class<?>[] paramTypes = m.getParameterTypes();
                    if (paramTypes.length > 0 && paramTypes[0].isAssignableFrom(keyClass)) {
                        m.setAccessible(true);
                        if (paramTypes.length == 1) {
                            return m.invoke(null, key);
                        } else if (paramTypes.length == 2) {
                            return m.invoke(null, new Object[]{ key, null });
                        }
                    }
                }
            }
        } catch (Throwable t) {
            this.logger.error("Failed to create DialogAction customClick for key {}", keyString, t);
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        return null;
    }

    private void logFallbackNoticeOnce() {
        if (!this.loggedNotice) {
            this.loggedNotice = true;
            this.logger.info("Paper Dialog API requires Paper 1.21.6+ (API 1.21.7+). Falling back to Chest GUI confirmation menu.");
        }
    }
}
