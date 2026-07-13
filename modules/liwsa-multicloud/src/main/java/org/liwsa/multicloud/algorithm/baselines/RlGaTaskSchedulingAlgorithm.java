package org.liwsa.multicloud.algorithm.baselines;

import org.liwsa.multicloud.algorithm.SchedulingAlgorithm;
import org.liwsa.multicloud.algorithm.SchedulingResult;
import org.liwsa.multicloud.model.CloudTask;
import org.liwsa.multicloud.model.ResourceCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * ML-hybrid baseline: a genetic algorithm whose mutation operator is guided
 * by tabular Q-learning, with an EWMA/z-score overload forecaster feeding a
 * predictive penalty into fitness. This operationalizes the RL-GA-LSTM-AE
 * concept of Narsimhulu &amp; Kumar, "A hybrid RL-GA-LSTM-AE framework for
 * energy-aware and SLA-driven task scheduling in cloud computing
 * environments", Scientific Reports 16:14961 (2026), at a "light ML" scope:
 *
 * <ul>
 *   <li><b>GA</b>: tournament selection, uniform crossover, elitism &mdash;
 *       standard, exactly as in the source paper's evolutionary layer.</li>
 *   <li><b>RL</b>: the paper's deep Q-learning agent is replaced with
 *       genuine <i>tabular</i> Q-learning over a small, explicit state space
 *       (priority level x deadline-slack bucket) with a contextual-bandit
 *       style update (no discounted future term, since one task's resource
 *       choice here is a one-shot decision, not a multi-step control
 *       problem) &mdash; used as the GA's mutation operator: each mutated
 *       gene is epsilon-greedy over the Q-table instead of uniformly
 *       random.</li>
 *   <li><b>LSTM-Autoencoder substitute</b>: the paper's deep predictive/
 *       anomaly-detection module is replaced with a classical EWMA tracker
 *       of each resource's per-generation assigned load plus an EWMA of its
 *       variance, flagging a resource as "predicted overload" via a
 *       z-score threshold and penalizing chromosomes that pile fresh load
 *       onto it. Same functional role (predict load, flag anomalies), no
 *       neural network.</li>
 * </ul>
 *
 * <p>This is a transparent, literature-grounded lightweight reproduction
 * for baseline-comparison purposes, not a verbatim reimplementation of the
 * source paper's (undisclosed) exact architecture and hyperparameters.
 * Like {@link WoaTaskSchedulingAlgorithm}, it targets independent
 * (bag-of-tasks) multi-cloud scheduling.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public class RlGaTaskSchedulingAlgorithm implements SchedulingAlgorithm {

    public static int DEFAULT_POPULATION_SIZE = 30;
    public static int DEFAULT_GENERATIONS = 100;

    private final List<CloudTask> taskOrder;
    private final List<ResourceCandidate> resources;
    private final double maxMips;

    private int populationSize = DEFAULT_POPULATION_SIZE;
    private int generations = DEFAULT_GENERATIONS;
    private int tournamentSize = 3;
    private double crossoverRate = 0.9;
    private double mutationRate = 0.05;
    private Long randomSeed;
    private Random random;

    // Fitness weights (paper's R_t = w1(1-SLAv) + w2(CPUutil) - w3(Energy), re-expressed
    // here as a cost to minimize: makespan + energy + SLA-violation-rate + overload risk).
    private double wMakespan = 0.4;
    private double wEnergy = 0.2;
    private double wSla = 0.3;
    private double wOverload = 0.1;

    // Q-learning
    private static final int NUM_STATES = 9; // 3 priority levels x 3 slack buckets
    private double qLearningRate = 0.3;

    // EWMA overload forecaster
    private static final double EWMA_ALPHA = 0.3;
    private static final double OVERLOAD_Z = 1.5;
    private double[] resourceLoadEwma;
    private double[] resourceLoadEwmaVar;

    // Population-relative normalisation bounds, refreshed each generation by computeFitness()
    private double minMk, maxMk, minEn, maxEn, minOv, maxOv;

    public RlGaTaskSchedulingAlgorithm(List<CloudTask> tasks, List<ResourceCandidate> resources) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("RlGaTaskSchedulingAlgorithm requires at least one task");
        }
        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException("RlGaTaskSchedulingAlgorithm requires at least one resource candidate");
        }
        this.taskOrder = new ArrayList<>(tasks);
        this.taskOrder.sort(Comparator.comparingDouble(CloudTask::getArrivalTime)
                .thenComparingInt(CloudTask::getCloudletId));
        this.resources = resources;
        double mm = 1.0;
        for (ResourceCandidate r : resources) {
            mm = Math.max(mm, r.getMips());
        }
        this.maxMips = mm;
    }

    public void setPopulationSize(int v) { this.populationSize = v; }
    public void setGenerations(int v) { this.generations = v; }
    public void setRandomSeed(long v) { this.randomSeed = v; }
    public void setFitnessWeights(double wMakespan, double wEnergy, double wSla, double wOverload) {
        this.wMakespan = wMakespan; this.wEnergy = wEnergy; this.wSla = wSla; this.wOverload = wOverload;
    }

    @Override
    public String getName() {
        return "RL-GA-lite";
    }

    public SchedulingResult run() {
        long startMillis = System.currentTimeMillis();
        random = (randomSeed != null) ? new Random(randomSeed) : new Random();
        int n = taskOrder.size();
        int m = resources.size();

        double avgMips = 0.0;
        for (ResourceCandidate r : resources) {
            avgMips += r.getMips();
        }
        avgMips = Math.max(avgMips / resources.size(), 1e-6);

        int[] stateOfTask = new int[n];
        for (int k = 0; k < n; k++) {
            stateOfTask[k] = stateOf(taskOrder.get(k), avgMips);
        }
        double[][] qTable = new double[NUM_STATES][m];

        int[][] pop = new int[populationSize][n];
        for (int i = 0; i < populationSize; i++) {
            for (int k = 0; k < n; k++) {
                pop[i][k] = random.nextInt(m);
            }
        }
        DecodeResult[] dr = new DecodeResult[populationSize];
        for (int i = 0; i < populationSize; i++) {
            dr[i] = decode(pop[i]);
        }
        double[] fitness = computeFitness(dr);

        int bestIdx = argmin(fitness);
        int[] bestGenotype = pop[bestIdx].clone();
        double bestFitness = fitness[bestIdx];
        DecodeResult bestDr = dr[bestIdx];

        for (int gen = 0; gen < generations; gen++) {
            updateLoadForecast(bestGenotype);
            double epsilon = Math.max(0.05, 1.0 - (double) gen / Math.max(generations, 1));

            int[][] newPop = new int[populationSize][];
            DecodeResult[] newDr = new DecodeResult[populationSize];
            newPop[0] = bestGenotype.clone();
            newDr[0] = bestDr;

            for (int idx = 1; idx < populationSize; idx++) {
                int p1 = tournamentSelect(fitness);
                int p2 = tournamentSelect(fitness);
                int[] child = (random.nextDouble() < crossoverRate)
                        ? crossover(pop[p1], pop[p2])
                        : pop[p1].clone();

                List<Integer> touched = guidedMutate(child, stateOfTask, qTable, epsilon, m);
                DecodeResult childDr = decode(child);

                double parentScore = singleFitnessNormalized(dr[p1], overloadPenalty(dr[p1].resourceLoad));
                double childScore = singleFitnessNormalized(childDr, overloadPenalty(childDr.resourceLoad));
                double delta = parentScore - childScore; // positive => child improved

                for (int k : touched) {
                    int s = stateOfTask[k];
                    int a = child[k];
                    qTable[s][a] += qLearningRate * (delta - qTable[s][a]);
                }

                newPop[idx] = child;
                newDr[idx] = childDr;
            }

            pop = newPop;
            dr = newDr;
            fitness = computeFitness(dr);
            int gi = argmin(fitness);
            if (fitness[gi] < bestFitness) {
                bestFitness = fitness[gi];
                bestGenotype = pop[gi].clone();
                bestDr = dr[gi];
            }
        }

        long wallClockMillis = System.currentTimeMillis() - startMillis;
        List<double[]> front = List.of(new double[]{bestDr.makespan, bestDr.cost});
        return new SchedulingResult(taskOrder, bestGenotype, bestDr.finishTimes, bestDr.makespan, bestDr.cost, front, wallClockMillis);
    }

    // ---------------------------------------------------------------
    // Q-learning state: (priority level, deadline-slack bucket)
    // ---------------------------------------------------------------
    private int stateOf(CloudTask task, double avgMips) {
        int priorityBucket = task.getPriority().level() - 1; // 0, 1, 2
        int slackBucket;
        if (!task.hasDeadline()) {
            slackBucket = 2;
        } else {
            double estDuration = (task.getExpectedRuntimeSeconds() > 0)
                    ? task.getExpectedRuntimeSeconds()
                    : task.getCloudletLength() / avgMips;
            double slack = task.getDeadline() - task.getArrivalTime() - estDuration;
            if (slack < 0) {
                slackBucket = 0;
            } else if (slack < estDuration) {
                slackBucket = 1;
            } else {
                slackBucket = 2;
            }
        }
        return priorityBucket * 3 + slackBucket;
    }

    // ---------------------------------------------------------------
    // GA operators
    // ---------------------------------------------------------------
    private int tournamentSelect(double[] fitness) {
        int best = random.nextInt(fitness.length);
        for (int t = 1; t < tournamentSize; t++) {
            int cand = random.nextInt(fitness.length);
            if (fitness[cand] < fitness[best]) {
                best = cand;
            }
        }
        return best;
    }

    private int[] crossover(int[] a, int[] b) {
        int[] child = new int[a.length];
        for (int k = 0; k < a.length; k++) {
            child[k] = random.nextBoolean() ? a[k] : b[k];
        }
        return child;
    }

    /** Epsilon-greedy Q-guided mutation. Returns the list of gene indices that were mutated (for the Q-update). */
    private List<Integer> guidedMutate(int[] child, int[] stateOfTask, double[][] qTable, double epsilon, int numResources) {
        List<Integer> touched = new ArrayList<>();
        for (int k = 0; k < child.length; k++) {
            if (random.nextDouble() < mutationRate) {
                int s = stateOfTask[k];
                int action = (random.nextDouble() < epsilon) ? random.nextInt(numResources) : argmaxRow(qTable[s]);
                child[k] = action;
                touched.add(k);
            }
        }
        return touched;
    }

    private int argmaxRow(double[] row) {
        int best = 0;
        for (int i = 1; i < row.length; i++) {
            if (row[i] > row[best]) {
                best = i;
            }
        }
        return best;
    }

    private int argmin(double[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] < values[best]) {
                best = i;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------
    // Fitness: population-relative normalised weighted sum
    // ---------------------------------------------------------------
    private double[] computeFitness(DecodeResult[] dr) {
        minMk = Double.MAX_VALUE; maxMk = -Double.MAX_VALUE;
        minEn = Double.MAX_VALUE; maxEn = -Double.MAX_VALUE;
        minOv = Double.MAX_VALUE; maxOv = -Double.MAX_VALUE;
        double[] overload = new double[dr.length];
        for (int i = 0; i < dr.length; i++) {
            overload[i] = overloadPenalty(dr[i].resourceLoad);
            minMk = Math.min(minMk, dr[i].makespan); maxMk = Math.max(maxMk, dr[i].makespan);
            minEn = Math.min(minEn, dr[i].energyProxy); maxEn = Math.max(maxEn, dr[i].energyProxy);
            minOv = Math.min(minOv, overload[i]); maxOv = Math.max(maxOv, overload[i]);
        }
        double[] fitness = new double[dr.length];
        for (int i = 0; i < dr.length; i++) {
            fitness[i] = singleFitnessNormalized(dr[i], overload[i]);
        }
        return fitness;
    }

    private double singleFitnessNormalized(DecodeResult d, double overload) {
        double normMk = (d.makespan - minMk) / Math.max(maxMk - minMk, 1e-9);
        double normEn = (d.energyProxy - minEn) / Math.max(maxEn - minEn, 1e-9);
        double normOv = (overload - minOv) / Math.max(maxOv - minOv, 1e-9);
        return wMakespan * normMk + wEnergy * normEn + wSla * d.slaViolationRate + wOverload * normOv;
    }

    // ---------------------------------------------------------------
    // EWMA / z-score overload forecaster (LSTM-AE substitute)
    // ---------------------------------------------------------------
    private void updateLoadForecast(int[] genotype) {
        double[] load = new double[resources.size()];
        for (int k = 0; k < taskOrder.size(); k++) {
            ResourceCandidate r = resources.get(genotype[k]);
            load[genotype[k]] += taskOrder.get(k).getCloudletLength() / Math.max(r.getMips(), 1e-6);
        }
        if (resourceLoadEwma == null) {
            resourceLoadEwma = load.clone();
            resourceLoadEwmaVar = new double[load.length];
            return;
        }
        for (int r = 0; r < load.length; r++) {
            double dev = load[r] - resourceLoadEwma[r];
            resourceLoadEwmaVar[r] = (1 - EWMA_ALPHA) * resourceLoadEwmaVar[r] + EWMA_ALPHA * dev * dev;
            resourceLoadEwma[r] = (1 - EWMA_ALPHA) * resourceLoadEwma[r] + EWMA_ALPHA * load[r];
        }
    }

    private double overloadPenalty(double[] resourceLoad) {
        if (resourceLoadEwma == null) {
            return 0.0;
        }
        double penalty = 0.0;
        for (int r = 0; r < resourceLoad.length; r++) {
            double std = Math.sqrt(resourceLoadEwmaVar[r]) + 1e-6;
            double z = (resourceLoad[r] - resourceLoadEwma[r]) / std;
            if (z > OVERLOAD_Z) {
                penalty += (z - OVERLOAD_Z);
            }
        }
        return penalty;
    }

    // ---------------------------------------------------------------
    // Self-contained insertion-based decoder (independent tasks; duplicated
    // from LiwsaTaskPlanningAlgorithm for now -- see WoaTaskSchedulingAlgorithm
    // for the same note on extracting a shared utility later).
    // ---------------------------------------------------------------
    private static final class Event {
        double start; double finish;
        Event(double start, double finish) { this.start = start; this.finish = finish; }
    }

    private static final class DecodeResult {
        final double makespan;
        final double cost;
        final double slaViolationRate;
        final double energyProxy;
        final double[] resourceLoad;
        final double[] finishTimes;
        DecodeResult(double makespan, double cost, double slaViolationRate, double energyProxy,
                     double[] resourceLoad, double[] finishTimes) {
            this.makespan = makespan; this.cost = cost;
            this.slaViolationRate = slaViolationRate; this.energyProxy = energyProxy;
            this.resourceLoad = resourceLoad; this.finishTimes = finishTimes;
        }
    }

    private double findFinishTime(List<Event> sched, double readyTime, double duration) {
        if (sched.isEmpty()) {
            sched.add(new Event(readyTime, readyTime + duration));
            return readyTime + duration;
        }
        if (sched.size() == 1) {
            double start; int pos;
            if (readyTime >= sched.get(0).finish) { pos = 1; start = readyTime; }
            else if (readyTime + duration <= sched.get(0).start) { pos = 0; start = readyTime; }
            else { pos = 1; start = sched.get(0).finish; }
            sched.add(pos, new Event(start, start + duration));
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
                    start = readyTime; finish = readyTime + duration;
                }
                break;
            }
            if (previous.finish + duration <= current.start) {
                start = previous.finish; finish = previous.finish + duration; pos = i;
            }
            i--; j--;
        }
        if (readyTime + duration <= sched.get(0).start) {
            pos = 0; start = readyTime;
            sched.add(pos, new Event(start, start + duration));
            return start + duration;
        }
        sched.add(pos, new Event(start, finish));
        return finish;
    }

    private DecodeResult decode(int[] genotype) {
        Map<Integer, List<Event>> schedules = new HashMap<>();
        double[] resourceLoad = new double[resources.size()];
        double[] finishTimes = new double[taskOrder.size()];
        double cost = 0.0, makespan = 0.0, energyProxy = 0.0;
        int violations = 0;
        for (int k = 0; k < taskOrder.size(); k++) {
            CloudTask task = taskOrder.get(k);
            ResourceCandidate resource = resources.get(genotype[k]);
            double ready = task.getArrivalTime();
            double duration = task.getCloudletLength() / Math.max(resource.getMips(), 1e-6);
            List<Event> sched = schedules.computeIfAbsent(genotype[k], key -> new ArrayList<>());
            double fin = findFinishTime(sched, ready, duration);
            finishTimes[k] = fin;
            makespan = Math.max(makespan, fin);
            cost += duration * resource.getCostPerSecond();
            resourceLoad[genotype[k]] += duration;
            energyProxy += duration * (0.5 + 0.5 * resource.getMips() / maxMips);
            if (task.violatesDeadline(fin)) {
                violations++;
            }
        }
        double slaRate = (double) violations / taskOrder.size();
        return new DecodeResult(makespan, cost, slaRate, energyProxy, resourceLoad, finishTimes);
    }
}
