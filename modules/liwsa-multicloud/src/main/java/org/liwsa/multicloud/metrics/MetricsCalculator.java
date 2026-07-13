package org.liwsa.multicloud.metrics;

import org.liwsa.multicloud.algorithm.SchedulingResult;
import org.liwsa.multicloud.model.CloudTask;
import org.liwsa.multicloud.model.ResourceCandidate;

import java.util.List;

/**
 * Computes a {@link SchedulingMetrics} snapshot from any {@link SchedulingResult}
 * against the same {@code List<ResourceCandidate>} the algorithm was given --
 * this is what makes the four algorithms directly, fairly comparable: the
 * same metric definitions are applied to all of them regardless of which
 * one produced the plan.
 *
 * <p>Two modelling choices worth being explicit about (both documented
 * again on {@link SchedulingMetrics}):
 * <ul>
 *   <li><b>Response time == waiting time.</b> This framework's tasks are
 *       non-interactive batch cloudlets with a single execution phase, so
 *       "time until the task first receives service" (response time) and
 *       "time spent queued before execution" (waiting time) are the same
 *       quantity here, unlike in an interactive/multi-phase system.</li>
 *   <li><b>Energy is a linear idle+dynamic proxy</b>
 *       ({@code 0.5 + 0.5 * mips / maxMips}, applied per busy-second), the
 *       same style of estimate used internally by the RL-GA baseline, not
 *       a physical wattage. Carbon is that proxy times a single illustrative
 *       global grid-intensity constant (not yet region-differentiated per
 *       cloud -- a natural next refinement once {@code CloudRegion} carbon
 *       data is wired to {@code ResourceCandidate}).</li>
 * </ul>
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class MetricsCalculator {

    private static final double IDLE_POWER_FRACTION = 0.5;
    private static final double DYNAMIC_POWER_FRACTION = 0.5;
    /** Illustrative global-average grid carbon intensity (grams CO2 per energy-proxy unit). Refine per-region later. */
    private static final double CARBON_INTENSITY = 400.0;

    private MetricsCalculator() { }

    public static SchedulingMetrics compute(String algorithmName, SchedulingResult result, List<ResourceCandidate> resources) {
        List<CloudTask> taskOrder = result.getTaskOrder();
        int[] assignment = result.getAssignment();
        double[] finishTimes = result.getFinishTimes();
        int n = taskOrder.size();

        double maxMips = 1.0;
        for (ResourceCandidate r : resources) {
            maxMips = Math.max(maxMips, r.getMips());
        }

        double[] resourceBusy = new double[resources.size()];
        double sumWaiting = 0.0, maxWaiting = 0.0;
        double sumTurnaround = 0.0, maxTurnaround = 0.0;
        double energy = 0.0;
        int slaViolations = 0;

        for (int k = 0; k < n; k++) {
            CloudTask task = taskOrder.get(k);
            ResourceCandidate resource = resources.get(assignment[k]);
            double duration = task.getCloudletLength() / Math.max(resource.getMips(), 1e-6);
            double finish = finishTimes[k];
            double start = finish - duration;

            double waiting = Math.max(0.0, start - task.getArrivalTime());
            double turnaround = finish - task.getArrivalTime();
            sumWaiting += waiting;
            maxWaiting = Math.max(maxWaiting, waiting);
            sumTurnaround += turnaround;
            maxTurnaround = Math.max(maxTurnaround, turnaround);

            resourceBusy[assignment[k]] += duration;
            energy += duration * (IDLE_POWER_FRACTION + DYNAMIC_POWER_FRACTION * resource.getMips() / maxMips);

            if (task.violatesDeadline(finish)) {
                slaViolations++;
            }
        }

        double makespan = result.getMakespan();
        double avgWaiting = (n > 0) ? sumWaiting / n : 0.0;
        double avgTurnaround = (n > 0) ? sumTurnaround / n : 0.0;
        double throughput = (makespan > 1e-9) ? n / makespan : 0.0;
        double slaRate = (n > 0) ? (double) slaViolations / n : 0.0;

        int m = resources.size();
        double sumBusy = 0.0, sumBusySq = 0.0, sumUtil = 0.0;
        double idleSum = 0.0, idleMax = 0.0;
        for (int r = 0; r < m; r++) {
            double busy = resourceBusy[r];
            sumBusy += busy;
            sumBusySq += busy * busy;
            sumUtil += (makespan > 1e-9) ? busy / makespan : 0.0;
            double idle = Math.max(0.0, makespan - busy);
            idleSum += idle;
            idleMax = Math.max(idleMax, idle);
        }
        double avgUtil = (m > 0) ? sumUtil / m : 0.0;
        double jain = (sumBusySq > 1e-12) ? (sumBusy * sumBusy) / (m * sumBusySq) : 1.0;
        double idleAvg = (m > 0) ? idleSum / m : 0.0;

        double carbon = energy * CARBON_INTENSITY;

        return new SchedulingMetrics(algorithmName, n, makespan, result.getCost(),
                avgWaiting, maxWaiting, avgTurnaround, maxTurnaround, avgWaiting, throughput,
                slaViolations, slaRate, avgUtil, jain, energy, carbon,
                idleAvg, idleMax, result.getWallClockMillis());
    }
}
