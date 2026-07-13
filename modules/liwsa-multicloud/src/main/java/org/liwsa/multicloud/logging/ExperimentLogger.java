package org.liwsa.multicloud.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * A thin, categorised wrapper over {@code java.util.logging} covering the
 * event types a multi-cloud scheduling experiment cares about: task
 * scheduling decisions, VM allocation, cloud selection, migration,
 * failures, energy, cost, and end-of-run summaries. Logs to the console by
 * default; call {@link #attachFile} to also persist to a log file.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class ExperimentLogger {

    private final Logger logger;
    private FileHandler fileHandler;

    public ExperimentLogger(String name) {
        this.logger = Logger.getLogger(name);
    }

    /** Adds a file handler so log records are also appended to {@code logFile}. */
    public void attachFile(Path logFile) throws IOException {
        Path parent = logFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        fileHandler = new FileHandler(logFile.toString(), true);
        fileHandler.setFormatter(new SimpleFormatter());
        logger.addHandler(fileHandler);
    }

    public void logTaskScheduled(int taskId, int vmId, int cloudId) {
        logger.info(() -> String.format("[SCHEDULE] task=%d -> vm=%d cloud=%d", taskId, vmId, cloudId));
    }

    public void logVmAllocation(int vmId, int hostId, int cloudId) {
        logger.info(() -> String.format("[VM-ALLOC] vm=%d -> host=%d cloud=%d", vmId, hostId, cloudId));
    }

    public void logCloudSelection(int taskId, int cloudId, String reason) {
        logger.info(() -> String.format("[CLOUD-SELECT] task=%d -> cloud=%d (%s)", taskId, cloudId, reason));
    }

    public void logMigration(int taskId, int fromVmId, int toVmId, boolean success) {
        logger.log(success ? Level.INFO : Level.WARNING, () -> String.format(
                "[MIGRATE] task=%d vm %d -> %d (%s)", taskId, fromVmId, toVmId, success ? "ok" : "failed"));
    }

    public void logFailure(String context, Throwable cause) {
        logger.log(Level.SEVERE, "[FAILURE] " + context, cause);
    }

    public void logEnergy(int cloudId, double energyProxy) {
        logger.info(() -> String.format("[ENERGY] cloud=%d energyProxy=%.4f", cloudId, energyProxy));
    }

    public void logCost(String algorithmName, double totalCost) {
        logger.info(() -> String.format("[COST] %s totalCost=%.4f", algorithmName, totalCost));
    }

    public void logSummary(String message) {
        logger.info(() -> "[SUMMARY] " + message);
    }

    /** Releases the file handler, if one was attached. */
    public void close() {
        if (fileHandler != null) {
            fileHandler.close();
        }
    }
}
