package com.petr.exception;

public class ConfigNameTakenException extends RuntimeException {
    public ConfigNameTakenException(String configName) {
        super(configName);
    }
}
