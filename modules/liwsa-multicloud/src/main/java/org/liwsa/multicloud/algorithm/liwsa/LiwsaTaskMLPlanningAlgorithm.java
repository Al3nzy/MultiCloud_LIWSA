package org.liwsa.multicloud.algorithm.liwsa;

import org.liwsa.multicloud.model.CloudTask;
import org.liwsa.multicloud.model.ResourceCandidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LIWSA-Task-ML: {@link LiwsaTaskPlanningAlgorithm} with a "light ML" warm
 * start, ported from the original workflow LIWSA-ML algorithm.
 *
 * <p>"Light" is meant literally: the predictor is an ordinary-least-squares
 * regression fitted from scratch with Gaussian elimination on the normal
 * equations, using no external ML/linear-algebra library. Before the search
 * begins, {@link #numTrainingSamples} random (task, resource) decodes are
 * used to fit two linear models &mdash; predicted makespan and predicted
 * cost, as a function of a 9-dimensional feature vector &mdash; and those
 * models are then used to construct {@link #numPredictorSeeds} biased
 * initial genotypes, each favouring a different point on the makespan/cost
 * trade-off, via softmax sampling over predicted scores. These seeds are
 * added to (not a replacement for) the base class's ordinary random
 * initial population, so LIWSA-Task-ML never starts from a worse position
 * than plain LIWSA-Task.
 *
 * <p>The only change from the original workflow version is the feature
 * vector: workflow-DAG-specific features (child count) are replaced with
 * this framework's task attributes (priority weight, dependency count),
 * and "depth" now means dependency-graph depth (0 for an independent
 * bag-of-tasks workload, which is the common case here).
 *
 * @author LIWSA Multi-Cloud Framework
 */
public class LiwsaTaskMLPlanningAlgorithm extends LiwsaTaskPlanningAlgorithm {

    private int numTrainingSamples = 400;
    private int numPredictorSeeds = 4;
    private double predTemperature = 0.5;
    private final double[] makespanWeights = {0.9, 0.7, 0.5, 0.3};

    private static final int N_FEATURES = 9;

    private double[] coefMakespan;
    private double[] coefCost;

    private double maxTaskLength = 1.0;
    private double maxDepth = 1.0;
    private double maxDependencies = 1.0;
    private double maxMips = 1.0;
    private double maxCost = 1.0;
    private double maxPredDuration = 1.0;
    private double maxPredCost = 1.0;

    public LiwsaTaskMLPlanningAlgorithm(List<CloudTask> tasks, List<ResourceCandidate> resources) {
        super(tasks, resources);
    }

    public void setNumTrainingSamples(int v) { this.numTrainingSamples = v; }
    public void setNumPredictorSeeds(int v) { this.numPredictorSeeds = v; }
    public void setPredTemperature(double v) { this.predTemperature = v; }

    @Override
    public String getName() {
        return "LIWSA-Task-ML";
    }

    @Override
    protected List<int[]> generateSeedGenotypes() {
        computeNormalisationConstants();
        trainPredictor();

        List<int[]> seeds = super.generateSeedGenotypes();
        seeds.addAll(buildBiasedGenotypes());
        return seeds;
    }

    // ---------------------------------------------------------------
    // Dependency-graph depth (0 for pure bag-of-tasks); taskOrder is
    // already topologically sorted, so one forward pass suffices.
    // ---------------------------------------------------------------
    private Map<Integer, Integer> computeDepths() {
        Map<Integer, Integer> depth = new HashMap<>();
        for (CloudTask t : taskOrder) {
            int d = 0;
            for (Integer depId : t.getDependencies()) {
                Integer pd = depth.get(depId);
                if (pd != null) {
                    d = Math.max(d, pd + 1);
                }
            }
            depth.put(t.getCloudletId(), d);
        }
        return depth;
    }

    private void computeNormalisationConstants() {
        maxTaskLength = 1.0;
        maxDepth = 1.0;
        maxDependencies = 1.0;
        maxMips = 1.0;
        maxCost = 1.0;

        Map<Integer, Integer> depthMap = computeDepths();
        for (CloudTask t : taskOrder) {
            maxTaskLength = Math.max(maxTaskLength, t.getCloudletLength());
            maxDepth = Math.max(maxDepth, depthMap.getOrDefault(t.getCloudletId(), 0));
            maxDependencies = Math.max(maxDependencies, t.getDependencies().size());
        }

        double slowestMips = resources.get(0).getMips();
        for (ResourceCandidate r : resources) {
            maxMips = Math.max(maxMips, r.getMips());
            maxCost = Math.max(maxCost, r.getCostPerSecond());
            slowestMips = Math.min(slowestMips, r.getMips());
        }
        if (maxCost < 1e-9) {
            maxCost = 1.0;
        }
        slowestMips = Math.max(slowestMips, 1.0);
        maxPredDuration = maxTaskLength / slowestMips;
        maxPredCost = maxPredDuration * maxCost;
        if (maxPredCost < 1e-9) {
            maxPredCost = 1.0;
        }
    }

    /**
     * 9-dimensional feature vector for a (task, resource) pair:
     *   [0] task length (normalised)
     *   [1] task priority weight (already in (0, 1])
     *   [2] dependency-graph depth (normalised; 0 for bag-of-tasks)
     *   [3] number of dependencies (normalised; 0 for bag-of-tasks)
     *   [4] resource MIPS (normalised)
     *   [5] resource cost per second (normalised)
     *   [6] predicted execution duration on this resource (normalised)
     *   [7] predicted execution cost on this resource (normalised)
     *   [8] intercept (always 1.0)
     */
    private double[] extractFeatures(CloudTask task, ResourceCandidate resource, Map<Integer, Integer> depthMap) {
        double len = task.getCloudletLength();
        double dur = len / Math.max(resource.getMips(), 1.0);
        double pcost = dur * resource.getCostPerSecond();
        return new double[]{
                len / maxTaskLength,
                task.getPriority().weight(),
                (double) depthMap.getOrDefault(task.getCloudletId(), 0) / maxDepth,
                (double) task.getDependencies().size() / maxDependencies,
                resource.getMips() / maxMips,
                resource.getCostPerSecond() / maxCost,
                dur / maxPredDuration,
                pcost / maxPredCost,
                1.0
        };
    }

    /**
     * Ceiling on total OLS training rows ({@code numTrainingSamples * numTasks}).
     * At this framework's original ~1,000-task scale the default 400 samples
     * already stays under this (400,000 rows), so behaviour there is unchanged;
     * it only takes effect at large task counts, where it scales the sample
     * *count* down instead of letting memory grow unbounded with numTasks
     * (100,000 tasks at the un-capped default would need ~40,000,000 rows).
     */
    private static final int MAX_TRAINING_ROWS = 500_000;

    private void trainPredictor() {
        int n = taskOrder.size();
        int m = resources.size();
        int effectiveSamples = Math.max(1, Math.min(numTrainingSamples, MAX_TRAINING_ROWS / Math.max(n, 1)));
        int rows = effectiveSamples * n;

        Map<Integer, Integer> depthMap = computeDepths();

        double[][] x = new double[rows][N_FEATURES];
        double[] yMakespan = new double[rows];
        double[] yCost = new double[rows];

        int row = 0;
        for (int s = 0; s < effectiveSamples; s++) {
            int[] genotype = new int[n];
            for (int k = 0; k < n; k++) {
                genotype[k] = random.nextInt(m);
            }
            double[] result = decode(genotype);
            double makespan = result[0];
            double cost = result[1];

            for (int k = 0; k < n; k++) {
                CloudTask t = taskOrder.get(k);
                ResourceCandidate r = resources.get(genotype[k]);
                x[row] = extractFeatures(t, r, depthMap);
                yMakespan[row] = makespan;
                yCost[row] = cost;
                row++;
            }
        }

        coefMakespan = solveOLS(x, yMakespan, rows);
        coefCost = solveOLS(x, yCost, rows);
    }

    /**
     * Pure-Java OLS solver: normal equations (X^T X) beta = X^T y, solved
     * via Gaussian elimination with partial pivoting on the augmented
     * (9 x 10) matrix. No external linear-algebra library required.
     */
    private double[] solveOLS(double[][] x, double[] y, int nRows) {
        int p = N_FEATURES;

        double[][] xtx = new double[p][p];
        for (int r = 0; r < nRows; r++) {
            for (int i = 0; i < p; i++) {
                for (int j = 0; j < p; j++) {
                    xtx[i][j] += x[r][i] * x[r][j];
                }
            }
        }

        double[] xty = new double[p];
        for (int r = 0; r < nRows; r++) {
            for (int i = 0; i < p; i++) {
                xty[i] += x[r][i] * y[r];
            }
        }

        double[][] aug = new double[p][p + 1];
        for (int i = 0; i < p; i++) {
            System.arraycopy(xtx[i], 0, aug[i], 0, p);
            aug[i][p] = xty[i];
        }

        for (int col = 0; col < p; col++) {
            int pivotRow = col;
            double maxVal = Math.abs(aug[col][col]);
            for (int r = col + 1; r < p; r++) {
                if (Math.abs(aug[r][col]) > maxVal) {
                    maxVal = Math.abs(aug[r][col]);
                    pivotRow = r;
                }
            }
            double[] tmp = aug[col];
            aug[col] = aug[pivotRow];
            aug[pivotRow] = tmp;

            if (Math.abs(aug[col][col]) < 1e-12) {
                continue;
            }
            for (int r = col + 1; r < p; r++) {
                double factor = aug[r][col] / aug[col][col];
                for (int c = col; c <= p; c++) {
                    aug[r][c] -= factor * aug[col][c];
                }
            }
        }

        double[] beta = new double[p];
        for (int i = p - 1; i >= 0; i--) {
            if (Math.abs(aug[i][i]) < 1e-12) {
                beta[i] = 0.0;
                continue;
            }
            double sum = aug[i][p];
            for (int j = i + 1; j < p; j++) {
                sum -= aug[i][j] * beta[j];
            }
            beta[i] = sum / aug[i][i];
        }
        return beta;
    }

    private double predict(double[] coef, double[] features) {
        double val = 0.0;
        for (int i = 0; i < N_FEATURES; i++) {
            val += coef[i] * features[i];
        }
        return val;
    }

    /**
     * Builds {@link #numPredictorSeeds} genotypes by scoring each task's
     * resource choices with the trained models and sampling from a softmax
     * over those scores. Each seed uses a different makespan/cost weight so
     * the seeds span the trade-off curve instead of collapsing to one point.
     */
    private List<int[]> buildBiasedGenotypes() {
        if (coefMakespan == null || coefCost == null) {
            return new ArrayList<>();
        }

        Map<Integer, Integer> depthMap = computeDepths();
        int n = taskOrder.size();
        int m = resources.size();
        List<int[]> seeds = new ArrayList<>();

        int numSeeds = Math.min(numPredictorSeeds, makespanWeights.length);
        for (int s = 0; s < numSeeds; s++) {
            double wM = makespanWeights[s];
            double wC = 1.0 - wM;
            int[] genotype = new int[n];

            for (int k = 0; k < n; k++) {
                CloudTask task = taskOrder.get(k);
                double[] scores = new double[m];
                for (int v = 0; v < m; v++) {
                    ResourceCandidate r = resources.get(v);
                    double[] features = extractFeatures(task, r, depthMap);
                    double pm = predict(coefMakespan, features);
                    double pc = predict(coefCost, features);
                    scores[v] = -(wM * pm + wC * pc);
                }
                genotype[k] = softmaxSample(scores);
            }
            seeds.add(genotype);
        }
        return seeds;
    }

    private int softmaxSample(double[] scores) {
        double temp = Math.max(predTemperature, 1e-6);
        double maxScore = scores[0];
        for (double s : scores) {
            maxScore = Math.max(maxScore, s);
        }

        double[] probs = new double[scores.length];
        double sum = 0.0;
        for (int i = 0; i < scores.length; i++) {
            probs[i] = Math.exp((scores[i] - maxScore) / temp);
            sum += probs[i];
        }

        double r = random.nextDouble() * sum;
        double cumulative = 0.0;
        for (int i = 0; i < probs.length; i++) {
            cumulative += probs[i];
            if (r <= cumulative) {
                return i;
            }
        }
        return scores.length - 1;
    }
}
