package org.liwsa.multicloud.metrics;

/**
 * A snapshot of the metrics computed for one scheduling run (see
 * {@link MetricsCalculator}). Fields are public and final: this is a plain
 * data holder, not a class with behaviour of its own.
 *
 * <p><b>Scope note:</b> {@code energyProxy} and {@code carbonProxyGrams}
 * are illustrative linear-power-model estimates (see {@link MetricsCalculator}),
 * not a physical measurement -- a real per-host SPECpower-based figure can
 * be substituted later using CloudSim's {@code power} package without
 * changing this class's shape. Likewise, host-level idle time and live
 * migration count are not included here because they need an actual
 * running simulation (host/VM placement over time, real migration events)
 * rather than the static, pre-simulation plan this calculator works from;
 * {@code vmIdleTimeAvg}/{@code vmIdleTimeMax} are the VM-level idle time
 * this framework's flat resource-candidate model already supports.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class SchedulingMetrics {

    public final String algorithmName;
    public final int numTasks;

    public final double makespan;
    public final double totalCost;

    public final double avgWaitingTime;
    public final double maxWaitingTime;
    public final double avgTurnaroundTime;
    public final double maxTurnaroundTime;
    /** Equal to {@link #avgWaitingTime} under this framework's non-interactive, single-phase task model (see MetricsCalculator). */
    public final double avgResponseTime;

    public final double throughputTasksPerSecond;
    public final int slaViolationCount;
    public final double slaViolationRate;

    public final double avgCpuUtilization;
    /** Jain's fairness index over per-resource busy time, in (0, 1]; 1.0 means perfectly even load. */
    public final double loadBalancingIndex;

    public final double energyProxy;
    public final double carbonProxyGrams;

    public final double vmIdleTimeAvg;
    public final double vmIdleTimeMax;

    public final long algorithmRuntimeMillis;

    public SchedulingMetrics(String algorithmName, int numTasks, double makespan, double totalCost,
                              double avgWaitingTime, double maxWaitingTime, double avgTurnaroundTime,
                              double maxTurnaroundTime, double avgResponseTime, double throughputTasksPerSecond,
                              int slaViolationCount, double slaViolationRate, double avgCpuUtilization,
                              double loadBalancingIndex, double energyProxy, double carbonProxyGrams,
                              double vmIdleTimeAvg, double vmIdleTimeMax, long algorithmRuntimeMillis) {
        this.algorithmName = algorithmName;
        this.numTasks = numTasks;
        this.makespan = makespan;
        this.totalCost = totalCost;
        this.avgWaitingTime = avgWaitingTime;
        this.maxWaitingTime = maxWaitingTime;
        this.avgTurnaroundTime = avgTurnaroundTime;
        this.maxTurnaroundTime = maxTurnaroundTime;
        this.avgResponseTime = avgResponseTime;
        this.throughputTasksPerSecond = throughputTasksPerSecond;
        this.slaViolationCount = slaViolationCount;
        this.slaViolationRate = slaViolationRate;
        this.avgCpuUtilization = avgCpuUtilization;
        this.loadBalancingIndex = loadBalancingIndex;
        this.energyProxy = energyProxy;
        this.carbonProxyGrams = carbonProxyGrams;
        this.vmIdleTimeAvg = vmIdleTimeAvg;
        this.vmIdleTimeMax = vmIdleTimeMax;
        this.algorithmRuntimeMillis = algorithmRuntimeMillis;
    }

    public static String csvHeader() {
        return "algorithm,numTasks,makespan,totalCost,avgWaitingTime,maxWaitingTime,avgTurnaroundTime,"
                + "maxTurnaroundTime,avgResponseTime,throughputTasksPerSecond,slaViolationCount,slaViolationRate,"
                + "avgCpuUtilization,loadBalancingIndex,energyProxy,carbonProxyGrams,vmIdleTimeAvg,vmIdleTimeMax,"
                + "algorithmRuntimeMillis";
    }

    public String toCsvRow() {
        return String.join(",",
                algorithmName,
                String.valueOf(numTasks),
                fmt(makespan), fmt(totalCost),
                fmt(avgWaitingTime), fmt(maxWaitingTime),
                fmt(avgTurnaroundTime), fmt(maxTurnaroundTime),
                fmt(avgResponseTime), fmt(throughputTasksPerSecond),
                String.valueOf(slaViolationCount), fmt(slaViolationRate),
                fmt(avgCpuUtilization), fmt(loadBalancingIndex),
                fmt(energyProxy), fmt(carbonProxyGrams),
                fmt(vmIdleTimeAvg), fmt(vmIdleTimeMax),
                String.valueOf(algorithmRuntimeMillis));
    }

    private static String fmt(double v) {
        return String.format("%.6f", v);
    }

    @Override
    public String toString() {
        return toCsvRow();
    }
}
