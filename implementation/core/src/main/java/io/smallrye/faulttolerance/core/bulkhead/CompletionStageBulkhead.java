package io.smallrye.faulttolerance.core.bulkhead;

import static io.smallrye.faulttolerance.core.util.CompletionStages.failedStage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

import org.jboss.logging.Logger;

import io.smallrye.faulttolerance.core.FaultToleranceStrategy;
import io.smallrye.faulttolerance.core.InvocationContext;

/**
 * @author Michal Szynkiewicz, michal.l.szynkiewicz@gmail.com
 */
public class CompletionStageBulkhead<V> extends BulkheadBase<CompletionStage<V>> {
    private static final Logger logger = Logger.getLogger(CompletionStageBulkhead.class);

    private final ExecutorService executor;
    private final int queueSize;
    private final Semaphore workSemaphore;
    private final Semaphore capacitySemaphore;

    public CompletionStageBulkhead(
            FaultToleranceStrategy<CompletionStage<V>> delegate,
            String description,
            ExecutorService executor,
            int size, int queueSize,
            MetricsRecorder recorder) {
        super(description, delegate, recorder);
        workSemaphore = new Semaphore(size);
        capacitySemaphore = new Semaphore(size + queueSize);
        this.queueSize = queueSize;
        this.executor = executor;
    }

    @Override
    public CompletionStage<V> apply(InvocationContext<CompletionStage<V>> ctx) {
        // TODO we shouldn't put tasks into the executor if they immediately block on workSemaphore,
        //  they should be put into some queue
        if (capacitySemaphore.tryAcquire()) {
            CompletionStageBulkheadTask task = new CompletionStageBulkheadTask(System.nanoTime(), ctx);
            executor.execute(task);
            recorder.bulkheadQueueEntered();
            return task.result;
        } else {
            recorder.bulkheadRejected();
            return failedStage(bulkheadRejected());
        }
    }

    // only for tests
    int getQueueSize() {
        return Math.max(0, queueSize - capacitySemaphore.availablePermits());
    }

    private class CompletionStageBulkheadTask implements Runnable {
        private final long timeEnqueued;
        private final CompletableFuture<V> result = new CompletableFuture<>();
        private final InvocationContext<CompletionStage<V>> context;

        private CompletionStageBulkheadTask(long timeEnqueued,
                InvocationContext<CompletionStage<V>> context) {
            this.timeEnqueued = timeEnqueued;
            this.context = context;
        }

        public void run() {
            try {
                workSemaphore.acquire();
            } catch (InterruptedException e) {
                // among other occasions, this also happens during shutdown
                result.completeExceptionally(e);
                return;
            }

            CompletionStage<V> rawResult;
            long startTime = System.nanoTime();
            recorder.bulkheadQueueLeft(startTime - timeEnqueued);
            recorder.bulkheadEntered();
            try {
                rawResult = delegate.apply(context);
                rawResult.whenComplete((value, error) -> {
                    releaseSemaphores();
                    recorder.bulkheadLeft(System.nanoTime() - startTime);
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(value);
                    }
                });
            } catch (Exception e) {
                releaseSemaphores();
                recorder.bulkheadLeft(System.nanoTime() - startTime);
                result.completeExceptionally(e);
            }
        }
    }

    private void releaseSemaphores() {
        workSemaphore.release();
        capacitySemaphore.release();
    }
}
