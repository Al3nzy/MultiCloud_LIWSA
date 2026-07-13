package org.liwsa.multicloud;

import org.liwsa.multicloud.algorithm.SchedulingAlgorithm;
import org.liwsa.multicloud.algorithm.SchedulingResult;
import org.liwsa.multicloud.algorithm.baselines.RlGaTaskSchedulingAlgorithm;
import org.liwsa.multicloud.algorithm.baselines.WoaTaskSchedulingAlgorithm;
import org.liwsa.multicloud.algorithm.liwsa.LiwsaTaskMLPlanningAlgorithm;
import org.liwsa.multicloud.algorithm.liwsa.LiwsaTaskPlanningAlgorithm;
import org.liwsa.multicloud.config.ConfigLoader;
import org.liwsa.multicloud.config.SimulationConfig;
import org.liwsa.multicloud.io.TaskWorkloadReader;
import org.liwsa.multicloud.logging.ExperimentLogger;
import org.liwsa.multicloud.metrics.MetricsCalculator;
import org.liwsa.multicloud.metrics.SchedulingMetrics;
import org.liwsa.multicloud.model.CloudTask;
import org.liwsa.multicloud.model.ResourceCandidate;
import org.liwsa.multicloud.model.TaskPriority;
import org.liwsa.multicloud.viz.ChartRenderer;
import org.liwsa.multicloud.viz.GanttChartRenderer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The single "how to run everything" entry point: loads
 * {@code config.properties}, builds a workload (from
 * {@link SimulationConfig#getTaskWorkloadPath()} if set, otherwise a
 * synthetic one sized by {@code simulation.numTasks}), runs all four
 * algorithms once each, logs every scheduling decision, computes metrics
 * uniformly via {@link MetricsCalculator}, and renders comparison bar
 * charts plus a Gantt chart for the winning (LIWSA-Task) schedule.
 *
 * <p>For the full statistical (30-run) comparison see {@link ExperimentDemo};
 * for the CloudSim-simulated (not just analytic) version see {@link CloudSimDemo}.
 * This class is the one to point at when asked "how do I run this project".
 *
 * <p>Run with: {@code mvn -q exec:java -Dexec.mainClass=org.liwsa.multicloud.FullDemo}
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class FullDemo {

    private FullDemo() { }

    public static void main(String[] args) throws Exception {
        SimulationConfig config = ConfigLoader.loadDefault();
        ExperimentLogger log = new ExperimentLogger("liwsa-multicloud");
        log.attachFile(Path.of(config.getResultsOutputDir(), "run.log"));
        log.logSummary("Loaded config: " + config);

        List<CloudTask> tasks = loadOrGenerateTasks(config);
        List<ResourceCandidate> resources = buildSyntheticResources();
        log.logSummary(tasks.size() + " tasks, " + resources.size() + " resources across 3 simulated clouds.");

        List<SchedulingAlgorithm> algorithms = List.of(
                configured(new LiwsaTaskPlanningAlgorithm(tasks, resources), config),
                configured(new LiwsaTaskMLPlanningAlgorithm(tasks, resources), config),
                new WoaTaskSchedulingAlgorithm(tasks, resources),
                new RlGaTaskSchedulingAlgorithm(tasks, resources));

        List<String> names = new ArrayList<>();
        List<Double> makespans = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        List<Double> energies = new ArrayList<>();

        SchedulingResult liwsaResult = null;
        for (SchedulingAlgorithm algo : algorithms) {
            SchedulingResult result = algo.run();
            SchedulingMetrics metrics = MetricsCalculator.compute(algo.getName(), result, resources);

            for (var entry : result.taskIdToResourceIndex().entrySet()) {
                ResourceCandidate rc = resources.get(entry.getValue());
                log.logTaskScheduled(entry.getKey(), entry.getValue(), rc.getCloudId());
            }
            log.logCost(algo.getName(), metrics.totalCost);
            log.logEnergy(-1, metrics.energyProxy);
            log.logSummary(algo.getName() + ": " + metrics);

            names.add(algo.getName());
            makespans.add(metrics.makespan);
            costs.add(metrics.totalCost);
            energies.add(metrics.energyProxy);

            if (algo.getName().equals("LIWSA-Task")) {
                liwsaResult = result;
            }
        }

        Path chartsDir = Path.of(config.getResultsOutputDir(), "charts");
        ChartRenderer.renderBarChart("Makespan by algorithm", "Makespan (s)", names, makespans,
                chartsDir.resolve("makespan.png"));
        ChartRenderer.renderBarChart("Total cost by algorithm", "Cost ($)", names, costs,
                chartsDir.resolve("cost.png"));
        ChartRenderer.renderBarChart("Energy proxy by algorithm", "Energy (proxy units)", names, energies,
                chartsDir.resolve("energy.png"));
        if (liwsaResult != null) {
            GanttChartRenderer.render("LIWSA-Task execution timeline", liwsaResult, resources,
                    chartsDir.resolve("liwsa_gantt.png"));
        }
        log.logSummary("Charts written to " + chartsDir);
        log.close();

        System.out.println("Done. See " + config.getResultsOutputDir() + "/ for logs and charts.");
    }

    private static SchedulingAlgorithm configured(LiwsaTaskPlanningAlgorithm algo, SimulationConfig config) {
        algo.setPopulationSize(config.getPopulationSize());
        algo.setGenerationCount(config.getGenerationCount());
        algo.setRandomSeed(config.getRandomSeed());
        return algo;
    }

    private static List<CloudTask> loadOrGenerateTasks(SimulationConfig config) throws Exception {
        String path = config.getTaskWorkloadPath();
        if (path == null || path.isBlank()) {
            return buildSyntheticTasks(config.getNumTasks(), config.getRandomSeed());
        }
        return switch (config.getTaskWorkloadFormat().toLowerCase()) {
            case "xml" -> TaskWorkloadReader.readXml(Path.of(path));
            case "json" -> TaskWorkloadReader.readJson(Path.of(path));
            default -> TaskWorkloadReader.readCsv(Path.of(path));
        };
    }

    private static List<CloudTask> buildSyntheticTasks(int count, long seed) {
        Random rnd = new Random(seed);
        List<CloudTask> tasks = new ArrayList<>();
        TaskPriority[] priorities = TaskPriority.values();
        for (int i = 0; i < count; i++) {
            long length = 5_000 + rnd.nextInt(45_000);
            double arrival = rnd.nextInt(100);
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
