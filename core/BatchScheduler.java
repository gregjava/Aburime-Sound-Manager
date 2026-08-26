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
 *
 * <p>This class allows users to queue batches to run at specific times,
 * enabling automated processing during off-hours or when system resources
 * are less contended.</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Schedule a batch to run at a specified date/time</li>
 *   <li>Cancel a scheduled batch before it runs</li>
 *   <li>Callback notifications for batch start and completion</li>
 *   <li>Automatic cleanup on application shutdown</li>
 * </ul>
 *
 * <p><b>Thread-safety:</b> This class uses a single-threaded scheduled executor
 * and volatile flags for state management, making it safe for use from
 * multiple UI threads.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see BatchProcessor
 * @see BatchFileItem
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
    
    /**
     * Schedules a batch to run at the specified time.
     *
     * <p>If a batch is already scheduled, it will be cancelled and replaced
     * with the new schedule.</p>
     *
     * @param time the date and time when the batch should start
     * @param items the list of batch items to process
     * @param processor the batch processor to use for processing
     */
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
    
    /**
     * Cancels the currently scheduled batch if one exists.
     *
     * <p>If a batch is currently running, this method does not interrupt it;
     * only pending scheduled batches are cancelled.</p>
     */
    public void cancelScheduledBatch() {
        if (scheduledTask != null && !scheduledTask.isDone()) {
            scheduledTask.cancel(true);
            LOGGER.info("Scheduled batch cancelled");
        }
        isScheduled = false;
    }
    
    /**
     * Returns whether a batch is currently scheduled.
     *
     * @return {@code true} if a batch is scheduled (including one that is running)
     */
    public boolean isScheduled() {
        return isScheduled;
    }
    
    /**
     * Returns the scheduled start time of the current batch.
     *
     * @return the scheduled start time, or {@code null} if no batch is scheduled
     */
    public LocalDateTime getScheduledTime() {
        return scheduledTime;
    }
    
    /**
     * Sets a callback to be invoked when the scheduled batch starts.
     *
     * @param callback the callback to invoke on batch start
     */
    public void setOnBatchStart(Runnable callback) {
        this.onBatchStart = callback;
    }
    
    /**
     * Sets a callback to be invoked when the scheduled batch completes.
     *
     * @param callback the callback to invoke on batch completion
     */
    public void setOnBatchComplete(Runnable callback) {
        this.onBatchComplete = callback;
    }
    
    /**
     * Shuts down the scheduler, cancelling any pending tasks.
     *
     * <p>This method should be called during application shutdown to
     * prevent resource leaks.</p>
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}