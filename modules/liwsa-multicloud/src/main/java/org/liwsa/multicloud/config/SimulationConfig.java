package org.liwsa.multicloud.config;

/**
 * Every experiment-level parameter this framework reads from
 * {@code config.properties} (see {@link ConfigLoader}), with the defaults
 * used when a key is absent.
 *
 * <p><b>Scope note:</b> per-cloud hardware (host counts, PEs, RAM, VM
 * catalog, pricing) currently lives in {@link org.liwsa.multicloud.cloud.CloudProviderPresets}
 * as code rather than as properties -- {@link #instancesPerVmType} is the
 * one hook this config exposes into that layer (a simple multiplier on how
 * many VMs of each catalog type get provisioned). Fully externalizing every
 * host/VM field to properties is a reasonable next step if a study needs to
 * sweep hardware configurations, but wasn't needed for the experiments this
 * framework currently runs, so it was left as a documented boundary rather
 * than half-done.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class SimulationConfig {

    private long randomSeed = 42L;

    private int numTasks = 60;
    private String taskWorkloadPath = null; // null => generate a synthetic workload instead of reading a file
    private String taskWorkloadFormat = "csv"; // csv | xml | json

    private int populationSize = 30;
    private int generationCount = 100;
    private int numExperimentRuns = 30;
    private int[] taskCountSweep = {100, 1_000, 10_000, 100_000};
    private long perAlgorithmTimeoutSeconds = 6000;

    private int instancesPerVmType = 4;
    private double brokerMonitoringPeriod = -1; // -1 disables periodic utilisation monitoring

    private String resultsOutputDir = "results";

    public long getRandomSeed() { return randomSeed; }
    public void setRandomSeed(long v) { this.randomSeed = v; }

    public int getNumTasks() { return numTasks; }
    public void setNumTasks(int v) { this.numTasks = v; }

    public String getTaskWorkloadPath() { return taskWorkloadPath; }
    public void setTaskWorkloadPath(String v) { this.taskWorkloadPath = v; }

    public String getTaskWorkloadFormat() { return taskWorkloadFormat; }
    public void setTaskWorkloadFormat(String v) { this.taskWorkloadFormat = v; }

    public int getPopulationSize() { return populationSize; }
    public void setPopulationSize(int v) { this.populationSize = v; }

    public int getGenerationCount() { return generationCount; }
    public void setGenerationCount(int v) { this.generationCount = v; }

    public int getNumExperimentRuns() { return numExperimentRuns; }
    public void setNumExperimentRuns(int v) { this.numExperimentRuns = v; }

    /** Task counts for {@code ScalabilityDemo}'s low-to-high load sweep, e.g. {100, 1000, 10000, 100000}. */
    public int[] getTaskCountSweep() { return taskCountSweep; }
    public void setTaskCountSweep(int[] v) { this.taskCountSweep = v; }

    /** Wall-clock budget, per (algorithm, task count) run, for ScalabilityDemo's timeout/skip safety net. */
    public long getPerAlgorithmTimeoutSeconds() { return perAlgorithmTimeoutSeconds; }
    public void setPerAlgorithmTimeoutSeconds(long v) { this.perAlgorithmTimeoutSeconds = v; }

    public int getInstancesPerVmType() { return instancesPerVmType; }
    public void setInstancesPerVmType(int v) { this.instancesPerVmType = v; }

    public double getBrokerMonitoringPeriod() { return brokerMonitoringPeriod; }
    public void setBrokerMonitoringPeriod(double v) { this.brokerMonitoringPeriod = v; }

    public String getResultsOutputDir() { return resultsOutputDir; }
    public void setResultsOutputDir(String v) { this.resultsOutputDir = v; }

    @Override
    public String toString() {
        return "SimulationConfig{randomSeed=" + randomSeed + ", numTasks=" + numTasks
                + ", taskWorkloadPath=" + taskWorkloadPath + ", taskWorkloadFormat=" + taskWorkloadFormat
                + ", populationSize=" + populationSize + ", generationCount=" + generationCount
                + ", numExperimentRuns=" + numExperimentRuns + ", taskCountSweep=" + java.util.Arrays.toString(taskCountSweep)
                + ", perAlgorithmTimeoutSeconds=" + perAlgorithmTimeoutSeconds
                + ", instancesPerVmType=" + instancesPerVmType
                + ", brokerMonitoringPeriod=" + brokerMonitoringPeriod + ", resultsOutputDir=" + resultsOutputDir + "}";
    }
}
