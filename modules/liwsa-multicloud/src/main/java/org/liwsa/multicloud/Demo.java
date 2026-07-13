package org.liwsa.multicloud;

import org.liwsa.multicloud.algorithm.SchedulingResult;
import org.liwsa.multicloud.algorithm.baselines.RlGaTaskSchedulingAlgorithm;
import org.liwsa.multicloud.algorithm.baselines.WoaTaskSchedulingAlgorithm;
import org.liwsa.multicloud.algorithm.liwsa.LiwsaTaskMLPlanningAlgorithm;
import org.liwsa.multicloud.algorithm.liwsa.LiwsaTaskPlanningAlgorithm;
import org.liwsa.multicloud.model.CloudTask;
import org.liwsa.multicloud.model.ResourceCandidate;
import org.liwsa.multicloud.model.TaskPriority;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Standalone smoke test: builds a synthetic independent-task, multi-cloud
 * workload entirely in memory (no CloudSim {@code Datacenter}/broker/
 * simulation clock involved) and runs all four scheduling algorithms
 * against it, printing (makespan, cost, wall-clock) for each.
 *
 * <p>This exists purely so the algorithm layer can be exercised and
 * sanity-checked the moment it compiles, ahead of the broker/provisioning
 * layer (which turns a {@link SchedulingResult} into actual CloudSim
 * {@code Vm}/{@code Cloudlet} bindings) being built in a later phase.
 *
 * <p>Run with: {@code mvn -q exec:java -Dexec.mainClass=org.liwsa.multicloud.Demo}
 * (or just run the {@code main} method from your IDE).
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class Demo {

    private Demo() { }

    public static void main(String[] args) {
        List<CloudTask> tasks = buildSyntheticTasks(60);
        List<ResourceCandidate> resources = buildSyntheticResources();

        System.out.println("Synthetic workload: " + tasks.size() + " independent tasks, "
                + resources.size() + " resources across 3 simulated clouds.");
        System.out.println();

        runLiwsaTask(tasks, resources);
        runLiwsaTaskMl(tasks, resources);
        runWoa(tasks, resources);
        runRlGa(tasks, resources);
    }

    private static void runLiwsaTask(List<CloudTask> tasks, List<ResourceCandidate> resources) {
        LiwsaTaskPlanningAlgorithm algo = new LiwsaTaskPlanningAlgorithm(tasks, resources);
        algo.setRandomSeed(42);
        SchedulingResult result = algo.run();
        report("LIWSA-Task (no ML)", result);
    }

    private static void runLiwsaTaskMl(List<CloudTask> tasks, List<ResourceCandidate> resources) {
        LiwsaTaskMLPlanningAlgorithm algo = new LiwsaTaskMLPlanningAlgorithm(tasks, resources);
        algo.setRandomSeed(42);
        SchedulingResult result = algo.run();
        report("LIWSA-Task-ML (light OLS warm-start)", result);
    }

    private static void runWoa(List<CloudTask> tasks, List<ResourceCandidate> resources) {
        WoaTaskSchedulingAlgorithm algo = new WoaTaskSchedulingAlgorithm(tasks, resources);
        algo.setRandomSeed(42);
        SchedulingResult result = algo.run();
        report("WOA baseline (non-ML)", result);
    }

    private static void runRlGa(List<CloudTask> tasks, List<ResourceCandidate> resources) {
        RlGaTaskSchedulingAlgorithm algo = new RlGaTaskSchedulingAlgorithm(tasks, resources);
        algo.setRandomSeed(42);
        SchedulingResult result = algo.run();
        report("RL-GA-lite baseline (ML-hybrid)", result);
    }

    private static void report(String label, SchedulingResult result) {
        System.out.printf("%-38s makespan=%10.2f  cost=%10.2f  wallClockMs=%d%n",
                label, result.getMakespan(), result.getCost(), result.getWallClockMillis());
    }

    // ---------------------------------------------------------------
    // Synthetic data generators
    // ---------------------------------------------------------------
    private static List<CloudTask> buildSyntheticTasks(int count) {
        Random rnd = new Random(1);
        List<CloudTask> tasks = new ArrayList<>();
        TaskPriority[] priorities = TaskPriority.values();
        for (int i = 0; i < count; i++) {
            long length = 5_000 + rnd.nextInt(45_000); // MI
            double arrival = rnd.nextInt(100); // seconds
            boolean hasDeadline = rnd.nextDouble() < 0.4;
            CloudTask.Builder b = new CloudTask.Builder(i, length)
                    .pes(1)
                    .priority(priorities[rnd.nextInt(priorities.length)])
                    .arrivalTime(arrival)
                    .memoryRequirementMb(256L + rnd.nextInt(1792))
                    .bandwidthRequirementMbps(5L + rnd.nextInt(95));
            if (hasDeadline) {
                b.deadline(arrival + 200 + rnd.nextInt(400));
            }
            tasks.add(b.build());
        }
        return tasks;
    }

    private static List<ResourceCandidate> buildSyntheticResources() {
        // Three simulated clouds, each with a small heterogeneous VM pool
        // and its own per-second pricing -- stand-ins for the real
        // AWS/Azure/GCP-grounded pricing/instance catalog to be added in
        // the broker/provisioning phase.
        double[] cloudMipsScale = {1.0, 1.3, 0.85};
        double[] cloudPriceScale = {1.0, 1.15, 0.9};
        String[] cloudNames = {"cloud-A", "cloud-B", "cloud-C"};

        List<ResourceCandidate> resources = new ArrayList<>();
        int index = 0;
        double[] baseMips = {1000, 2000, 4000, 8000};
        long[] baseRam = {2048, 4096, 8192, 16384};
        long[] baseBw = {100, 250, 500, 1000};
        long[] baseStorage = {20_000, 40_000, 80_000, 160_000};
        double[] baseCostPerSecond = {0.00028, 0.00056, 0.00111, 0.00222}; // roughly $1/$2/$4/$8 per hour

        for (int c = 0; c < cloudNames.length; c++) {
            for (int t = 0; t < baseMips.length; t++) {
                resources.add(new ResourceCandidate(
                        index++,
                        c,
                        cloudNames[c],
                        baseMips[t] * cloudMipsScale[c],
                        1,
                        baseRam[t],
                        baseBw[t],
                        baseStorage[t],
                        baseCostPerSecond[t] * cloudPriceScale[c]));
            }
        }
        return resources;
    }
}
