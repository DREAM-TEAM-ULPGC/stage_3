package com.dreamteam.common.broker;

import com.dreamteam.common.model.IndexRequest;

/**
 * Functional interface for processing IndexRequest messages.
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * Process an incoming IndexRequest.
     * 
     * @param request the index request to process
     * @throws Exception if processing fails
     */
    void handle(IndexRequest request) throws Exception;
}
