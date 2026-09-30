package com.lunatech.tpcore.module.tpa.gui.impl;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Reflection cache for Paper Dialog API components across Paper versions.
 */
final class DialogReflectionCache {

    static final boolean SUPPORTED;
    static final Class<?> CONSUMER_CLASS;
    static final Class<?>[] CONSUMER_INTERFACES;
    static final Method DIALOG_BASE_BUILDER;
    static final Method DIALOG_BASE_CAN_CLOSE;
    static final Method DIALOG_BASE_BODY;
    static final Method DIALOG_BASE_BUILD;
    static final Method DIALOG_BODY_PLAIN;
    static final Method ACTION_BUTTON_BUILDER;
    static final Method ACTION_BUTTON_ACTION;
    static final Method ACTION_BUTTON_BUILD;
    static final Method DIALOG_TYPE_CONFIRMATION;
    static final Method DIALOG_CREATE;
    static final Method SHOW_DIALOG;
    static final Method CUSTOM_CLICK_1;
    static final Method CUSTOM_CLICK_2;
    static final Method KEY_FACTORY;

    static final Method DIALOG_BUILDER_EMPTY;
    static final Method DIALOG_BUILDER_BASE;
    static final Method DIALOG_BUILDER_TYPE;

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
            consumerCls = Consumer.class;
            Class<?> dClass = Class.forName("io.papermc.paper.dialog.Dialog");
            kFactory = Key.class.getMethod("key", String.class);
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
                    if (pTypes.length == 1 && pTypes[0].isAssignableFrom(Key.class)) {
                        cClick1 = m;
                        cClick1.setAccessible(true);
                    } else if (pTypes.length == 2 && pTypes[0].isAssignableFrom(Key.class)) {
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
        CONSUMER_INTERFACES = (consumerCls != null) ? new Class<?>[]{ consumerCls } : new Class<?>[0];
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

    private DialogReflectionCache() {
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
