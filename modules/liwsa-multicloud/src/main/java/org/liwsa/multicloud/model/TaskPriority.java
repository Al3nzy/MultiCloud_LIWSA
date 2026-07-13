package org.liwsa.multicloud.model;

/**
 * Priority class of a {@link CloudTask}, following the three-tier scheme
 * (1 = highest, 3 = lowest) used throughout the multi-cloud scheduling
 * literature this framework benchmarks against &mdash; e.g. the P_i in
 * {1,2,3} priority model of Narsimhulu &amp; Kumar, "A hybrid RL-GA-LSTM-AE
 * framework for energy-aware and SLA-driven task scheduling in cloud
 * computing environments", Scientific Reports 16:14961 (2026).
 *
 * <p>The enum also carries a numeric {@link #weight()} in (0, 1] so that
 * scheduling algorithms and fitness functions can use priority as a
 * continuous term (e.g. in a weighted sum) without a separate lookup table.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public enum TaskPriority {

    /** Highest priority: interactive / latency-critical / SLA-strict tasks. */
    HIGH(1, 1.0),

    /** Default priority for ordinary batch tasks. */
    MEDIUM(2, 0.6),

    /** Lowest priority: deferrable, best-effort background tasks. */
    LOW(3, 0.3);

    private final int level;
    private final double weight;

    TaskPriority(int level, double weight) {
        this.level = level;
        this.weight = weight;
    }

    /**
     * @return the ordinal priority level as used in the literature (1 = highest).
     */
    public int level() {
        return level;
    }

    /**
     * @return a normalized weight in (0, 1], higher meaning more important,
     *         suitable for direct use in weighted-sum fitness functions.
     */
    public double weight() {
        return weight;
    }

    /**
     * Resolves a {@link TaskPriority} from the literature's numeric level
     * (1, 2 or 3), defaulting to {@link #MEDIUM} for any out-of-range value
     * rather than throwing, since priority is frequently parsed from
     * untrusted external workload traces.
     *
     * @param level the numeric priority level, expected in {1, 2, 3}
     * @return the corresponding TaskPriority
     */
    public static TaskPriority fromLevel(int level) {
        return switch (level) {
            case 1 -> HIGH;
            case 3 -> LOW;
            default -> MEDIUM;
        };
    }
}
