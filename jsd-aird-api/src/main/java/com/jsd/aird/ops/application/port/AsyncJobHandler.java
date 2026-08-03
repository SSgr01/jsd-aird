package com.jsd.aird.ops.application.port;

import com.fasterxml.jackson.databind.JsonNode;

public interface AsyncJobHandler {

    boolean supports(String jobType);

    JsonNode handle(JsonNode payload);
}
