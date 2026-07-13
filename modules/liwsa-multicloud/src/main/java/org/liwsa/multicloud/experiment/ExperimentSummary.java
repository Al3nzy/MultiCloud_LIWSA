package org.liwsa.multicloud.experiment;

import org.liwsa.multicloud.metrics.SchedulingMetrics;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * The raw per-run {@link SchedulingMetrics} collected for one algorithm
 * across N independent trials, with {@link #summarize} for turning any one
 * metric field into a {@link StatSummary} and {@link #toCsv} for the raw
 * per-run rows.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class ExperimentSummary {

    private final String algorithmName;
    private final List<SchedulingMetrics> runs;

    public ExperimentSummary(String algorithmName, List<SchedulingMetrics> runs) {
        this.algorithmName = algorithmName;
        this.runs = runs;
    }

    public String getAlgorithmName() {
        return algorithmName;
    }

    public List<SchedulingMetrics> getRuns() {
        return runs;
    }

    /** @param extractor picks the metric field to summarise, e.g. {@code m -> m.makespan} */
    public StatSummary summarize(String metricName, ToDoubleFunction<SchedulingMetrics> extractor) {
        double[] values = new double[runs.size()];
        for (int i = 0; i < runs.size(); i++) {
            values[i] = extractor.applyAsDouble(runs.get(i));
        }
        return StatSummary.of(metricName, values);
    }

    /** @return summaries for the headline metrics (makespan, cost, SLA violation rate, utilization, energy, runtime). */
    public List<StatSummary> summarizeHeadlineMetrics() {
        return List.of(
                summarize("makespan", m -> m.makespan),
                summarize("totalCost", m -> m.totalCost),
                summarize("avgWaitingTime", m -> m.avgWaitingTime),
                summarize("avgTurnaroundTime", m -> m.avgTurnaroundTime),
                summarize("slaViolationRate", m -> m.slaViolationRate),
                summarize("avgCpuUtilization", m -> m.avgCpuUtilization),
                summarize("loadBalancingIndex", m -> m.loadBalancingIndex),
                summarize("energyProxy", m -> m.energyProxy),
                summarize("carbonProxyGrams", m -> m.carbonProxyGrams),
                summarize("algorithmRuntimeMillis", m -> m.algorithmRuntimeMillis));
    }

    /** @return every run's metrics as CSV rows (header + one row per run), for archiving raw results alongside the summary. */
    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append(SchedulingMetrics.csvHeader()).append('\n');
        for (SchedulingMetrics m : runs) {
            sb.append(m.toCsvRow()).append('\n');
        }
        return sb.toString();
    }
}
