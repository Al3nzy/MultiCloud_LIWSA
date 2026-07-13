package org.liwsa.multicloud.experiment;

/**
 * Mean / min / max / sample standard deviation / 95% confidence interval
 * for one metric across N independent runs.
 *
 * <p>The confidence interval uses the correct two-tailed t-distribution
 * critical value for the sample's degrees of freedom (a small lookup table
 * for df 1-30, falling back to the z=1.96 normal approximation beyond
 * that, where the two are already within about 4% of each other) rather
 * than always assuming the large-sample z=1.96 value, which would
 * understate the interval for the smaller run counts people often use
 * while iterating before committing to a full 30-run experiment.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class StatSummary {

    // Two-tailed 95% critical values for df = 1..30 (index 0 unused).
    private static final double[] T_TABLE = {
            0,
            12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
            2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
            2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042
    };

    public final String metricName;
    public final int n;
    public final double mean;
    public final double min;
    public final double max;
    public final double stdDev;
    public final double ci95HalfWidth;

    public StatSummary(String metricName, int n, double mean, double min, double max,
                        double stdDev, double ci95HalfWidth) {
        this.metricName = metricName;
        this.n = n;
        this.mean = mean;
        this.min = min;
        this.max = max;
        this.stdDev = stdDev;
        this.ci95HalfWidth = ci95HalfWidth;
    }

    public double getCiLower() { return mean - ci95HalfWidth; }
    public double getCiUpper() { return mean + ci95HalfWidth; }

    public static StatSummary of(String metricName, double[] values) {
        int n = values.length;
        if (n == 0) {
            return new StatSummary(metricName, 0, 0, 0, 0, 0, 0);
        }
        double sum = 0.0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : values) {
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double mean = sum / n;
        double sqSum = 0.0;
        for (double v : values) {
            sqSum += (v - mean) * (v - mean);
        }
        double variance = (n > 1) ? sqSum / (n - 1) : 0.0;
        double stdDev = Math.sqrt(variance);
        double tValue = tCriticalValue(n - 1);
        double ciHalfWidth = (n > 1) ? tValue * stdDev / Math.sqrt(n) : 0.0;
        return new StatSummary(metricName, n, mean, min, max, stdDev, ciHalfWidth);
    }

    private static double tCriticalValue(int degreesOfFreedom) {
        if (degreesOfFreedom <= 0) {
            return 1.96;
        }
        if (degreesOfFreedom < T_TABLE.length) {
            return T_TABLE[degreesOfFreedom];
        }
        return 1.96;
    }

    @Override
    public String toString() {
        return String.format("%s: mean=%.4f [95%% CI %.4f, %.4f] min=%.4f max=%.4f stdDev=%.4f (n=%d)",
                metricName, mean, getCiLower(), getCiUpper(), min, max, stdDev, n);
    }
}
