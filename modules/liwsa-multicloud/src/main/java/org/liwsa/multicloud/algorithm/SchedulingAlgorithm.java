package org.liwsa.multicloud.algorithm;

/**
 * Common contract for every scheduling algorithm in this framework
 * (LIWSA-Task, LIWSA-Task-ML, and the baselines). Introduced now, once all
 * four algorithms had already converged on the same {@code run()} shape by
 * convention, rather than speculatively up front.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public interface SchedulingAlgorithm {

    /** Runs the algorithm to completion and returns its chosen schedule. */
    SchedulingResult run();

    /** @return a short, human-readable name for reports/CSV/logs (e.g. "LIWSA-Task", "WOA"). */
    String getName();
}
