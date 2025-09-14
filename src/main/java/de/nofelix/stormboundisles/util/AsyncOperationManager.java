package de.nofelix.stormboundisles.util;

import de.nofelix.stormboundisles.StormboundIslesMod;
import de.nofelix.stormboundisles.init.Initialize;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.Objects;

/**
 * Async operation manager for handling expensive operations off the main server
 * thread.
 * Provides safe async execution with main thread callbacks.
 *
 * Features:
 * - Thread pool management with configurable size
 * - Priority-based operation queuing
 * - Main thread callbacks for thread-safe operations
 * - Proper error handling and logging
 * - Resource cleanup on server shutdown
 */
public final class AsyncOperationManager {

    private static final Logger LOGGER = StormboundIslesMod.LOGGER;

    // Thread pool for async operations
    private static ExecutorService asyncExecutor;

    // Main thread task queue
    private static final Queue<MainThreadTask> mainThreadQueue = new ConcurrentLinkedQueue<>();

    // Operation counters for monitoring
    private static final AtomicInteger activeOperations = new AtomicInteger(0);
    private static final AtomicInteger completedOperations = new AtomicInteger(0);
    private static final AtomicInteger failedOperations = new AtomicInteger(0);

    // Performance monitoring
    private static final AtomicLong totalAsyncTime = new AtomicLong(0);
    private static final AtomicLong totalCallbackTime = new AtomicLong(0);
    private static final AtomicInteger queueFullCount = new AtomicInteger(0);

    // Configuration
    private static final int DEFAULT_THREAD_POOL_SIZE = 4;
    private static final long MAIN_THREAD_CALLBACK_TIMEOUT_MS = 5000; // 5 seconds
    private static final int MAX_CALLBACKS_PER_TICK = 20;
    private static final int MAX_CONCURRENT_OPERATIONS = 12;
    private static final Semaphore operationSemaphore = new Semaphore(MAX_CONCURRENT_OPERATIONS);

