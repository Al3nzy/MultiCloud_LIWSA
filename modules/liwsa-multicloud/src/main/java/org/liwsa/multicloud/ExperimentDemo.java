package org.liwsa.multicloud;

import org.liwsa.multicloud.algorithm.SchedulingResult;
import org.liwsa.multicloud.algorithm.baselines.RlGaTaskSchedulingAlgorithm;
import org.liwsa.multicloud.algorithm.baselines.WoaTaskSchedulingAlgorithm;
import org.liwsa.multicloud.algorithm.liwsa.LiwsaTaskMLPlanningAlgorithm;
import org.liwsa.multicloud.algorithm.liwsa.LiwsaTaskPlanningAlgorithm;
import org.liwsa.multicloud.experiment.ExperimentRunner;
import org.liwsa.multicloud.experiment.ExperimentSummary;
import org.liwsa.multicloud.experiment.StatSummary;
import org.liwsa.multicloud.metrics.MetricsCalculator;
import org.liwsa.multicloud.metrics.SchedulingMetrics;
import org.liwsa.multicloud.model.CloudTask;
import org.liwsa.multicloud.model.ResourceCandidate;
import org.liwsa.multicloud.model.TaskPriority;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Runs a full {@value ExperimentRunner#DEFAULT_RUNS}-trial comparison of all
 * four algorithms on the same synthetic workload (each trial re-seeded so
 * the runs are genuinely independent), prints the mean / 95% CI / min / max
 * for the headline metrics per algorithm, and writes each algorithm's raw
 * per-run results to {@code results/<algorithm>.csv} for archiving or
 * further analysis (e.g. significance testing) outside this framework.
 *
 * <p>Uses the same in-memory {@link ResourceCandidate} workload as
 * {@link Demo} (no CloudSim simulation), since a 30-run x 4-algorithm
 * comparison is the kind of thing you want to be fast to iterate on; see
 * {@link CloudSimDemo} for the full CloudSim-backed version of a single run.
 *
 * <p>Run with: {@code mvn -q exec:java -Dexec.mainClass=org.liwsa.multicloud.ExperimentDemo}
 *
 * @author LIWSA Multi-Cloud Framework
 */
@SuppressWarnings("unused")
public final class ExperimentDemo {

    private ExperimentDemo() { }

    public static void main(String[] args) throws IOException {
        List<CloudTask> tasks = buildSyntheticTasks(1000); // was 60 and I made it 1000
        List<ResourceCandidate> resources = buildSyntheticResources();
        ExperimentRunner runner = new ExperimentRunner(); // 30 runs

        ExperimentSummary liwsa = runner.run("LIWSA-Task", i -> {
            LiwsaTaskPlanningAlgorithm algo = new LiwsaTaskPlanningAlgorithm(tasks, resources);
            algo.setRandomSeed(1000L + i);
            SchedulingResult result = algo.run();
            return MetricsCalculator.compute("LIWSA-Task", result, resources);
        });

        ExperimentSummary liwsaMl = runner.run("LIWSA-Task-ML", i -> {
            LiwsaTaskMLPlanningAlgorithm algo = new LiwsaTaskMLPlanningAlgorithm(tasks, resources);
            algo.setRandomSeed(2000L + i);
            SchedulingResult result = algo.run();
            return MetricsCalculator.compute("LIWSA-Task-ML", result, resources);
        });

        ExperimentSummary woa = runner.run("WOA", i -> {
            WoaTaskSchedulingAlgorithm algo = new WoaTaskSchedulingAlgorithm(tasks, resources);
            algo.setRandomSeed(3000L + i);
            SchedulingResult result = algo.run();
            return MetricsCalculator.compute("WOA", result, resources);
        });

        ExperimentSummary rlGa = runner.run("RL-GA-lite", i -> {
            RlGaTaskSchedulingAlgorithm algo = new RlGaTaskSchedulingAlgorithm(tasks, resources);
            algo.setRandomSeed(4000L + i);
            SchedulingResult result = algo.run();
            return MetricsCalculator.compute("RL-GA-lite", result, resources);
        });

        List<ExperimentSummary> all = List.of(liwsa, liwsaMl, woa, rlGa);

        System.out.println("=== " + runner.getNumRuns() + "-run comparison (" + tasks.size()
                + " tasks, " + resources.size() + " resources across 3 clouds) ===");
        for (ExperimentSummary summary : all) {
            System.out.println();
            System.out.println("--- " + summary.getAlgorithmName() + " ---");
            for (StatSummary stat : summary.summarizeHeadlineMetrics()) {
                System.out.println("  " + stat);
            }
        }

        Files.createDirectories(Path.of("results"));
        for (ExperimentSummary summary : all) {
            Path path = Path.of("results", summary.getAlgorithmName() + ".csv");
            Files.writeString(path, summary.toCsv());
            System.out.println("Wrote " + path);
        }
    }

    private static List<CloudTask> buildSyntheticTasks(int count) {
        Random rnd = new Random(1);
        List<CloudTask> tasks = new ArrayList<>();
        TaskPriority[] priorities = TaskPriority.values();
        for (int i = 0; i < count; i++) {
            long length = 5_000 + rnd.nextInt(45_000);
            double arrival = rnd.nextInt(100);
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
        double[] cloudMipsScale = {1.0, 1.3, 0.85};
        double[] cloudPriceScale = {1.0, 1.15, 0.9};
        String[] cloudNames = {"cloud-A", "cloud-B", "cloud-C"};

        List<ResourceCandidate> resources = new ArrayList<>();
        int index = 0;
        double[] baseMips = {1000, 2000, 4000, 8000};
        long[] baseRam = {2048, 4096, 8192, 16384};
        long[] baseBw = {100, 250, 500, 1000};
        long[] baseStorage = {20_000, 40_000, 80_000, 160_000};
        double[] baseCostPerSecond = {0.00028, 0.00056, 0.00111, 0.00222};

        for (int c = 0; c < cloudNames.length; c++) {
            for (int t = 0; t < baseMips.length; t++) {
                resources.add(new ResourceCandidate(
                        index++, c, cloudNames[c],
                        baseMips[t] * cloudMipsScale[c], 1, baseRam[t], baseBw[t], baseStorage[t],
                        baseCostPerSecond[t] * cloudPriceScale[c]));
            }
        }
        return resources;
    }
}
