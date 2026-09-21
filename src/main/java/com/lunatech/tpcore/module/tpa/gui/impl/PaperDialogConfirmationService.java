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

            String acceptKey = "tpcore:tpa_send_confirm/" + target.getUniqueId().toString().toLowerCase() + "/" + type.name().toLowerCase();
            String denyKey = null;

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
        if (!DialogReflectionCache.SUPPORTED) {
            return false;
        }

        try {
            Component titleComp = MiniMessage.miniMessage().deserialize(titleText);
            Component bodyComp = MiniMessage.miniMessage().deserialize(bodyText);
            Component acceptComp = MiniMessage.miniMessage().deserialize(acceptText);
            Component denyComp = MiniMessage.miniMessage().deserialize(denyText);

            // DialogBase
            Object baseBuilder = DialogReflectionCache.DIALOG_BASE_BUILDER.invoke(null, titleComp);
            Method setCanClose = findMethod(baseBuilder.getClass(), "canCloseWithEscape", boolean.class);
            if (setCanClose != null) {
                setCanClose.invoke(baseBuilder, true);
            }

            Object bodyItem = DialogReflectionCache.DIALOG_BODY_PLAIN.invoke(null, bodyComp);
            Method setBody = findMethod(baseBuilder.getClass(), "body", List.class);
            if (setBody != null) {
                setBody.invoke(baseBuilder, List.of(bodyItem));
            }
            Object dialogBase = baseBuilder.getClass().getMethod("build").invoke(baseBuilder);

            // Accept button
            Object acceptBuilder = DialogReflectionCache.ACTION_BUTTON_BUILDER.invoke(null, acceptComp);
            if (acceptKey != null) {
                Object acceptAction = createCustomClickAction(acceptKey);
                if (acceptAction != null) {
                    Method actionMethod = findMethod(acceptBuilder.getClass(), "action", acceptAction.getClass());
                    if (actionMethod != null) {
                        actionMethod.invoke(acceptBuilder, acceptAction);
                    }
                }
            }
            Object acceptButton = acceptBuilder.getClass().getMethod("build").invoke(acceptBuilder);

            // Deny/Cancel button
            Object denyBuilder = DialogReflectionCache.ACTION_BUTTON_BUILDER.invoke(null, denyComp);
            if (denyKey != null) {
                Object denyAction = createCustomClickAction(denyKey);
                if (denyAction != null) {
                    Method actionMethod = findMethod(denyBuilder.getClass(), "action", denyAction.getClass());
                    if (actionMethod != null) {
                        actionMethod.invoke(denyBuilder, denyAction);
                    }
                }
            }
            Object denyButton = denyBuilder.getClass().getMethod("build").invoke(denyBuilder);

            // DialogType
            Object dialogType = DialogReflectionCache.DIALOG_TYPE_CONFIRMATION.invoke(null, acceptButton, denyButton);

            // Build Dialog via Dialog.create(consumer)
            Class<?> consumerClass = Class.forName("java.util.function.Consumer");
            Object consumerProxy = Proxy.newProxyInstance(player.getClass().getClassLoader(), new Class<?>[]{consumerClass}, (proxy, method, args) -> {
                if ("accept".equals(method.getName()) && args.length == 1) {
                    Object builder = args[0];
                    Object emptyBuilder = builder.getClass().getMethod("empty").invoke(builder);
                    Method setBase = findMethod(emptyBuilder.getClass(), "base", dialogBase.getClass());
                    if (setBase != null) {
                        setBase.invoke(emptyBuilder, dialogBase);
                    }
                    Method setType = findMethod(emptyBuilder.getClass(), "type", dialogType.getClass());
                    if (setType != null) {
                        setType.invoke(emptyBuilder, dialogType);
                    }
                }
                return null;
            });

            Object dialogInstance = DialogReflectionCache.DIALOG_CREATE.invoke(null, consumerProxy);

            // Show dialog to player
            if (DialogReflectionCache.SHOW_DIALOG != null && dialogInstance != null) {
                DialogReflectionCache.SHOW_DIALOG.invoke(player, dialogInstance);
                return true;
            }
        } catch (Throwable t) {
            this.logger.debug("Failed to invoke Paper Dialog API via cached reflection, falling back to Chest GUI", t);
        }
        return false;
    }

    private Object createCustomClickAction(String keyString) {
        try {
            if (DialogReflectionCache.KEY_FACTORY == null) {
                return null;
            }
            Object key = DialogReflectionCache.KEY_FACTORY.invoke(null, keyString);

            if (DialogReflectionCache.CUSTOM_CLICK_1 != null) {
                return DialogReflectionCache.CUSTOM_CLICK_1.invoke(null, key);
            } else if (DialogReflectionCache.CUSTOM_CLICK_2 != null) {
                return DialogReflectionCache.CUSTOM_CLICK_2.invoke(null, new Object[]{ key, null });
            }
        } catch (Throwable t) {
            this.logger.error("Failed to create DialogAction customClick for key {}", keyString, t);
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name)) {
                if (paramTypes.length == 0 || m.getParameterCount() == paramTypes.length) {
                    m.setAccessible(true);
                    return m;
                }
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

    private static final class DialogReflectionCache {
        private static final boolean SUPPORTED;
        private static final Method DIALOG_BASE_BUILDER;
        private static final Method DIALOG_BODY_PLAIN;
        private static final Method ACTION_BUTTON_BUILDER;
        private static final Method DIALOG_TYPE_CONFIRMATION;
        private static final Method DIALOG_CREATE;
        private static final Method SHOW_DIALOG;
        private static final Method CUSTOM_CLICK_1;
        private static final Method CUSTOM_CLICK_2;
        private static final Method KEY_FACTORY;

        static {
            boolean supp = false;
            Method dbBuilder = null;
            Method dBodyPlain = null;
            Method abBuilder = null;
            Method dtConfirmation = null;
            Method dCreate = null;
            Method sDialog = null;
            Method cClick1 = null;
            Method cClick2 = null;
            Method kFactory = null;

            try {
                Class<?> dClass = Class.forName("io.papermc.paper.dialog.Dialog");
                Class<?> kClass = Class.forName("net.kyori.adventure.key.Key");
                kFactory = kClass.getMethod("key", String.class);
                kFactory.setAccessible(true);

                Class<?> dbClass = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase");
                dbBuilder = findMethodWithParam(dbClass, "builder", Component.class);

                Class<?> dBodyClass = Class.forName("io.papermc.paper.registry.data.dialog.body.DialogBody");
                dBodyPlain = findMethodWithParam(dBodyClass, "plainMessage", Component.class);

                Class<?> abClass = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton");
                abBuilder = findMethodWithParam(abClass, "builder", Component.class);

                Class<?> dtClass = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
                dtConfirmation = findMethodWithParam(dtClass, "confirmation", abClass, abClass);

                Class<?> consumerClass = Class.forName("java.util.function.Consumer");
                dCreate = findMethodWithParam(dClass, "create", consumerClass);

                sDialog = findMethodWithParam(Player.class, "showDialog", dClass);

                Class<?> daClass = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction");
                for (Method m : daClass.getMethods()) {
                    if (m.getName().equals("customClick")) {
                        Class<?>[] pTypes = m.getParameterTypes();
                        if (pTypes.length == 1 && pTypes[0].isAssignableFrom(kClass)) {
                            cClick1 = m;
                            cClick1.setAccessible(true);
                        } else if (pTypes.length == 2 && pTypes[0].isAssignableFrom(kClass)) {
                            cClick2 = m;
                            cClick2.setAccessible(true);
                        }
                    }
                }

                supp = (dbBuilder != null && dBodyPlain != null && abBuilder != null && 
                        dtConfirmation != null && dCreate != null && sDialog != null);
            } catch (Throwable ignored) {
                supp = false;
            }

            SUPPORTED = supp;
            DIALOG_BASE_BUILDER = dbBuilder;
            DIALOG_BODY_PLAIN = dBodyPlain;
            ACTION_BUTTON_BUILDER = abBuilder;
            DIALOG_TYPE_CONFIRMATION = dtConfirmation;
            DIALOG_CREATE = dCreate;
            SHOW_DIALOG = sDialog;
            CUSTOM_CLICK_1 = cClick1;
            CUSTOM_CLICK_2 = cClick2;
            KEY_FACTORY = kFactory;
        }

        private static Method findMethodWithParam(Class<?> clazz, String name, Class<?>... paramTypes) {
            try {
                for (Method m : clazz.getMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == paramTypes.length) {
                        boolean match = true;
                        Class<?>[] types = m.getParameterTypes();
                        for (int i = 0; i < types.length; i++) {
                            if (!types[i].isAssignableFrom(paramTypes[i]) && !paramTypes[i].isAssignableFrom(types[i])) {
                                match = false;
                                break;
                            }
                        }
                        if (match) {
                            m.setAccessible(true);
                            return m;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}