    private AsyncOperationManager() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Initialize the async operation manager.
     * Should be called during mod initialization.
     */
    @Initialize(priority = 50, description = "Initialize async operation manager")
    public static void initialize() {
        LOGGER.info("Initializing AsyncOperationManager");

        // Create thread pool with daemon threads
        asyncExecutor = Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE, r -> {
            Thread t = new Thread(r, "Stormbound-Async-Op-" + System.currentTimeMillis());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1); // Slightly lower priority than main thread
            return t;
        });

        // Register main thread callback processor
        ServerTickEvents.END_SERVER_TICK.register(AsyncOperationManager::processMainThreadCallbacks);

        LOGGER.info("AsyncOperationManager initialized with {} threads", DEFAULT_THREAD_POOL_SIZE);
    }

    /**
     * Submit an async operation with a main thread callback.
     *
     * @param operation  The expensive operation to run async
     * @param onComplete Callback to run on main thread when operation completes
     * @param onError    Callback for error handling (runs on main thread)
     * @return Future for the async operation
     */
    public static <T> CompletableFuture<T> submitAsync(
            Callable<T> operation,
            Consumer<T> onComplete,
            Consumer<Throwable> onError) {

        if (asyncExecutor == null || asyncExecutor.isShutdown()) {
            throw new IllegalStateException("AsyncOperationManager not initialized");
        }

        // Rate limiting - check if we can acquire a permit
        if (!operationSemaphore.tryAcquire()) {
            LOGGER.warn("Async operation queue full, rejecting operation");
            queueFullCount.incrementAndGet();
            throw new IllegalStateException("Async operation queue full");
        }

        activeOperations.incrementAndGet();
        long startTime = System.nanoTime();

        return CompletableFuture.supplyAsync(() -> {
            try {
                T result = operation.call();
                long asyncTime = (System.nanoTime() - startTime) / 1_000_000; // Convert to milliseconds
                totalAsyncTime.addAndGet(asyncTime);

                // Log warning for slow operations
                if (asyncTime > 1000) { // 1 second
                    LOGGER.warn("Slow async operation completed in {}ms", asyncTime);
                }

                return result;
            } catch (Exception e) {
                LOGGER.error("Async operation failed", e);
                failedOperations.incrementAndGet();

                // Schedule error callback on main thread
                if (onError != null) {
                    mainThreadQueue.offer(new MainThreadTask(
                            () -> onError.accept(e),
                            OperationPriority.HIGH));
                }
                throw new CompletionException("Async operation failed", e);
            } finally {
                activeOperations.decrementAndGet();
                operationSemaphore.release(); // Release the permit
            }
        }, asyncExecutor).whenComplete((result, throwable) -> {
            completedOperations.incrementAndGet();

            if (throwable == null && onComplete != null) {
                // Schedule success callback on main thread
                mainThreadQueue.offer(new MainThreadTask(
                        () -> onComplete.accept(result),
                        OperationPriority.NORMAL));
            }
        });
    }

    /**
     * Submit a fire-and-forget async operation (no callback).
     *
     * @param operation The operation to run async
     * @return Future for the operation
     */
    public static CompletableFuture<Void> submitAsync(Runnable operation) {
        return submitAsync(
                () -> {
                    operation.run();
                    return null;
                },
                null,
                null);
    }

    /**
     * Submit an async operation with only success callback.
     *
     * @param operation  The expensive operation to run async
     * @param onComplete Callback to run on main thread when operation completes
     * @return Future for the async operation
     */
    public static <T> CompletableFuture<T> submitAsync(
            Callable<T> operation,
            Consumer<T> onComplete) {

        return submitAsync(operation, onComplete, null);
    }

    /**
     * Schedule a task to run on the main thread.
     * Useful for operations that need to modify game state.
     *
     * @param task     The task to run on main thread
     * @param priority Priority of the task
     */
    public static void runOnMainThread(Runnable task, OperationPriority priority) {
        mainThreadQueue.offer(new MainThreadTask(task, priority));
    }

    /**
     * Schedule a task to run on the main thread with normal priority.
     *
     * @param task The task to run on main thread
     */
    public static void runOnMainThread(Runnable task) {
        runOnMainThread(task, OperationPriority.NORMAL);
    }

    /**
     * Process queued main thread callbacks.
     * Called automatically on server tick, but can be called manually if needed.
     */
    private static void processMainThreadCallbacks(MinecraftServer server) {
        int processed = 0;
        long startTime = System.nanoTime();

        // Process up to MAX_CALLBACKS_PER_TICK tasks per tick to prevent main thread
        // blocking
        MainThreadTask task;
        while (processed < MAX_CALLBACKS_PER_TICK && (task = mainThreadQueue.poll()) != null) {
            long callbackStartTime = System.nanoTime();
            try {
                task.runnable.run();
                processed++;

                long callbackTime = (System.nanoTime() - callbackStartTime) / 1_000_000; // Convert to milliseconds
                totalCallbackTime.addAndGet(callbackTime);

                // Log warning for slow callbacks
                if (callbackTime > 50) { // 50ms
                    LOGGER.warn("Slow main thread callback completed in {}ms", callbackTime);
                }
            } catch (Exception e) {
                LOGGER.error("Main thread callback failed", e);
            }

            // Safety timeout to prevent infinite loops
            long totalTime = (System.nanoTime() - startTime) / 1_000_000;
            if (totalTime > MAIN_THREAD_CALLBACK_TIMEOUT_MS) {
                LOGGER.warn("Main thread callback processing timed out after {} ms", MAIN_THREAD_CALLBACK_TIMEOUT_MS);
                break;
            }
        }

        if (processed > 0) {
            long totalTime = (System.nanoTime() - startTime) / 1_000_000;
            LOGGER.debug("Processed {} main thread callbacks in {}ms", processed, totalTime);
        }
    }

    /**
     * Shutdown the async operation manager.
     * Should be called during mod shutdown.
     */
    public static void shutdown() {
        LOGGER.info("Shutting down AsyncOperationManager");

        if (asyncExecutor != null && !asyncExecutor.isShutdown()) {
            asyncExecutor.shutdown();
            try {
                if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                    LOGGER.warn("Async executor did not terminate gracefully, forced shutdown");
                }
            } catch (InterruptedException e) {
                asyncExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Clear any remaining tasks
        mainThreadQueue.clear();

        LOGGER.info("AsyncOperationManager shutdown complete");
    }

    /**
     * Get performance statistics.
     *
     * @return Map containing performance metrics
     */
    public static java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("activeOperations", activeOperations.get());
        stats.put("completedOperations", completedOperations.get());
        stats.put("failedOperations", failedOperations.get());
        stats.put("queuedMainThreadTasks", mainThreadQueue.size());
        stats.put("threadPoolActive", asyncExecutor != null && !asyncExecutor.isShutdown());
        stats.put("totalAsyncTimeMs", totalAsyncTime.get());
        stats.put("totalCallbackTimeMs", totalCallbackTime.get());
        stats.put("queueFullCount", queueFullCount.get());
        stats.put("availablePermits", operationSemaphore.availablePermits());
        stats.put("maxConcurrentOperations", MAX_CONCURRENT_OPERATIONS);
        stats.put("maxCallbacksPerTick", MAX_CALLBACKS_PER_TICK);

        // Calculate averages if we have data
        long totalOps = (long) completedOperations.get() + failedOperations.get();
        if (totalOps > 0) {
            stats.put("averageAsyncTimeMs", totalAsyncTime.get() / totalOps);
        }
        if (completedOperations.get() > 0) {
            stats.put("averageCallbackTimeMs", totalCallbackTime.get() / completedOperations.get());
        }

        return stats;
    }

    /**
     * Operation priority levels for main thread callbacks.
     */
    public enum OperationPriority {
        CRITICAL(0), // Process immediately
        HIGH(1), // Process soon
        NORMAL(2), // Normal priority
        LOW(3); // Process when convenient

        private final int value;

        OperationPriority(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    /**
     * Internal class for main thread tasks with priority.
     */
    private static class MainThreadTask implements Comparable<MainThreadTask> {
        final Runnable runnable;
        final OperationPriority priority;
        final long submitTime;

        MainThreadTask(Runnable runnable, OperationPriority priority) {
            this.runnable = runnable;
            this.priority = priority;
            this.submitTime = System.currentTimeMillis();
        }

        @Override
        public int compareTo(MainThreadTask other) {
            // First compare by priority, then by submit time (FIFO within same priority)
            int priorityCompare = Integer.compare(this.priority.getValue(), other.priority.getValue());
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Long.compare(this.submitTime, other.submitTime);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (!(obj instanceof MainThreadTask))
                return false;
            MainThreadTask other = (MainThreadTask) obj;
            return priority == other.priority && submitTime == other.submitTime;
        }

        @Override
        public int hashCode() {
            return Objects.hash(priority, submitTime);
        }
    }
}
