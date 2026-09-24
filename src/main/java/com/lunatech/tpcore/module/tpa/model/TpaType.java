package com.lunatech.tpcore.module.tpa.model;

public enum TpaType {
    TPA_TO("tpa_to"),
    TPA_HERE("tpa_here");

    private final String keyName;

    TpaType(String keyName) {
        this.keyName = keyName;
    }

    public String keyName() {
        return keyName;
    }
}
