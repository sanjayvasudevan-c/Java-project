package com.swarmcron.exec;

/** Notified for every RunSummary this node learns about -- its own runs and peers' RUN_RESULT broadcasts alike. See http.SseHub for the one real subscriber (M9). */
@FunctionalInterface
public interface RunResultListener {
    void onRunResult(RunSummary summary);
}
