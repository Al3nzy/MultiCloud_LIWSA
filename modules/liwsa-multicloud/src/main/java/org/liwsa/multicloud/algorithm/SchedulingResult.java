package org.liwsa.multicloud.algorithm;

import org.liwsa.multicloud.model.CloudTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The common output of every scheduling algorithm in this framework
 * (LIWSA-Task, LIWSA-Task-ML, and the baselines). Kept deliberately minimal
 * for now: a task-order + resource-index assignment, the two headline
 * objectives, the algorithm's own Pareto front (a single point for
 * scalarized/single-objective algorithms), and wall-clock search time so
 * that {@code algorithm runtime} can be reported without extra plumbing.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class SchedulingResult {

    private final List<CloudTask> taskOrder;
    private final int[] assignment;
    private final double[] finishTimes;
    private final double makespan;
    private final double cost;
    private final List<double[]> paretoFront;
    private final long wallClockMillis;

    public SchedulingResult(List<CloudTask> taskOrder, int[] assignment, double[] finishTimes, double makespan,
                             double cost, List<double[]> paretoFront, long wallClockMillis) {
        this.taskOrder = taskOrder;
        this.assignment = assignment;
        this.finishTimes = finishTimes;
        this.makespan = makespan;
        this.cost = cost;
        this.paretoFront = paretoFront;
        this.wallClockMillis = wallClockMillis;
    }

    public List<CloudTask> getTaskOrder() {
        return taskOrder;
    }

    /** @return assignment[k] = resource index chosen for taskOrder.get(k). */
    public int[] getAssignment() {
        return assignment;
    }

    public double getMakespan() {
        return makespan;
    }

    public double getCost() {
        return cost;
    }

    /** @return the algorithm's final Pareto front as (makespan, cost) points; a single-element list for scalarized algorithms. */
    public List<double[]> getParetoFront() {
        return paretoFront;
    }

    public long getWallClockMillis() {
        return wallClockMillis;
    }

    /** @return finishTimes[k] = simulated/predicted completion time of taskOrder.get(k) under this plan. */
    public double[] getFinishTimes() {
        return finishTimes;
    }

    /** @return a convenience view mapping cloudlet/task id to the chosen resource index. */
    public Map<Integer, Integer> taskIdToResourceIndex() {
        Map<Integer, Integer> map = new HashMap<>();
        for (int k = 0; k < taskOrder.size(); k++) {
            map.put(taskOrder.get(k).getCloudletId(), assignment[k]);
        }
        return map;
    }

    /** @return a convenience view mapping cloudlet/task id to its predicted finish time under this plan. */
    public Map<Integer, Double> taskIdToFinishTime() {
        Map<Integer, Double> map = new HashMap<>();
        for (int k = 0; k < taskOrder.size(); k++) {
            map.put(taskOrder.get(k).getCloudletId(), finishTimes[k]);
        }
        return map;
    }

    @Override
    public String toString() {
        return "SchedulingResult{makespan=" + makespan + ", cost=" + cost
                + ", paretoFrontSize=" + paretoFront.size()
                + ", wallClockMillis=" + wallClockMillis + "}";
    }
}
