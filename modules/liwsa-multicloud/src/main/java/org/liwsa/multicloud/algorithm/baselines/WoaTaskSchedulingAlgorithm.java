package org.liwsa.multicloud.algorithm.baselines;

import org.liwsa.multicloud.algorithm.SchedulingAlgorithm;
import org.liwsa.multicloud.algorithm.SchedulingResult;
import org.liwsa.multicloud.model.CloudTask;
import org.liwsa.multicloud.model.ResourceCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Non-ML bio-inspired baseline: the Whale Optimization Algorithm (Mirjalili
 * &amp; Lewis, "The Whale Optimization Algorithm", Advances in Engineering
 * Software 95:51-67, 2016), adapted to multi-cloud bag-of-tasks scheduling
 * in the spirit of Chhabra et al., "Energy-aware bag-of-tasks scheduling in
 * the cloud computing system using hybrid oppositional differential
 * evolution-enabled whale optimization algorithm", Energies 15(13):4571
 * (2022).
 *
 * <p>This is our own discretization of classic WOA, not a reproduction of
 * Chhabra et al.'s specific DE-hybrid variant: each whale is a continuous
 * position vector in {@code [0, numResources-1]^numTasks}, rounded to the
 * nearest resource index only at fitness-evaluation time (the continuous
 * state itself is what evolves under the encircling / search / bubble-net
 * spiral update rules). WOA is naturally single-objective, so the two
 * objectives (makespan, cost) are combined via a population-relative
 * min-max-normalised weighted sum &mdash; unlike LIWSA-Task, which uses
 * genuine Pareto dominance. This scalarization (rather than Pareto ranking)
 * is the fair, literature-typical way WOA is applied to multi-objective
 * scheduling, and is the key structural difference from our own algorithm
 * worth calling out in a baseline comparison.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public class WoaTaskSchedulingAlgorithm implements SchedulingAlgorithm {

    public static int DEFAULT_POPULATION_SIZE = 30;
    public static int DEFAULT_ITERATIONS = 100;

    private final List<CloudTask> taskOrder;
    private final List<ResourceCandidate> resources;

    private int populationSize = DEFAULT_POPULATION_SIZE;
    private int iterations = DEFAULT_ITERATIONS;
    private Long randomSeed;
    private Random random;

    /** Weight given to (normalised) makespan in the scalarized fitness; cost gets (1 - this). */
    private double makespanWeight = 0.5;
    /** Shape constant "b" of the WOA logarithmic bubble-net spiral. */
    private double spiralConstant = 1.0;

    public WoaTaskSchedulingAlgorithm(List<CloudTask> tasks, List<ResourceCandidate> resources) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("WoaTaskSchedulingAlgorithm requires at least one task");
        }
        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException("WoaTaskSchedulingAlgorithm requires at least one resource candidate");
        }
        this.taskOrder = new ArrayList<>(tasks);
        this.taskOrder.sort(Comparator.comparingDouble(CloudTask::getArrivalTime)
                .thenComparingInt(CloudTask::getCloudletId));
        this.resources = resources;
    }

    public void setPopulationSize(int v) { this.populationSize = v; }
    public void setIterations(int v) { this.iterations = v; }
    public void setRandomSeed(long v) { this.randomSeed = v; }
    public void setMakespanWeight(double v) { this.makespanWeight = v; }

    @Override
    public String getName() {
        return "WOA";
    }

    public SchedulingResult run() {
        long startMillis = System.currentTimeMillis();
        random = (randomSeed != null) ? new Random(randomSeed) : new Random();
        int n = taskOrder.size();
        int m = resources.size();

        double[][] positions = new double[populationSize][n];
        for (int i = 0; i < populationSize; i++) {
            for (int k = 0; k < n; k++) {
                positions[i][k] = random.nextDouble() * (m - 1);
            }
        }

        double[] makespans = new double[populationSize];
        double[] costs = new double[populationSize];
        for (int i = 0; i < populationSize; i++) {
            double[] mc = decode(discretize(positions[i], m));
            makespans[i] = mc[0];
            costs[i] = mc[1];
        }
        double[] fitness = scalarize(makespans, costs);

        int bestIdx = argmin(fitness);
        double[] bestPosition = positions[bestIdx].clone();
        double bestFitness = fitness[bestIdx];
        double bestMakespan = makespans[bestIdx];
        double bestCost = costs[bestIdx];

        for (int t = 0; t < iterations; t++) {
            double a = 2.0 - 2.0 * t / Math.max(iterations - 1, 1);

            for (int i = 0; i < populationSize; i++) {
                double r1 = random.nextDouble();
                double r2 = random.nextDouble();
                double coefA = 2 * a * r1 - a;
                double coefC = 2 * r2;
                double[] newPos = new double[n];

                if (random.nextDouble() < 0.5) {
                    if (Math.abs(coefA) < 1.0) {
                        // Encircling prey: shrink toward the current best whale.
                        for (int k = 0; k < n; k++) {
                            double d = Math.abs(coefC * bestPosition[k] - positions[i][k]);
                            newPos[k] = bestPosition[k] - coefA * d;
                        }
                    } else {
                        // Search for prey: explore away from a random whale.
                        int randIdx = random.nextInt(populationSize);
                        for (int k = 0; k < n; k++) {
                            double d = Math.abs(coefC * positions[randIdx][k] - positions[i][k]);
                            newPos[k] = positions[randIdx][k] - coefA * d;
                        }
                    }
                } else {
                    // Bubble-net attack: logarithmic spiral toward the best whale.
                    double l = random.nextDouble() * 2 - 1;
                    for (int k = 0; k < n; k++) {
                        double dPrime = Math.abs(bestPosition[k] - positions[i][k]);
                        newPos[k] = dPrime * Math.exp(spiralConstant * l) * Math.cos(2 * Math.PI * l) + bestPosition[k];
                    }
                }

                for (int k = 0; k < n; k++) {
                    newPos[k] = clamp(newPos[k], 0, m - 1);
                }
                positions[i] = newPos;
                double[] mc = decode(discretize(newPos, m));
                makespans[i] = mc[0];
                costs[i] = mc[1];
            }

            fitness = scalarize(makespans, costs);
            int gi = argmin(fitness);
            if (fitness[gi] < bestFitness) {
                bestFitness = fitness[gi];
                bestPosition = positions[gi].clone();
                bestMakespan = makespans[gi];
                bestCost = costs[gi];
            }
        }

        int[] finalGenotype = discretize(bestPosition, m);
        double[] finishTimes = decodeFinishTimes(finalGenotype);
        long wallClockMillis = System.currentTimeMillis() - startMillis;
        List<double[]> front = List.of(new double[]{bestMakespan, bestCost});
        return new SchedulingResult(taskOrder, finalGenotype, finishTimes, bestMakespan, bestCost, front, wallClockMillis);
    }

    private double[] scalarize(double[] makespans, double[] costs) {
        double minM = Double.MAX_VALUE, maxM = -Double.MAX_VALUE;
        double minC = Double.MAX_VALUE, maxC = -Double.MAX_VALUE;
        for (int i = 0; i < makespans.length; i++) {
            minM = Math.min(minM, makespans[i]);
            maxM = Math.max(maxM, makespans[i]);
            minC = Math.min(minC, costs[i]);
            maxC = Math.max(maxC, costs[i]);
        }
        double rangeM = Math.max(maxM - minM, 1e-9);
        double rangeC = Math.max(maxC - minC, 1e-9);
        double[] fitness = new double[makespans.length];
        for (int i = 0; i < makespans.length; i++) {
            double normM = (makespans[i] - minM) / rangeM;
            double normC = (costs[i] - minC) / rangeC;
            fitness[i] = makespanWeight * normM + (1 - makespanWeight) * normC;
        }
        return fitness;
    }

    private int[] discretize(double[] position, int numResources) {
        int[] g = new int[position.length];
        for (int k = 0; k < position.length; k++) {
            int idx = (int) Math.round(position[k]);
            g[k] = Math.max(0, Math.min(numResources - 1, idx));
        }
        return g;
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
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
    // Self-contained insertion-based decoder (independent tasks, no
    // dependencies -- deliberately duplicated from LiwsaTaskPlanningAlgorithm
    // rather than shared, so each baseline stands alone for now; a shared
    // decoder utility can be extracted later).
    // ---------------------------------------------------------------
    private static final class Event {
        double start;
        double finish;
        Event(double start, double finish) { this.start = start; this.finish = finish; }
    }

    private double findFinishTime(List<Event> sched, double readyTime, double duration) {
        if (sched.isEmpty()) {
            sched.add(new Event(readyTime, readyTime + duration));
            return readyTime + duration;
        }
        if (sched.size() == 1) {
            double start;
            int pos;
            if (readyTime >= sched.get(0).finish) {
                pos = 1; start = readyTime;
            } else if (readyTime + duration <= sched.get(0).start) {
                pos = 0; start = readyTime;
            } else {
                pos = 1; start = sched.get(0).finish;
            }
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

    private double[] decode(int[] genotype) {
        java.util.Map<Integer, List<Event>> schedules = new java.util.HashMap<>();
        double makespan = 0.0;
        double cost = 0.0;
        for (int k = 0; k < taskOrder.size(); k++) {
            CloudTask task = taskOrder.get(k);
            ResourceCandidate resource = resources.get(genotype[k]);
            double ready = task.getArrivalTime();
            double duration = task.getCloudletLength() / Math.max(resource.getMips(), 1e-6);
            List<Event> sched = schedules.computeIfAbsent(genotype[k], key -> new ArrayList<>());
            double fin = findFinishTime(sched, ready, duration);
            makespan = Math.max(makespan, fin);
            cost += duration * resource.getCostPerSecond();
        }
        return new double[]{makespan, cost};
    }

    /** Same as {@link #decode}, but also returns each task's own finish time; called once on the final best position. */
    private double[] decodeFinishTimes(int[] genotype) {
        java.util.Map<Integer, List<Event>> schedules = new java.util.HashMap<>();
        double[] finishByOrder = new double[taskOrder.size()];
        for (int k = 0; k < taskOrder.size(); k++) {
            CloudTask task = taskOrder.get(k);
            ResourceCandidate resource = resources.get(genotype[k]);
            double ready = task.getArrivalTime();
            double duration = task.getCloudletLength() / Math.max(resource.getMips(), 1e-6);
            List<Event> sched = schedules.computeIfAbsent(genotype[k], key -> new ArrayList<>());
            finishByOrder[k] = findFinishTime(sched, ready, duration);
        }
        return finishByOrder;
    }
}
