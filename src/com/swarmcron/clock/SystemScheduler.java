package com.swarmcron.clock;

import com.swarmcron.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Production Scheduler: one small daemon ScheduledExecutorService per node.
 * Every scheduled task is wrapped so a thrown RuntimeException is logged
 * instead of silently killing that recurring task in the executor.
 */
public final class SystemScheduler implements Scheduler {

    private final ScheduledExecutorService executor;

    public SystemScheduler(String nodeId) {
        this.executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "scheduler-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public Cancellable scheduleAtFixedRate(Runnable task, long initialDelayMillis, long periodMillis) {
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                wrap(task), initialDelayMillis, periodMillis, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public Cancellable scheduleOnce(Runnable task, long delayMillis) {
        ScheduledFuture<?> future = executor.schedule(wrap(task), delayMillis, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    private Runnable wrap(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                Log.error("scheduler", "scheduled task threw: %s", e);
            }
        };
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
