package org.liwsa.multicloud.algorithm.liwsa;

import org.liwsa.multicloud.algorithm.SchedulingAlgorithm;
import org.liwsa.multicloud.algorithm.SchedulingResult;
import org.liwsa.multicloud.model.CloudTask;
import org.liwsa.multicloud.model.ResourceCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * LIWSA-Task: a density-adaptive, Pareto multi-objective, locust-swarm-inspired
 * scheduler for independent tasks across multiple cloud providers.
 *
 * <p>This is a direct re-target of the original workflow-scheduling LIWSA
 * algorithm (density-driven solitary/gregarious phase switching over a
 * Hamming-distance neighbourhood, as in desert-locust phase polyphenism) at
 * <b>independent, multi-cloud task scheduling</b> instead of DAG workflow
 * scheduling. Every genetic/behavioural operator below (Pareto dominance,
 * self-calibrated crowding density, the phase-mixing probability, the
 * solitary voting move, the social roulette-and-copy move, mutation, and the
 * "child replaces parent unless parent strictly dominates it" acceptance
 * rule) is numerically identical to the original workflow algorithm. The
 * only thing that changed is the decoder:
 *
 * <ul>
 *   <li>the genotype's alphabet is a flat, multi-cloud
 *       {@code List<ResourceCandidate>} instead of a single cloud's VM list,
 *       so one search simultaneously performs task, VM and cloud-provider
 *       selection;</li>
 *   <li>a task's ready time is {@code max(arrivalTime, dependency finish
 *       times)} instead of {@code max(parent finish time + transfer cost)}
 *       &mdash; independent (bag-of-tasks) workloads have no dependencies,
 *       so this reduces to simply {@code arrivalTime}. Optional dependencies
 *       are still supported for workloads that are not pure bags-of-tasks,
 *       but there is no data-transfer-cost term, since that is a workflow
 *       artefact tied to matching parent-output/child-input files.</li>
 * </ul>
 *
 * @author LIWSA Multi-Cloud Framework
 */
public class LiwsaTaskPlanningAlgorithm implements SchedulingAlgorithm {

    public static int DEFAULT_POPULATION_SIZE = 30;
    public static int DEFAULT_GENERATION_COUNT = 100;

    // Locust-analogy hyperparameters (identical defaults to the original workflow LIWSA)
    protected double lambdaMix = 0.5;
    protected double mutationRate = 0.02;
    protected double blendProbability = 0.5;
    protected double copyAlpha = 1.2;
    protected int minEliteSize = 3;
    protected double kernelF = 3.0;
    protected double kernelL = 0.3;

    protected int populationSize = DEFAULT_POPULATION_SIZE;
    protected int generationCount = DEFAULT_GENERATION_COUNT;
    protected Long randomSeed;
    protected Random random;

    protected final List<CloudTask> tasksInput;
    protected final List<ResourceCandidate> resources;

    protected List<CloudTask> taskOrder;
    protected List<int[]> population;
    protected double[] makespans;
    protected double[] costs;

    private List<int[]> externalSeedGenotypes;

    /** Snapshot of the last completed run, mirroring the original algorithm's LastRunMetrics. */
    public static final class LastRunMetrics {
        public double chosenMakespan;
        public double chosenCost;
        public int paretoFrontSize;
        public List<double[]> paretoFrontPoints;
        public long searchWallClockMillis;
        public int populationSizeUsed;
        public int generationCountUsed;
    }

    public LastRunMetrics lastRun;

