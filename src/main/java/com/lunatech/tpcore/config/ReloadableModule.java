package com.lunatech.tpcore.config;

public interface ReloadableModule {

    String getModuleName();

    boolean reloadConfig();
}
