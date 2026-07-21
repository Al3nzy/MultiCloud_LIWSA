package org.liwsa.multicloud;

import org.liwsa.multicloud.algorithm.SchedulingAlgorithm;
import org.liwsa.multicloud.algorithm.SchedulingResult;
import org.liwsa.multicloud.algorithm.baselines.RlGaTaskSchedulingAlgorithm;
import org.liwsa.multicloud.algorithm.baselines.WoaTaskSchedulingAlgorithm;
import org.liwsa.multicloud.algorithm.liwsa.LiwsaTaskMLPlanningAlgorithm;
import org.liwsa.multicloud.algorithm.liwsa.LiwsaTaskPlanningAlgorithm;
import org.liwsa.multicloud.config.ConfigLoader;
import org.liwsa.multicloud.config.SimulationConfig;
import org.liwsa.multicloud.experiment.ExperimentRunner;
import org.liwsa.multicloud.metrics.MetricsCalculator;
import org.liwsa.multicloud.metrics.SchedulingMetrics;
import org.liwsa.multicloud.model.CloudTask;
import org.liwsa.multicloud.model.ResourceCandidate;
import org.liwsa.multicloud.model.TaskPriority;
import org.liwsa.multicloud.viz.ChartRenderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Low-load-to-high-load scalability sweep: runs all four algorithms once
 * each at every task count in {@link SimulationConfig#getTaskCountSweep()}
 * (default 100 / 1,000 / 10,000 / 100,000) against the same fixed 12-VM,
 * 3-cloud resource pool as {@link FullDemo}, and appends every run's
 * {@link SchedulingMetrics} to one combined CSV, and renders four
 * "metric vs. task count" line charts (makespan, cost, energy proxy, and
 * the algorithm's own wall-clock runtime -- one line per algorithm each).
 * One execution, one CSV, four PNGs, the whole low-to-high picture -- each
 * CSV row already carries {@code numTasks} (see
 * {@link SchedulingMetrics#csvHeader()}), so the CSV can also be pivoted or
 * re-plotted independently if you want a different metric or a custom look.
 *
 * <p><b>Two small algorithm-level fixes were needed first</b> (both applied
 * in {@code LiwsaTaskPlanningAlgorithm} / {@code LiwsaTaskMLPlanningAlgorithm}
 * themselves, not here, so every other entry point benefits too):
 * {@code solitaryMove()} was recomputing the same per-individual Hamming
 * distance and vote weight once per gene instead of once per call -- an
 * accidental extra factor of {@code numTasks}, i.e. O(numTasks^2 *
 * populationSize) instead of O(numTasks * populationSize) -- and
 * {@code LiwsaTaskMLPlanningAlgorithm}'s OLS warm-start trained on
 * {@code numTrainingSamples * numTasks} rows with no ceiling, which at
 * 100,000 tasks is tens of millions of rows. Both are invisible at
 * ~1,000 tasks but make 10,000-100,000 impractical (or an outright
 * OutOfMemoryError) without them. Neither changes any algorithm's output
 * for a given seed, only its speed/memory footprint.
 *
 * <p>Arrival times are spread over a window that grows with task count
 * (a floor of 100s, then roughly {@value #ARRIVAL_RATE_PER_SECOND}
 * arrivals/second beyond that) rather than the fixed 100-second window
 * {@link FullDemo}/{@link Demo} use. Holding the window fixed while task
 * count climbs into the tens of thousands means more and more tasks land in
 * the same short interval, which both inflates per-resource contention in
 * the decoder and stops "task count" from being the only thing that varies
 * between tiers. At 100 and 1,000 tasks this produces the same ~100s window
 * those other demos use, so results at this framework's original scale are
 * unaffected.
 *
 * <p>Each task count runs once per algorithm (not wrapped in
 * {@link ExperimentRunner}'s 30-repeat statistical loop) so the largest
 * tier stays a matter of minutes rather than hours; wrap the body of the
 * inner loop in {@code ExperimentRunner} yourself if you want repeats too.
 *
 * <p>Run with: {@code mvn -q -pl modules/liwsa-multicloud exec:java -Dexec.mainClass=org.liwsa.multicloud.ScalabilityDemo}
 * <br>If the largest configured tier runs out of heap, increase it, e.g.
 * {@code MAVEN_OPTS=-Xmx4g mvn ...}.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class ScalabilityDemo {

    /** Target arrival rate once the window grows past its 100s floor (see class Javadoc). */
    private static final double ARRIVAL_RATE_PER_SECOND = 50.0;

    private ScalabilityDemo() { }

    public static void main(String[] args) throws Exception {
        SimulationConfig config = ConfigLoader.loadDefault();
        int[] taskCounts = config.getTaskCountSweep().clone();
        Arrays.sort(taskCounts);

        List<ResourceCandidate> resources = buildSyntheticResources();
        Path outPath = Path.of(config.getResultsOutputDir(), "scalability-sweep.csv");
        if (outPath.getParent() != null) {
            Files.createDirectories(outPath.getParent());
        }

        StringBuilder csv = new StringBuilder();
        csv.append(SchedulingMetrics.csvHeader()).append('\n');

        // Per-algorithm series, one entry per tier, for the scaling charts below.
        // LinkedHashMap so chart legends list algorithms in first-seen order
        // (which is the same fixed order every tier, since the same four
        // algorithm objects are (re)created in the same order each time).
        Map<String, double[]> makespanByAlgo = new LinkedHashMap<>();
        Map<String, double[]> costByAlgo = new LinkedHashMap<>();
        Map<String, double[]> energyByAlgo = new LinkedHashMap<>();
        Map<String, double[]> runtimeByAlgo = new LinkedHashMap<>();

        System.out.println("Scalability sweep: " + Arrays.toString(taskCounts)
                + " tasks, " + resources.size() + " resources across 3 simulated clouds.");

        for (int tier = 0; tier < taskCounts.length; tier++) {
            int numTasks = taskCounts[tier];
            List<CloudTask> tasks = buildSyntheticTasks(numTasks, config.getRandomSeed());
            System.out.println();
            System.out.println("=== " + numTasks + " tasks ===");

            List<SchedulingAlgorithm> algorithms = List.of(
                    configuredLiwsa(new LiwsaTaskPlanningAlgorithm(tasks, resources), config),
                    configuredLiwsa(new LiwsaTaskMLPlanningAlgorithm(tasks, resources), config),
                    configuredSeed(new WoaTaskSchedulingAlgorithm(tasks, resources), config),
                    configuredSeed(new RlGaTaskSchedulingAlgorithm(tasks, resources), config));

            for (SchedulingAlgorithm algo : algorithms) {
                long start = System.currentTimeMillis();
                SchedulingResult result = algo.run();
                SchedulingMetrics metrics = MetricsCalculator.compute(algo.getName(), result, resources);
                long elapsedMs = System.currentTimeMillis() - start;

                System.out.printf("  %-16s makespan=%12.2f  cost=%10.4f  wallClock=%6dms%n",
                        algo.getName(), metrics.makespan, metrics.totalCost, elapsedMs);
                csv.append(metrics.toCsvRow()).append('\n');

                makespanByAlgo.computeIfAbsent(algo.getName(), k -> new double[taskCounts.length])[tier] = metrics.makespan;
                costByAlgo.computeIfAbsent(algo.getName(), k -> new double[taskCounts.length])[tier] = metrics.totalCost;
                energyByAlgo.computeIfAbsent(algo.getName(), k -> new double[taskCounts.length])[tier] = metrics.energyProxy;
                runtimeByAlgo.computeIfAbsent(algo.getName(), k -> new double[taskCounts.length])[tier] = metrics.algorithmRuntimeMillis;
            }
        }

        Files.writeString(outPath, csv.toString());
        System.out.println();
        System.out.println("Wrote combined low-to-high-load results to " + outPath);

        renderScalabilityCharts(config, taskCounts, makespanByAlgo, costByAlgo, energyByAlgo, runtimeByAlgo);
    }

    private static void renderScalabilityCharts(SimulationConfig config, int[] taskCounts,
            Map<String, double[]> makespanByAlgo, Map<String, double[]> costByAlgo,
            Map<String, double[]> energyByAlgo, Map<String, double[]> runtimeByAlgo) throws IOException {
        Path chartsDir = Path.of(config.getResultsOutputDir(), "charts");
        List<String> names = new ArrayList<>(makespanByAlgo.keySet());

        ChartRenderer.renderLineChart("Makespan vs. task count", "Task count (log scale)", "Makespan (s)",
                taskCounts, names, new ArrayList<>(makespanByAlgo.values()), true,
                chartsDir.resolve("scalability-makespan.png"));
        ChartRenderer.renderLineChart("Total cost vs. task count", "Task count (log scale)", "Cost ($)",
                taskCounts, names, new ArrayList<>(costByAlgo.values()), true,
                chartsDir.resolve("scalability-cost.png"));
        ChartRenderer.renderLineChart("Energy proxy vs. task count", "Task count (log scale)", "Energy (proxy units)",
                taskCounts, names, new ArrayList<>(energyByAlgo.values()), true,
                chartsDir.resolve("scalability-energy.png"));
        ChartRenderer.renderLineChart("Algorithm runtime vs. task count", "Task count (log scale)", "Wall-clock runtime (ms)",
                taskCounts, names, new ArrayList<>(runtimeByAlgo.values()), true,
                chartsDir.resolve("scalability-runtime.png"));

        System.out.println("Wrote scaling charts to " + chartsDir);
    }

    private static LiwsaTaskPlanningAlgorithm configuredLiwsa(LiwsaTaskPlanningAlgorithm algo, SimulationConfig config) {
        algo.setPopulationSize(config.getPopulationSize());
        algo.setGenerationCount(config.getGenerationCount());
        algo.setRandomSeed(config.getRandomSeed());
        return algo;
    }

    private static WoaTaskSchedulingAlgorithm configuredSeed(WoaTaskSchedulingAlgorithm algo, SimulationConfig config) {
        algo.setRandomSeed(config.getRandomSeed());
        return algo;
    }

    private static RlGaTaskSchedulingAlgorithm configuredSeed(RlGaTaskSchedulingAlgorithm algo, SimulationConfig config) {
        algo.setRandomSeed(config.getRandomSeed());
        return algo;
    }

    // ---------------------------------------------------------------------
    // Synthetic data generators.
    // buildSyntheticResources() is identical to FullDemo/Demo's, kept fixed
    // across every tier so task count is the only thing that varies.
    // buildSyntheticTasks() is the same style as FullDemo's, with the
    // arrival-window change described in the class Javadoc.
    // ---------------------------------------------------------------------
    private static List<CloudTask> buildSyntheticTasks(int count, long seed) {
        Random rnd = new Random(seed);
        List<CloudTask> tasks = new ArrayList<>();
        TaskPriority[] priorities = TaskPriority.values();
        double arrivalWindowSeconds = Math.max(100.0, count / ARRIVAL_RATE_PER_SECOND);

        for (int i = 0; i < count; i++) {
            long length = 5_000 + rnd.nextInt(45_000);
            double arrival = rnd.nextDouble() * arrivalWindowSeconds;
            CloudTask.Builder b = new CloudTask.Builder(i, length)
                    .pes(1)
                    .priority(priorities[rnd.nextInt(priorities.length)])
                    .arrivalTime(arrival)
                    .memoryRequirementMb(256L + rnd.nextInt(1792))
                    .bandwidthRequirementMbps(5L + rnd.nextInt(95));
            if (rnd.nextDouble() < 0.4) {
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
        double[] baseMips = {1000, 2000, 4000, 8000};
        long[] baseRam = {2048, 4096, 8192, 16384};
        long[] baseBw = {100, 250, 500, 1000};
        long[] baseStorage = {20_000, 40_000, 80_000, 160_000};
        double[] baseCostPerSecond = {0.00028, 0.00056, 0.00111, 0.00222};

        List<ResourceCandidate> resources = new ArrayList<>();
        int index = 0;
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
