package com.jsd.aird.ops.application.port;

/** Ragent-inspired title generation contract executed only by the worker. */
public interface ConversationTitleGenerator {

    GeneratedTitle generate(String question);

    record GeneratedTitle(String title, String outcome, String model, String promptVersion,
                          String fallbackReason) { }
}
