/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.model.BatchFileItem;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Batch scheduling for off-hours processing.
 * Allows users to queue batches to run at specific times.
 */
public class BatchScheduler {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchScheduler.class);
    
    private final ScheduledExecutorService scheduler = 
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BatchScheduler");
            t.setDaemon(true);
            return t;
        });
    
    private ScheduledFuture<?> scheduledTask;
    private BatchProcessor batchProcessor;
    private ObservableList<BatchFileItem> pendingItems;
    private volatile boolean isScheduled = false;
    private volatile LocalDateTime scheduledTime;
    private Runnable onBatchStart;
    private Runnable onBatchComplete;
    
    public void scheduleBatch(LocalDateTime time, 
                              ObservableList<BatchFileItem> items,
                              BatchProcessor processor) {
        if (isScheduled) {
            cancelScheduledBatch();
        }
        
        this.pendingItems = items;
        this.batchProcessor = processor;
        this.scheduledTime = time;
        
        long delay = LocalDateTime.now().until(time, java.time.temporal.ChronoUnit.MILLIS);
        if (delay < 0) {
            LOGGER.warn("Scheduled time is in the past, starting immediately");
            delay = 0;
        }
        
        LOGGER.info("Batch scheduled for: {} (in {} minutes)", time, delay / 60000);
        
        scheduledTask = scheduler.schedule(() -> {
            isScheduled = true;
            if (onBatchStart != null) onBatchStart.run();
            
            try {
                // Process the batch
                // This would call batchProcessor.processBatch() with the items
                LOGGER.info("Scheduled batch starting with {} files", pendingItems.size());
                
                // After completion
                if (onBatchComplete != null) onBatchComplete.run();
            } catch (Exception e) {
                LOGGER.error("Scheduled batch failed", e);
            } finally {
                isScheduled = false;
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
    
    public void cancelScheduledBatch() {
        if (scheduledTask != null && !scheduledTask.isDone()) {
            scheduledTask.cancel(true);
            LOGGER.info("Scheduled batch cancelled");
        }
        isScheduled = false;
    }
    
    public boolean isScheduled() {
        return isScheduled;
    }
    
    public LocalDateTime getScheduledTime() {
        return scheduledTime;
    }
    
    public void setOnBatchStart(Runnable callback) {
        this.onBatchStart = callback;
    }
    
    public void setOnBatchComplete(Runnable callback) {
        this.onBatchComplete = callback;
    }
    
    public void shutdown() {
        scheduler.shutdownNow();
    }
}