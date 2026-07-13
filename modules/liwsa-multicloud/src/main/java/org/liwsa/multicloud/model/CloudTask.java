package org.liwsa.multicloud.model;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.UtilizationModel;
import org.cloudbus.cloudsim.UtilizationModelFull;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A single unit of work submitted to the multi-cloud simulation.
 *
 * <p>{@code CloudTask} extends CloudSim's {@link Cloudlet} so that it can flow
 * unmodified through every stock CloudSim/CloudSim-7G component (schedulers,
 * brokers, datacenters), while adding the task attributes required by this
 * framework's scheduling and feature-extraction layers: priority, deadline,
 * explicit memory/bandwidth requirements, an optional dependency list (for
 * workloads that are not pure bags-of-tasks), an arrival time, and an
 * a-priori expected runtime estimate (as distinct from the actual runtime
 * computed from {@code cloudletLength / vm.getMips()} at decode time).
 *
 * <p>Instances are immutable except for the two mutable assignment fields
 * ({@link #getAssignedCloudId()}, and the inherited guest/VM id), which
 * schedulers set as they place the task. Use {@link Builder} to construct
 * instances.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class CloudTask extends Cloudlet {

    /** Sentinel value meaning "no deadline was specified for this task". */
    public static final double NO_DEADLINE = -1.0;

    private final TaskPriority priority;
    private final double deadline;
    private final long memoryRequirementMb;
    private final long bandwidthRequirementMbps;
    private final List<Integer> dependencies;
    private final double arrivalTime;
    private final double expectedRuntimeSeconds;

    /** The cloud (datacenter) this task has been tentatively or finally assigned to, or -1 if unassigned. */
    private int assignedCloudId = -1;

    private CloudTask(Builder b) {
        super(b.taskId, b.length, b.pes, b.inputSizeBytes, b.outputSizeBytes,
                b.utilizationModelCpu, b.utilizationModelRam, b.utilizationModelBw);
        this.priority = b.priority;
        this.deadline = b.deadline;
        this.memoryRequirementMb = b.memoryRequirementMb;
        this.bandwidthRequirementMbps = b.bandwidthRequirementMbps;
        this.dependencies = List.copyOf(b.dependencies);
        this.arrivalTime = b.arrivalTime;
        this.expectedRuntimeSeconds = b.expectedRuntimeSeconds;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    /** @return the absolute simulation-time deadline, or {@link #NO_DEADLINE} if none was set. */
    public double getDeadline() {
        return deadline;
    }

    public boolean hasDeadline() {
        return deadline != NO_DEADLINE;
    }

    public long getMemoryRequirementMb() {
        return memoryRequirementMb;
    }

    public long getBandwidthRequirementMbps() {
        return bandwidthRequirementMbps;
    }

    /** @return an unmodifiable list of task IDs that must complete before this task may start (empty for bag-of-tasks workloads). */
    public List<Integer> getDependencies() {
        return dependencies;
    }

    public boolean isIndependent() {
        return dependencies.isEmpty();
    }

    public double getArrivalTime() {
        return arrivalTime;
    }

    /** @return the a-priori expected runtime estimate in seconds, as supplied by the workload source (not the simulated actual runtime). */
    public double getExpectedRuntimeSeconds() {
        return expectedRuntimeSeconds;
    }

    public int getAssignedCloudId() {
        return assignedCloudId;
    }

    public void setAssignedCloudId(int assignedCloudId) {
        this.assignedCloudId = assignedCloudId;
    }

    /**
     * Checks whether completing this task at the given simulation time would
     * violate its deadline. Tasks with no deadline never violate.
     *
     * @param finishTime the (candidate or actual) simulation-time completion of this task
     * @return true if the task has a deadline and finishTime exceeds it
     */
    public boolean violatesDeadline(double finishTime) {
        return hasDeadline() && finishTime > deadline;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CloudTask other)) return false;
        return getCloudletId() == other.getCloudletId();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getCloudletId());
    }

    @Override
    public String toString() {
        return "CloudTask{id=" + getCloudletId() + ", length=" + getCloudletLength()
                + ", priority=" + priority + ", deadline=" + deadline
                + ", arrival=" + arrivalTime + ", cloud=" + assignedCloudId + "}";
    }

    /** Builder for {@link CloudTask}. All resource-requirement fields have sensible defaults so only id/length are strictly required. */
    public static final class Builder {
        private final int taskId;
        private final long length;
        private int pes = 1;
        private long inputSizeBytes = 300L;
        private long outputSizeBytes = 300L;
        private TaskPriority priority = TaskPriority.MEDIUM;
        private double deadline = NO_DEADLINE;
        private long memoryRequirementMb = 512L;
        private long bandwidthRequirementMbps = 10L;
        private List<Integer> dependencies = Collections.emptyList();
        private double arrivalTime = 0.0;
        private double expectedRuntimeSeconds = -1.0;
        private UtilizationModel utilizationModelCpu = new UtilizationModelFull();
        private UtilizationModel utilizationModelRam = new UtilizationModelFull();
        private UtilizationModel utilizationModelBw = new UtilizationModelFull();

        public Builder(int taskId, long lengthMi) {
            this.taskId = taskId;
            this.length = lengthMi;
        }

        public Builder pes(int pes) { this.pes = pes; return this; }
        public Builder inputSizeBytes(long v) { this.inputSizeBytes = v; return this; }
        public Builder outputSizeBytes(long v) { this.outputSizeBytes = v; return this; }
        public Builder priority(TaskPriority v) { this.priority = v; return this; }
        public Builder deadline(double v) { this.deadline = v; return this; }
        public Builder memoryRequirementMb(long v) { this.memoryRequirementMb = v; return this; }
        public Builder bandwidthRequirementMbps(long v) { this.bandwidthRequirementMbps = v; return this; }
        public Builder dependencies(List<Integer> v) { this.dependencies = v; return this; }
        public Builder arrivalTime(double v) { this.arrivalTime = v; return this; }
        public Builder expectedRuntimeSeconds(double v) { this.expectedRuntimeSeconds = v; return this; }
        public Builder utilizationModelCpu(UtilizationModel v) { this.utilizationModelCpu = v; return this; }
        public Builder utilizationModelRam(UtilizationModel v) { this.utilizationModelRam = v; return this; }
        public Builder utilizationModelBw(UtilizationModel v) { this.utilizationModelBw = v; return this; }

        public CloudTask build() {
            return new CloudTask(this);
        }
    }
}
