package com.jsd.aird.kb.domain;

public class MediaExtractionException extends RuntimeException {

    private final String providerTaskId;
    private final String model;

    public MediaExtractionException(String message, String providerTaskId, String model, Throwable cause) {
        super(message, cause);
        this.providerTaskId = providerTaskId;
        this.model = model;
    }

    public String providerTaskId() { return providerTaskId; }
    public String model() { return model; }
}