    public LiwsaTaskPlanningAlgorithm(List<CloudTask> tasks, List<ResourceCandidate> resources) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("LiwsaTaskPlanningAlgorithm requires at least one task");
        }
        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException("LiwsaTaskPlanningAlgorithm requires at least one resource candidate");
        }
        this.tasksInput = tasks;
        this.resources = resources;
    }

    public void setPopulationSize(int populationSize) { this.populationSize = populationSize; }
    public void setGenerationCount(int generationCount) { this.generationCount = generationCount; }
    public void setRandomSeed(long seed) { this.randomSeed = seed; }
    public void setLambdaMix(double lambdaMix) { this.lambdaMix = lambdaMix; }
    public void setMutationRate(double mutationRate) { this.mutationRate = mutationRate; }
    public void setSeedGenotypes(List<int[]> seeds) { this.externalSeedGenotypes = seeds; }

    /**
     * Off by default (so existing entry points' console output is unchanged).
     * When true, prints one line every {@link #progressIntervalGenerations}
     * generations -- e.g. "[LIWSA-Task] generation 20/100 elapsed=48.2s" --
     * so a long run at a large task count has a visible heartbeat instead of
     * going silent until it's entirely done. {@link ScalabilityDemo} turns
     * this on for exactly that reason.
     */
    protected boolean verboseProgress = false;
    protected int progressIntervalGenerations = 5;
    public void setVerboseProgress(boolean v) { this.verboseProgress = v; }

    /**
     * Cached once per {@link #run()} (see where {@link #taskOrder} is set)
     * rather than rechecked per task: whether any task in this workload
     * declares a dependency at all. {@link #decode} uses this to skip
     * building its {@code finish}-time lookup map entirely when nothing
     * would ever read from it -- see {@link #decode} for why that map is
     * otherwise a meaningful amount of wasted allocation on dependency-free
     * workloads (which is every synthetic workload in this framework;
     * dependencies only come from {@code TaskWorkloadReader}-loaded files).
     */
    private boolean hasAnyDependencies;

    @Override
    public String getName() {
        return "LIWSA-Task";
    }

    // ---------------------------------------------------------------
    // Main search loop (numerically identical to the workflow LIWSA)
    // ---------------------------------------------------------------
    public SchedulingResult run() {
        long searchStartMillis = System.currentTimeMillis();

        random = (randomSeed != null) ? new Random(randomSeed) : new Random();
        taskOrder = computeTaskOrder(tasksInput);
        hasAnyDependencies = taskOrder.stream().anyMatch(t -> !t.isIndependent());

        initializePopulation();
        double tau = calibrateTau();

        if (verboseProgress) {
            System.out.printf("    [%s] setup done (%d tasks, %d resources), starting search: elapsed=%.1fs%n",
                    getName(), taskOrder.size(), resources.size(), (System.currentTimeMillis() - searchStartMillis) / 1000.0);
        }

        for (int gen = 0; gen < generationCount; gen++) {
            if (verboseProgress && gen % progressIntervalGenerations == 0) {
                System.out.printf("    [%s] generation %d/%d  elapsed=%.1fs%n",
                        getName(), gen, generationCount, (System.currentTimeMillis() - searchStartMillis) / 1000.0);
            }
            int[] frontNumber = new int[populationSize];
            List<List<Integer>> fronts = nonDominatedSort(frontNumber);

            List<Integer> elite = new ArrayList<>(fronts.get(0));
            int idx = 1;
            while (elite.size() < minEliteSize && idx < fronts.size()) {
                elite.addAll(fronts.get(idx));
                idx++;
            }

            int bestIndex = bestOf(fronts.get(0));

            for (int i = 0; i < populationSize; i++) {
                if (i == bestIndex) {
                    continue;
                }
                double density = localDensity(i, tau);
                double pSocial = (1 - lambdaMix) * ((double) gen / Math.max(generationCount, 1))
                        + lambdaMix * density;

                int[] child = (random.nextDouble() > pSocial)
                        ? solitaryMove(i, frontNumber)
                        : socialMove(i, frontNumber, elite);
                mutate(child);

                double[] mc = decode(child);
                if (!dominates(makespans[i], costs[i], mc[0], mc[1])) {
                    population.set(i, child);
                    makespans[i] = mc[0];
                    costs[i] = mc[1];
                }
            }
        }

        int[] finalFrontNumber = new int[populationSize];
        List<List<Integer>> finalFronts = nonDominatedSort(finalFrontNumber);
        int chosen = bestOf(finalFronts.get(0));

        LastRunMetrics metrics = new LastRunMetrics();
        metrics.chosenMakespan = makespans[chosen];
        metrics.chosenCost = costs[chosen];
        metrics.paretoFrontSize = finalFronts.get(0).size();
        metrics.paretoFrontPoints = new ArrayList<>();
        for (int i : finalFronts.get(0)) {
            metrics.paretoFrontPoints.add(new double[]{makespans[i], costs[i]});
        }
        metrics.searchWallClockMillis = System.currentTimeMillis() - searchStartMillis;
        metrics.populationSizeUsed = populationSize;
        metrics.generationCountUsed = generationCount;
        lastRun = metrics;

        double[] finishTimes = decodeFinishTimes(population.get(chosen));
        return new SchedulingResult(taskOrder, population.get(chosen).clone(), finishTimes,
                makespans[chosen], costs[chosen], metrics.paretoFrontPoints,
                metrics.searchWallClockMillis);
    }

    private int bestOf(List<Integer> indices) {
        int best = indices.get(0);
        for (int i : indices) {
            if (makespans[i] < makespans[best]
                    || (makespans[i] == makespans[best] && costs[i] < costs[best])) {
                best = i;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------
    // Task ordering: topological w.r.t. optional dependencies, tie-broken
    // by arrival time then id. Degrades to a plain arrival/id sort for pure
    // bag-of-tasks workloads (the common case), since there are no edges.
    // ---------------------------------------------------------------
    private List<CloudTask> computeTaskOrder(List<CloudTask> tasks) {
        Map<Integer, CloudTask> byId = new HashMap<>();
        for (CloudTask t : tasks) {
            byId.put(t.getCloudletId(), t);
        }
        Map<Integer, Integer> indegree = new HashMap<>();
        Map<Integer, List<Integer>> children = new HashMap<>();
        for (CloudTask t : tasks) {
            indegree.putIfAbsent(t.getCloudletId(), 0);
            children.putIfAbsent(t.getCloudletId(), new ArrayList<>());
        }
        for (CloudTask t : tasks) {
            for (Integer depId : t.getDependencies()) {
                if (byId.containsKey(depId)) {
                    indegree.put(t.getCloudletId(), indegree.get(t.getCloudletId()) + 1);
                    children.get(depId).add(t.getCloudletId());
                }
            }
        }

        Comparator<CloudTask> readyOrder = Comparator
                .comparingDouble(CloudTask::getArrivalTime)
                .thenComparingInt(CloudTask::getCloudletId);
        PriorityQueue<CloudTask> ready = new PriorityQueue<>(readyOrder);
        for (CloudTask t : tasks) {
            if (indegree.get(t.getCloudletId()) == 0) {
                ready.add(t);
            }
        }

        List<CloudTask> order = new ArrayList<>();
        Map<Integer, Integer> remaining = new HashMap<>(indegree);
        while (!ready.isEmpty()) {
            CloudTask t = ready.poll();
            order.add(t);
            for (Integer childId : children.get(t.getCloudletId())) {
                int d = remaining.get(childId) - 1;
                remaining.put(childId, d);
                if (d == 0) {
                    ready.add(byId.get(childId));
                }
            }
        }

        if (order.size() != tasks.size()) {
            // A dependency cycle was supplied; fall back to a valid, if
            // dependency-agnostic, order rather than failing the whole run.
            order = new ArrayList<>(tasks);
            order.sort(readyOrder);
        }
        return order;
    }

    // ---------------------------------------------------------------
    // Decoder: genotype -> (makespan, cost), feasible by construction
    // (dependency-respecting order + per-resource insertion scheduling)
    // ---------------------------------------------------------------
    private static final class Event {
        double start;
        double finish;
        Event(double start, double finish) { this.start = start; this.finish = finish; }
    }

    private double findFinishTime(List<Event> sched, double readyTime, double duration, boolean occupySlot) {
        if (sched.isEmpty()) {
            if (occupySlot) {
                sched.add(new Event(readyTime, readyTime + duration));
            }
            return readyTime + duration;
        }

        if (sched.size() == 1) {
            double start;
            int pos;
            if (readyTime >= sched.get(0).finish) {
                pos = 1;
                start = readyTime;
            } else if (readyTime + duration <= sched.get(0).start) {
                pos = 0;
                start = readyTime;
            } else {
                pos = 1;
                start = sched.get(0).finish;
            }
            if (occupySlot) {
                sched.add(pos, new Event(start, start + duration));
            }
            return start + duration;
        }

        double start = Math.max(readyTime, sched.get(sched.size() - 1).finish);
        double finish = start + duration;
        int pos = sched.size();
        int i = sched.size() - 1;
        int j = sched.size() - 2;
        while (j >= 0) {
            Event current = sched.get(i);
            Event previous = sched.get(j);
            if (readyTime > previous.finish) {
                if (readyTime + duration <= current.start) {
                    start = readyTime;
                    finish = readyTime + duration;
                }
                break;
            }
            if (previous.finish + duration <= current.start) {
                start = previous.finish;
                finish = previous.finish + duration;
                pos = i;
            }
            i--;
            j--;
        }

        if (readyTime + duration <= sched.get(0).start) {
            pos = 0;
            start = readyTime;
            if (occupySlot) {
                sched.add(pos, new Event(start, start + duration));
            }
            return start + duration;
        }

        if (occupySlot) {
            sched.add(pos, new Event(start, finish));
        }
        return finish;
    }

    protected double[] decode(int[] genotype) {
        Map<Integer, List<Event>> schedules = new HashMap<>();
        // Only ever actually populated when hasAnyDependencies is true --
        // building and filling this with one entry per task (up to
        // numTasks entries) is real, measurable allocation/GC overhead that
        // has zero payoff on a dependency-free workload, since nothing
        // would ever read from it. See the field's own Javadoc.
        Map<Integer, Double> finish = hasAnyDependencies ? new HashMap<>() : null;
        double cost = 0.0;
        double makespan = 0.0;

        for (int k = 0; k < taskOrder.size(); k++) {
            CloudTask task = taskOrder.get(k);
            ResourceCandidate resource = resources.get(genotype[k]);

            double ready = task.getArrivalTime();
            if (hasAnyDependencies) {
                for (Integer depId : task.getDependencies()) {
                    Double pf = finish.get(depId);
                    if (pf != null) {
                        ready = Math.max(ready, pf);
                    }
                }
            }

            double duration = task.getCloudletLength() / Math.max(resource.getMips(), 1e-6);
            List<Event> sched = schedules.computeIfAbsent(genotype[k], key -> new ArrayList<>());
            double fin = findFinishTime(sched, ready, duration, true);

            if (hasAnyDependencies) {
                finish.put(task.getCloudletId(), fin);
            }
            makespan = Math.max(makespan, fin);
            cost += duration * resource.getCostPerSecond();
        }

        return new double[]{makespan, cost};
    }

    /**
     * Re-runs the decode for {@code genotype}, this time also recording each
     * task's own finish time. Only ever called once, on the final chosen
     * genotype after the search loop ends (see {@link #run()}), so it does
     * not affect the per-generation cost of {@link #decode}.
     */
    protected double[] decodeFinishTimes(int[] genotype) {
        Map<Integer, List<Event>> schedules = new HashMap<>();
        Map<Integer, Double> finish = new HashMap<>();
        double[] finishByOrder = new double[taskOrder.size()];

        for (int k = 0; k < taskOrder.size(); k++) {
            CloudTask task = taskOrder.get(k);
            ResourceCandidate resource = resources.get(genotype[k]);

            double ready = task.getArrivalTime();
            for (Integer depId : task.getDependencies()) {
                Double pf = finish.get(depId);
                if (pf != null) {
                    ready = Math.max(ready, pf);
                }
            }

            double duration = task.getCloudletLength() / Math.max(resource.getMips(), 1e-6);
            List<Event> sched = schedules.computeIfAbsent(genotype[k], key -> new ArrayList<>());
            double fin = findFinishTime(sched, ready, duration, true);

            finish.put(task.getCloudletId(), fin);
            finishByOrder[k] = fin;
        }
        return finishByOrder;
    }

    // ---------------------------------------------------------------
    // Population, Pareto ranking, density, and the two locust-derived
    // movement operators -- identical to the workflow LIWSA algorithm.
    // ---------------------------------------------------------------

    /**
     * Hook for subclasses (see {@code LiwsaTaskMLPlanningAlgorithm}) to
     * inject warm-start genotypes ahead of the randomly-generated rest of
     * the initial population.
     */
    protected List<int[]> generateSeedGenotypes() {
        return (externalSeedGenotypes != null) ? new ArrayList<>(externalSeedGenotypes) : new ArrayList<>();
    }

    protected void initializePopulation() {
        population = new ArrayList<>();
        for (int[] g : generateSeedGenotypes()) {
            population.add(g.clone());
        }
        int n = taskOrder.size();
        while (population.size() < populationSize) {
            int[] genotype = new int[n];
            for (int k = 0; k < n; k++) {
                genotype[k] = random.nextInt(resources.size());
            }
            population.add(genotype);
        }
        if (population.size() > populationSize) {
            population = new ArrayList<>(population.subList(0, populationSize));
        }
        makespans = new double[populationSize];
        costs = new double[populationSize];
        for (int i = 0; i < populationSize; i++) {
            double[] mc = decode(population.get(i));
            makespans[i] = mc[0];
            costs[i] = mc[1];
        }
    }

    private double calibrateTau() {
        List<Double> sample = new ArrayList<>();
        for (int t = 0; t < 200; t++) {
            int a = random.nextInt(populationSize);
            int b = random.nextInt(populationSize);
            if (a != b) {
                sample.add(hamming(population.get(a), population.get(b)));
            }
        }
        if (sample.isEmpty()) {
            return 0.3;
        }
        java.util.Collections.sort(sample);
        return sample.get(sample.size() / 2);
    }

    private double hamming(int[] a, int[] b) {
        int diff = 0;
        for (int k = 0; k < a.length; k++) {
            if (a[k] != b[k]) {
                diff++;
            }
        }
        return (double) diff / a.length;
    }

    private double kernel(double d) {
        double raw = kernelF * Math.exp(-d / kernelL) - Math.exp(-d);
        return 1.0 / (1.0 + Math.exp(-raw));
    }

    private boolean dominates(double m1, double c1, double m2, double c2) {
        return (m1 <= m2 && c1 <= c2) && (m1 < m2 || c1 < c2);
    }

    private List<List<Integer>> nonDominatedSort(int[] frontNumberOut) {
        int n = populationSize;
        int[] domCount = new int[n];
        List<List<Integer>> dominatedBy = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            dominatedBy.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                if (dominates(makespans[i], costs[i], makespans[j], costs[j])) {
                    dominatedBy.get(i).add(j);
                } else if (dominates(makespans[j], costs[j], makespans[i], costs[i])) {
                    domCount[i]++;
                }
            }
        }
        List<List<Integer>> fronts = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (domCount[i] == 0) {
                current.add(i);
            }
        }
        int rank = 0;
        while (!current.isEmpty()) {
            for (int i : current) {
                frontNumberOut[i] = rank;
            }
            fronts.add(current);
            List<Integer> next = new ArrayList<>();
            for (int i : current) {
                for (int j : dominatedBy.get(i)) {
                    domCount[j]--;
                    if (domCount[j] == 0) {
                        next.add(j);
                    }
                }
            }
            current = next;
            rank++;
        }
        return fronts;
    }

    private double localDensity(int i, double tau) {
        int n = populationSize - 1;
        if (n <= 0) {
            return 0.0;
        }
        int count = 0;
        for (int j = 0; j < populationSize; j++) {
            if (j != i && hamming(population.get(i), population.get(j)) < tau) {
                count++;
            }
        }
        return (double) count / n;
    }

    private int weightedChoice(List<Integer> options, List<Double> weights) {
        double total = 0.0;
        for (double w : weights) {
            total += w;
        }
        if (total <= 0) {
            return options.get(random.nextInt(options.size()));
        }
        double r = random.nextDouble() * total;
        double acc = 0.0;
        for (int k = 0; k < options.size(); k++) {
            acc += weights.get(k);
            if (r <= acc) {
                return options.get(k);
            }
        }
        return options.get(options.size() - 1);
    }

    private int[] solitaryMove(int i, int[] frontNumber) {
        int n = taskOrder.size();
        int[] child = population.get(i).clone();

        // Precompute each other individual's vote weight (sign * kernel(Hamming
        // distance)) ONCE per call: it depends only on the (i, j) pair, not on
        // the gene position k, but was previously recomputed inside the k-loop
        // below -- an accidental O(n) redundancy (O(n^2 * populationSize)
        // overall) that only bites at large task counts. Same numbers, computed
        // once each; output and random-number consumption are unchanged.
        int[] voterId = new int[populationSize];
        double[] voterWeight = new double[populationSize];
        int voterCount = 0;
        for (int j = 0; j < populationSize; j++) {
            if (j == i || frontNumber[j] == frontNumber[i]) {
                continue;
            }
            double sign = (frontNumber[j] < frontNumber[i]) ? 1.0 : -1.0;
            double d = hamming(population.get(i), population.get(j));
            voterId[voterCount] = j;
            voterWeight[voterCount] = sign * kernel(d);
            voterCount++;
        }

        for (int k = 0; k < n; k++) {
            Map<Integer, Double> votes = new HashMap<>();
            for (int t = 0; t < voterCount; t++) {
                int j = voterId[t];
                double w = voterWeight[t];
                int v = population.get(j)[k];
                votes.put(v, votes.getOrDefault(v, 0.0) + w);
            }
            if (!votes.isEmpty() && random.nextDouble() < blendProbability) {
                List<Integer> options = new ArrayList<>(votes.keySet());
                double max = Double.NEGATIVE_INFINITY;
                for (int v : options) {
                    max = Math.max(max, votes.get(v));
                }
                List<Double> probs = new ArrayList<>();
                double sum = 0.0;
                for (int v : options) {
                    double e = Math.exp(votes.get(v) - max);
                    probs.add(e);
                    sum += e;
                }
                for (int idx = 0; idx < probs.size(); idx++) {
                    probs.set(idx, probs.get(idx) / sum);
                }
                child[k] = weightedChoice(options, probs);
            }
        }
        return child;
    }

    private int[] socialMove(int i, int[] frontNumber, List<Integer> elite) {
        List<Integer> candidates = new ArrayList<>();
        for (int e : elite) {
            if (e != i) {
                candidates.add(e);
            }
        }
        if (candidates.isEmpty()) {
            return population.get(i).clone();
        }
        List<Double> weights = new ArrayList<>();
        for (int e : candidates) {
            double d = hamming(population.get(i), population.get(e));
            weights.add(kernel(d) / (frontNumber[e] + 1));
        }
        int partner = weightedChoice(candidates, weights);
        int[] y = population.get(partner);
        double dIY = hamming(population.get(i), y);
        double pCopy = Math.max(0.0, Math.min(1.0, copyAlpha * kernel(dIY)));

        int[] child = population.get(i).clone();
        for (int k = 0; k < child.length; k++) {
            if (random.nextDouble() < pCopy) {
                child[k] = y[k];
            }
        }
        return child;
    }

    private void mutate(int[] child) {
        for (int k = 0; k < child.length; k++) {
            if (random.nextDouble() < mutationRate) {
                child[k] = random.nextInt(resources.size());
            }
        }
    }
}
