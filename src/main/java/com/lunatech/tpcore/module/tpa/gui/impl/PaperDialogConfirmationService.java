package com.lunatech.tpcore.module.tpa.gui.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationMenuService;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import com.lunatech.tpcore.platform.ServerVersion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
            body = body.replace("<sender>", senderName).replace("<target>", target.getName());

            String acceptText = (config.dialogAcceptText() != null && !config.dialogAcceptText().isBlank())
                ? config.dialogAcceptText()
                : "<green><bold>ACCEPT</bold></green>";
            String denyText = (config.dialogDenyText() != null && !config.dialogDenyText().isBlank())
                ? config.dialogDenyText()
                : "<red><bold>DENY</bold></red>";

            String acceptKey = "tpcore:tpa_accept/" + request.senderId().toString();
            String denyKey = "tpcore:tpa_deny/" + request.senderId().toString();

            boolean success = this.tryShowDialog(
                target,
                config.dialogTitle(),
                body,
                acceptText,
                denyText,
                acceptKey,
                denyKey,
                senderName,
                target.getName()
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
                body = config.dialogBodyText();
            }

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
                denyKey,
                sender.getName(),
                target.getName()
            );
            if (success) {
                return;
            }
        }

        this.logFallbackNoticeOnce();
        this.fallbackChestGui.openSendConfirmation(sender, target, type);
    }

    private boolean tryShowDialog(Player player, String titleText, String bodyText, String acceptText, String denyText, String acceptKey, String denyKey, String senderName, String targetName) {
        if (!DialogReflectionCache.SUPPORTED) {
            return false;
        }

        try {
            Component titleComp = MiniMessage.miniMessage().deserialize(titleText);
            TagResolver senderRes = Placeholder.unparsed("sender", senderName != null ? senderName : "");
            TagResolver targetRes = Placeholder.unparsed("target", targetName != null ? targetName : "");
            Component bodyComp = MiniMessage.miniMessage().deserialize(bodyText, TagResolver.resolver(senderRes, targetRes));
            Component acceptComp = MiniMessage.miniMessage().deserialize(acceptText);
            Component denyComp = MiniMessage.miniMessage().deserialize(denyText);

            // DialogBase
            Object baseBuilder = DialogReflectionCache.DIALOG_BASE_BUILDER.invoke(null, titleComp);
            if (DialogReflectionCache.DIALOG_BASE_CAN_CLOSE != null) {
                DialogReflectionCache.DIALOG_BASE_CAN_CLOSE.invoke(baseBuilder, true);
            }

            Object bodyItem = DialogReflectionCache.DIALOG_BODY_PLAIN.invoke(null, bodyComp);
            if (DialogReflectionCache.DIALOG_BASE_BODY != null) {
                DialogReflectionCache.DIALOG_BASE_BODY.invoke(baseBuilder, List.of(bodyItem));
            }
            Object dialogBase = DialogReflectionCache.DIALOG_BASE_BUILD != null
                ? DialogReflectionCache.DIALOG_BASE_BUILD.invoke(baseBuilder)
                : baseBuilder.getClass().getMethod("build").invoke(baseBuilder);

            // Accept button
            Object acceptBuilder = DialogReflectionCache.ACTION_BUTTON_BUILDER.invoke(null, acceptComp);
            if (acceptKey != null) {
                Object acceptAction = createCustomClickAction(acceptKey);
                if (acceptAction != null && DialogReflectionCache.ACTION_BUTTON_ACTION != null) {
                    DialogReflectionCache.ACTION_BUTTON_ACTION.invoke(acceptBuilder, acceptAction);
                }
            }
            Object acceptButton = DialogReflectionCache.ACTION_BUTTON_BUILD != null
                ? DialogReflectionCache.ACTION_BUTTON_BUILD.invoke(acceptBuilder)
                : acceptBuilder.getClass().getMethod("build").invoke(acceptBuilder);

            // Deny/Cancel button
            Object denyBuilder = DialogReflectionCache.ACTION_BUTTON_BUILDER.invoke(null, denyComp);
            if (denyKey != null) {
                Object denyAction = createCustomClickAction(denyKey);
                if (denyAction != null && DialogReflectionCache.ACTION_BUTTON_ACTION != null) {
                    DialogReflectionCache.ACTION_BUTTON_ACTION.invoke(denyBuilder, denyAction);
                }
            }
            Object denyButton = DialogReflectionCache.ACTION_BUTTON_BUILD != null
                ? DialogReflectionCache.ACTION_BUTTON_BUILD.invoke(denyBuilder)
                : denyBuilder.getClass().getMethod("build").invoke(denyBuilder);

            // DialogType
            Object dialogType = DialogReflectionCache.DIALOG_TYPE_CONFIRMATION.invoke(null, acceptButton, denyButton);

            // Build Dialog via Dialog.create(consumer)
            Object consumerProxy = Proxy.newProxyInstance(player.getClass().getClassLoader(), new Class<?>[]{ DialogReflectionCache.CONSUMER_CLASS }, (proxy, method, args) -> {
                if ("accept".equals(method.getName()) && args.length == 1) {
                    Object builder = args[0];
                    Object emptyBuilder = (DialogReflectionCache.DIALOG_BUILDER_EMPTY != null)
                        ? DialogReflectionCache.DIALOG_BUILDER_EMPTY.invoke(builder)
                        : builder;

                    if (DialogReflectionCache.DIALOG_BUILDER_BASE != null) {
                        DialogReflectionCache.DIALOG_BUILDER_BASE.invoke(emptyBuilder, dialogBase);
                    }
                    if (DialogReflectionCache.DIALOG_BUILDER_TYPE != null) {
                        DialogReflectionCache.DIALOG_BUILDER_TYPE.invoke(emptyBuilder, dialogType);
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
        private static final Class<?> CONSUMER_CLASS;
        private static final Method DIALOG_BASE_BUILDER;
        private static final Method DIALOG_BASE_CAN_CLOSE;
        private static final Method DIALOG_BASE_BODY;
        private static final Method DIALOG_BASE_BUILD;
        private static final Method DIALOG_BODY_PLAIN;
        private static final Method ACTION_BUTTON_BUILDER;
        private static final Method ACTION_BUTTON_ACTION;
        private static final Method ACTION_BUTTON_BUILD;
        private static final Method DIALOG_TYPE_CONFIRMATION;
        private static final Method DIALOG_CREATE;
        private static final Method SHOW_DIALOG;
        private static final Method CUSTOM_CLICK_1;
        private static final Method CUSTOM_CLICK_2;
        private static final Method KEY_FACTORY;

        private static final Method DIALOG_BUILDER_EMPTY;
        private static final Method DIALOG_BUILDER_BASE;
        private static final Method DIALOG_BUILDER_TYPE;

        static {
            boolean supp = false;
            Class<?> consumerCls = null;
            Method dbBuilder = null;
            Method dbCanClose = null;
            Method dbBody = null;
            Method dbBuild = null;
            Method dBodyPlain = null;
            Method abBuilder = null;
            Method abAction = null;
            Method abBuild = null;
            Method dtConfirmation = null;
            Method dCreate = null;
            Method sDialog = null;
            Method cClick1 = null;
            Method cClick2 = null;
            Method kFactory = null;
            Method dBuilderEmpty = null;
            Method dBuilderBase = null;
            Method dBuilderType = null;

            try {
                consumerCls = Class.forName("java.util.function.Consumer");
                Class<?> dClass = Class.forName("io.papermc.paper.dialog.Dialog");
                Class<?> kClass = Class.forName("net.kyori.adventure.key.Key");
                kFactory = kClass.getMethod("key", String.class);
                kFactory.setAccessible(true);

                Class<?> dbClass = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase");
                dbBuilder = findMethodWithParam(dbClass, "builder", Component.class);
                if (dbBuilder != null) {
                    Class<?> dbBuilderClass = dbBuilder.getReturnType();
                    dbCanClose = findMethodByName(dbBuilderClass, "canCloseWithEscape");
                    dbBody = findMethodByName(dbBuilderClass, "body");
                    dbBuild = findMethodByName(dbBuilderClass, "build");
                }

                Class<?> dBodyClass = Class.forName("io.papermc.paper.registry.data.dialog.body.DialogBody");
                dBodyPlain = findMethodWithParam(dBodyClass, "plainMessage", Component.class);

                Class<?> abClass = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton");
                abBuilder = findMethodWithParam(abClass, "builder", Component.class);
                if (abBuilder != null) {
                    Class<?> abBuilderClass = abBuilder.getReturnType();
                    abAction = findMethodByName(abBuilderClass, "action");
                    abBuild = findMethodByName(abBuilderClass, "build");
                }

                Class<?> dtClass = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
                dtConfirmation = findMethodWithParam(dtClass, "confirmation", abClass, abClass);

                dCreate = findMethodWithParam(dClass, "create", consumerCls);

                sDialog = findMethodWithParam(Player.class, "showDialog", dClass);

                try {
                    Class<?> builderClass = Class.forName("io.papermc.paper.dialog.Dialog$Builder");
                    dBuilderEmpty = findMethodByName(builderClass, "empty");
                    dBuilderBase = findMethodWithParam(builderClass, "base", dbClass);
                    dBuilderType = findMethodWithParam(builderClass, "type", dtClass);
                } catch (Throwable ignored) {
                }

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
            CONSUMER_CLASS = consumerCls;
            DIALOG_BASE_BUILDER = dbBuilder;
            DIALOG_BASE_CAN_CLOSE = dbCanClose;
            DIALOG_BASE_BODY = dbBody;
            DIALOG_BASE_BUILD = dbBuild;
            DIALOG_BODY_PLAIN = dBodyPlain;
            ACTION_BUTTON_BUILDER = abBuilder;
            ACTION_BUTTON_ACTION = abAction;
            ACTION_BUTTON_BUILD = abBuild;
            DIALOG_TYPE_CONFIRMATION = dtConfirmation;
            DIALOG_CREATE = dCreate;
            SHOW_DIALOG = sDialog;
            CUSTOM_CLICK_1 = cClick1;
            CUSTOM_CLICK_2 = cClick2;
            KEY_FACTORY = kFactory;
            DIALOG_BUILDER_EMPTY = dBuilderEmpty;
            DIALOG_BUILDER_BASE = dBuilderBase;
            DIALOG_BUILDER_TYPE = dBuilderType;
        }

        private static Method findMethodByName(Class<?> clazz, String name) {
            try {
                for (Method m : clazz.getMethods()) {
                    if (m.getName().equals(name)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
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
