package org.liwsa.multicloud.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads a {@link SimulationConfig} from a {@code .properties} file. Every
 * key is optional: anything missing keeps {@link SimulationConfig}'s
 * built-in default, so a minimal or even empty properties file is valid.
 *
 * <p>Recognised keys (see {@code src/main/resources/config.properties} for
 * the fully-commented reference copy):
 * <pre>
 * simulation.randomSeed
 * simulation.numTasks
 * simulation.taskWorkloadPath
 * simulation.taskWorkloadFormat      (csv | xml | json)
 * simulation.resultsOutputDir
 * simulation.taskCountSweep          (comma-separated, e.g. "100,1000,10000,100000"; see ScalabilityDemo)
 * algorithm.populationSize
 * algorithm.generationCount
 * algorithm.numExperimentRuns
 * cloud.instancesPerVmType
 * broker.monitoringPeriod
 * </pre>
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class ConfigLoader {

    private ConfigLoader() { }

    /** Loads the {@code config.properties} bundled on the classpath (src/main/resources). */
    public static SimulationConfig loadDefault() throws IOException {
        Properties props = new Properties();
        try (InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (in != null) {
                props.load(in);
            }
        }
        return fromProperties(props);
    }

    /** Loads a config.properties file from an arbitrary filesystem path. */
    public static SimulationConfig load(Path path) throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(path)) {
            props.load(in);
        }
        return fromProperties(props);
    }

    public static SimulationConfig fromProperties(Properties props) {
        SimulationConfig cfg = new SimulationConfig();

        cfg.setRandomSeed(getLong(props, "simulation.randomSeed", cfg.getRandomSeed()));
        cfg.setNumTasks(getInt(props, "simulation.numTasks", cfg.getNumTasks()));
        cfg.setTaskWorkloadPath(props.getProperty("simulation.taskWorkloadPath", cfg.getTaskWorkloadPath()));
        cfg.setTaskWorkloadFormat(props.getProperty("simulation.taskWorkloadFormat", cfg.getTaskWorkloadFormat()));
        cfg.setResultsOutputDir(props.getProperty("simulation.resultsOutputDir", cfg.getResultsOutputDir()));
        cfg.setTaskCountSweep(getIntArray(props, "simulation.taskCountSweep", cfg.getTaskCountSweep()));

        cfg.setPopulationSize(getInt(props, "algorithm.populationSize", cfg.getPopulationSize()));
        cfg.setGenerationCount(getInt(props, "algorithm.generationCount", cfg.getGenerationCount()));
        cfg.setNumExperimentRuns(getInt(props, "algorithm.numExperimentRuns", cfg.getNumExperimentRuns()));

        cfg.setInstancesPerVmType(getInt(props, "cloud.instancesPerVmType", cfg.getInstancesPerVmType()));
        cfg.setBrokerMonitoringPeriod(getDouble(props, "broker.monitoringPeriod", cfg.getBrokerMonitoringPeriod()));

        return cfg;
    }

    private static int getInt(Properties props, String key, int fallback) {
        String raw = props.getProperty(key);
        return (raw == null || raw.isBlank()) ? fallback : Integer.parseInt(raw.trim());
    }

    private static long getLong(Properties props, String key, long fallback) {
        String raw = props.getProperty(key);
        return (raw == null || raw.isBlank()) ? fallback : Long.parseLong(raw.trim());
    }

    private static double getDouble(Properties props, String key, double fallback) {
        String raw = props.getProperty(key);
        return (raw == null || raw.isBlank()) ? fallback : Double.parseDouble(raw.trim());
    }

    private static int[] getIntArray(Properties props, String key, int[] fallback) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String[] parts = raw.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }
}
