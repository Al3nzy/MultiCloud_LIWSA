package org.liwsa.multicloud.experiment;

import org.liwsa.multicloud.metrics.SchedulingMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Runs a scheduling algorithm {@link #numRuns} independent times and
 * collects the resulting {@link SchedulingMetrics} into an
 * {@link ExperimentSummary}, satisfying the "N independent runs with
 * mean/min/max/stddev/95% CI" experiment-design requirement without tying
 * this class to any one of the four algorithms' constructors.
 *
 * <p>The caller supplies a small function ({@code runIndex -> SchedulingMetrics})
 * that builds a fresh algorithm instance (typically seeded with
 * {@code runIndex}, or a derived seed, for reproducibility) and returns its
 * metrics -- see {@code ExperimentDemo} for a worked example comparing all
 * four algorithms this way.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class ExperimentRunner {

    public static final int DEFAULT_RUNS = 30;

    private final int numRuns;

    public ExperimentRunner() {
        this(DEFAULT_RUNS);
    }

    public ExperimentRunner(int numRuns) {
        this.numRuns = numRuns;
    }

    /**
     * @param algorithmName label attached to every run's metrics and to the returned summary
     * @param trial         builds and runs one trial, given its 0-based run index; should vary its
     *                      random seed with {@code runIndex} so the {@code numRuns} trials are
     *                      genuinely independent samples rather than {@code numRuns} copies of one run
     */
    public ExperimentSummary run(String algorithmName, IntFunction<SchedulingMetrics> trial) {
        List<SchedulingMetrics> results = new ArrayList<>(numRuns);
        for (int i = 0; i < numRuns; i++) {
            results.add(trial.apply(i));
        }
        return new ExperimentSummary(algorithmName, results);
    }

    public int getNumRuns() {
        return numRuns;
    }
}
