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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 * <p>Arrival times are spread over a window sized from a fixed arrivals/second
 * rate (see {@link #ARRIVAL_RATE_PER_SECOND}) chosen to match this
 * codebase's own {@code FullDemo}/{@code Demo} convention, so scheduling
 * quality matters at every tier the same way it does in their results
 * instead of being diluted away by an overly conservative workload.
 *
 * <p>Reproducing that same contention level at 100,000 tasks (not just
 * 1,000) means {@code decode()} can end up scanning a substantial backlog
 * again, and how expensive that gets depends on exactly how each
 * algorithm's search happens to distribute load across the resource pool --
 * which isn't fully predictable in advance. {@code simulation.perAlgorithmTimeoutSeconds}
 * is the safety net for that: any single (algorithm, task count) run that
 * exceeds its budget is skipped (clearly logged, no CSV row or chart point
 * for it) rather than blocking the rest of the sweep.
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

    /**
     * Arrivals per second, held constant across every tier. Chosen to match
     * {@code FullDemo}/{@code Demo}'s own convention exactly: they spread
     * their default 1,000 tasks over a fixed 100-second window, i.e.
     * 1000/100 = 10 tasks/second -- so this class's 1,000-task tier now
     * reproduces that same arrival pattern (same rate, same resulting
     * ~100-second window) instead of inventing its own.
     *
     * <p>An earlier version of this constant targeted a fixed 20%
     * <em>utilization</em> instead of a fixed <em>rate</em>. That kept
     * {@code decode()} fast, but at that low a contention level almost no
     * task ever has to wait for a resource, so which algorithm did the
     * scheduling barely affects the result -- confirmed empirically: the
     * spread between best and worst algorithm's makespan was 2.1% at 1,000
     * tasks and only 0.04% at 100,000, versus 119.6% in this same codebase's
     * own {@code ExperimentDemo} output at 1,000 tasks. Holding rate (not
     * utilization) constant instead reproduces {@code ExperimentDemo}'s own
     * ~580%-of-capacity contention level at every tier, which is what
     * actually makes scheduling quality -- and therefore this comparison --
     * meaningful. The tradeoff: this workload is genuinely oversubscribed
     * again, which can make {@code decode()} slow at the largest tier(s);
     * {@code simulation.perAlgorithmTimeoutSeconds} (config-driven, default
     * 600 seconds) exists so that no longer means an indefinite wait.
     */
    private static final double ARRIVAL_RATE_PER_SECOND = 10.0;

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
        // NaN-filled by default so a skipped (timed-out) run leaves a gap in
        // its chart line instead of a misleading drop to zero.
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
                    configuredWoa(new WoaTaskSchedulingAlgorithm(tasks, resources), config),
                    configuredSeed(new RlGaTaskSchedulingAlgorithm(tasks, resources), config));

            for (SchedulingAlgorithm algo : algorithms) {
                makespanByAlgo.computeIfAbsent(algo.getName(), k -> nanArray(taskCounts.length));
                costByAlgo.computeIfAbsent(algo.getName(), k -> nanArray(taskCounts.length));
                energyByAlgo.computeIfAbsent(algo.getName(), k -> nanArray(taskCounts.length));
                runtimeByAlgo.computeIfAbsent(algo.getName(), k -> nanArray(taskCounts.length));

                System.out.println("  " + algo.getName() + " starting...");
                long start = System.currentTimeMillis();

                SchedulingResult result;
                long timeoutSeconds = config.getPerAlgorithmTimeoutSeconds();
                ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                    Thread th = new Thread(r, algo.getName() + "-runner");
                    th.setDaemon(true); // an abandoned (timed-out) run can't keep the JVM alive
                    return th;
                });
                try {
                    Future<SchedulingResult> future = executor.submit(algo::run);
                    result = future.get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    System.out.printf("  %-16s SKIPPED - exceeded %ds budget (still running in the "
                            + "background; no CSV row or chart point for this tier)%n",
                            algo.getName(), timeoutSeconds);
                    continue;
                } catch (ExecutionException e) {
                    System.out.println("  " + algo.getName() + " FAILED: " + e.getCause());
                    continue;
                } finally {
                    executor.shutdown();
                }

                SchedulingMetrics metrics = MetricsCalculator.compute(algo.getName(), result, resources);
                long elapsedMs = System.currentTimeMillis() - start;

                System.out.printf("  %-16s makespan=%12.2f  cost=%10.4f  wallClock=%6dms%n",
                        algo.getName(), metrics.makespan, metrics.totalCost, elapsedMs);
                csv.append(metrics.toCsvRow()).append('\n');

                makespanByAlgo.get(algo.getName())[tier] = metrics.makespan;
                costByAlgo.get(algo.getName())[tier] = metrics.totalCost;
                energyByAlgo.get(algo.getName())[tier] = metrics.energyProxy;
                runtimeByAlgo.get(algo.getName())[tier] = metrics.algorithmRuntimeMillis;
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

    private static double[] nanArray(int length) {
        double[] a = new double[length];
        Arrays.fill(a, Double.NaN);
        return a;
    }

    private static LiwsaTaskPlanningAlgorithm configuredLiwsa(LiwsaTaskPlanningAlgorithm algo, SimulationConfig config) {
        algo.setPopulationSize(config.getPopulationSize());
        algo.setGenerationCount(config.getGenerationCount());
        algo.setRandomSeed(config.getRandomSeed());
        algo.setVerboseProgress(true);
        return algo;
    }

    private static WoaTaskSchedulingAlgorithm configuredWoa(WoaTaskSchedulingAlgorithm algo, SimulationConfig config) {
        algo.setRandomSeed(config.getRandomSeed());
        algo.setVerboseProgress(true);
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
