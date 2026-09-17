package com.lunatech.tpcore.module.back.service;

public enum BackResultStatus {
    SUCCESS,
    SUCCESS_ADJUSTED_HAZARD,
    NO_BACK_LOCATION,
    NO_DEATH_LOCATION,
    NO_PERMISSION,
    WORLD_NOT_LOADED,
    UNSAFE_LOCATION,
    COOLDOWN_ACTIVE,
    ERROR;

    public boolean isSuccess() {
        return this == SUCCESS || this == SUCCESS_ADJUSTED_HAZARD;
    }
}

