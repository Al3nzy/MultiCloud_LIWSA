package org.liwsa.multicloud.ml;

import org.liwsa.multicloud.model.CloudTask;
import org.liwsa.multicloud.model.ResourceCandidate;

/**
 * A general-purpose (task, resource) feature vector, for any future ML
 * component that needs the fuller feature set (task length, task depth,
 * task priority, VM MIPS, VM utilization, available RAM, queue length,
 * cloud latency, execution cost, power consumption, bandwidth, storage,
 * current load, cloud reliability, SLA violations) rather than
 * {@code LiwsaTaskMLPlanningAlgorithm}'s specialised, smaller 9-feature
 * vector tuned specifically for its in-house OLS regression. That class is
 * left as-is (changing its feature count would retrain a working model for
 * no benefit); this one exists for anything built on top of this framework
 * later that wants the richer set.
 *
 * <p>Every feature here is a genuine measurement from data this framework
 * actually has, <b>except</b> two which are honestly stubbed rather than
 * faked: {@code cloudReliability} and {@code slaViolationRate} default to
 * "perfectly reliable, no violations yet" (1.0 / 0.0) unless the caller
 * supplies real tracked values (e.g. accumulated from
 * {@code metrics.SchedulingMetrics} across prior runs) -- this framework
 * doesn't yet maintain that history itself. Likewise, {@code vmUtilization}
 * is a rough pre-decode estimate from queued load, not a measurement from a
 * running simulation, since this extractor is meant to be usable before a
 * schedule is decoded, not just after.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class FeatureExtractor {

    public static final int NUM_FEATURES = 15;

    private FeatureExtractor() { }

    public static String[] featureNames() {
        return new String[]{
                "taskLength", "taskDepth", "taskPriority", "vmMips", "vmUtilization",
                "availableRamMb", "queueLength", "cloudLatencyMs", "executionCost",
                "powerConsumptionProxy", "bandwidthMbps", "storageMb", "currentLoadSeconds",
                "cloudReliability", "slaViolationRate"
        };
    }

    /**
     * @param task                 the task being considered for placement
     * @param resource             the candidate resource
     * @param taskDepth            dependency-graph depth of this task (0 for an independent bag-of-tasks workload)
     * @param queueLength          number of tasks already assigned to {@code resource} in the current (partial) plan
     * @param currentLoadSeconds   total busy-seconds already assigned to {@code resource} in the current plan
     * @param maxMips              normalisation constant: largest MIPS among all candidate resources
     * @param cloudLatencyMs       estimated latency to this resource's cloud, e.g. from
     *                             {@code MultiCloudEnvironment.getLatencyMs}; pass 0 if not modelled
     * @param cloudReliability     a [0,1] reliability score for this resource's cloud; pass 1.0 if not tracked
     * @param recentSlaViolationRate this cloud's recent SLA violation rate in [0,1]; pass 0.0 if not tracked
     */
    public static double[] extract(CloudTask task, ResourceCandidate resource, int taskDepth,
                                    int queueLength, double currentLoadSeconds, double maxMips,
                                    double cloudLatencyMs, double cloudReliability, double recentSlaViolationRate) {
        double duration = task.getCloudletLength() / Math.max(resource.getMips(), 1e-6);
        double executionCost = duration * resource.getCostPerSecond();
        // Same linear idle+dynamic proxy used by MetricsCalculator/RlGaTaskSchedulingAlgorithm,
        // reused here rather than re-invented so "power consumption" means the same thing everywhere.
        double powerProxy = 0.5 + 0.5 * (resource.getMips() / Math.max(maxMips, 1e-9));
        double vmUtilizationEstimate = (currentLoadSeconds + duration > 1e-9)
                ? Math.min(1.0, currentLoadSeconds / (currentLoadSeconds + duration))
                : 0.0;

        return new double[]{
                task.getCloudletLength(),
                taskDepth,
                task.getPriority().weight(),
                resource.getMips(),
                vmUtilizationEstimate,
                resource.getRamMb(),
                queueLength,
                cloudLatencyMs,
                executionCost,
                powerProxy,
                resource.getBwMbps(),
                resource.getStorageMb(),
                currentLoadSeconds,
                cloudReliability,
                recentSlaViolationRate
        };
    }
}
