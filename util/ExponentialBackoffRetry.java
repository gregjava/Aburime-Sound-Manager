/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Utility for exponential backoff retry logic
 */
public class ExponentialBackoffRetry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExponentialBackoffRetry.class);
    
    /**
     * Execute an operation with exponential backoff retry
     * @param operation The operation to execute
     * @param maxRetries Maximum number of retries
     * @param operationName Name of operation for logging
     * @return Result of the operation
     * @throws Exception If all retries fail
     */
    public static <T> T executeWithRetry(Supplier<T> operation, int maxRetries, String operationName) 
            throws Exception {
        
        int retryCount = 0;
        Exception lastException = null;
        
        while (retryCount < maxRetries) {
            try {
                retryCount++;
                LOGGER.debug("{} attempt {}/{}", operationName, retryCount, maxRetries);
                
                T result = operation.get();
                if (result != null) {
                    LOGGER.info("{} succeeded on attempt {}", operationName, retryCount);
                    return result;
                }
                
                // If operation returned null (not an exception), wait and retry
                if (retryCount < maxRetries) {
                    int backoffSeconds = calculateBackoffSeconds(retryCount);
                    LOGGER.info("{} returned null, waiting {} seconds before retry...", 
                               operationName, backoffSeconds);
                    Thread.sleep(backoffSeconds * 1000L);
                }
                
            } catch (Exception e) {
                lastException = e;
                LOGGER.warn("{} attempt {} failed: {}", operationName, retryCount, e.getMessage());
                
                if (retryCount < maxRetries) {
                    int backoffSeconds = calculateBackoffSeconds(retryCount);
                    LOGGER.info("Waiting {} seconds before retry...", backoffSeconds);
                    Thread.sleep(backoffSeconds * 1000L);
                }
            }
        }
        
        throw new Exception(String.format("%s failed after %d attempts", operationName, maxRetries), 
                          lastException);
    }
    
    /**
     * Execute a void operation with exponential backoff retry
     */
    public static void executeVoidWithRetry(Runnable operation, int maxRetries, String operationName) 
            throws Exception {
        
        executeWithRetry(() -> {
            operation.run();
            return true; // Return dummy value
        }, maxRetries, operationName);
    }
    
    /**
     * Calculate exponential backoff delay: 2^(retry-1) seconds
     */
    public static int calculateBackoffSeconds(int retryCount) {
        return (int) Math.pow(2, retryCount - 1);
    }
    
    /**
     * Execute with default 20 retries (your requirement)
     */
    public static <T> T executeWithRetry(Supplier<T> operation, String operationName) throws Exception {
        return executeWithRetry(operation, 20, operationName);
    }
    
    /**
     * Check if an operation should be retried based on error message
     */
    public static boolean shouldRetryOnError(String errorMessage) {
        if (errorMessage == null) return false;
        
        String message = errorMessage.toLowerCase();
        return message.contains("connection") ||
               message.contains("timeout") ||
               message.contains("download") ||
               message.contains("network") ||
               message.contains("proxy") ||
               message.contains("temporary") ||
               message.contains("retry") ||
               message.contains("rate limit") ||
               message.contains("too many requests");
    }
}