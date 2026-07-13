package org.liwsa.multicloud.model;

/**
 * A lightweight, algorithm-facing description of one schedulable resource
 * (conceptually "a VM of a given type, running in a given cloud").
 *
 * <p>All scheduling algorithms in this framework (LIWSA-Task, LIWSA-Task-ML,
 * and the baselines) operate over a flat {@code List<ResourceCandidate>}
 * spanning every cloud provider, rather than over live CloudSim {@code Vm}/
 * {@code Datacenter} objects. This keeps the search/optimization layer
 * completely decoupled from simulation wiring (single responsibility): the
 * broker/provisioning layer is responsible for turning a chosen
 * {@code (task, resource)} assignment into an actual CloudSim
 * {@code cloudletId -> vmId} binding, while the algorithms only ever reason
 * about mips/cost/index.
 *
 * <p>Assigning a task to a {@code ResourceCandidate} simultaneously fixes
 * both its VM and its cloud (since {@link #getCloudId()} is a property of
 * the candidate) -- this is what makes the same genotype representation
 * serve task scheduling, VM scheduling and cloud/provider selection at once.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class ResourceCandidate {

    private final int index;
    private final int cloudId;
    private final String cloudName;
    private final double mips;
    private final int pes;
    private final long ramMb;
    private final long bwMbps;
    private final long storageMb;
    private final double costPerSecond;

    public ResourceCandidate(int index, int cloudId, String cloudName, double mips,
                              int pes, long ramMb, long bwMbps, long storageMb, double costPerSecond) {
        this.index = index;
        this.cloudId = cloudId;
        this.cloudName = cloudName;
        this.mips = mips;
        this.pes = pes;
        this.ramMb = ramMb;
        this.bwMbps = bwMbps;
        this.storageMb = storageMb;
        this.costPerSecond = costPerSecond;
    }

    /** @return this candidate's stable position in the flat resource list used as the genotype's alphabet. */
    public int getIndex() {
        return index;
    }

    /** @return the id of the cloud (CloudSim datacenter) this resource belongs to. */
    public int getCloudId() {
        return cloudId;
    }

    public String getCloudName() {
        return cloudName;
    }

    public double getMips() {
        return mips;
    }

    public int getPes() {
        return pes;
    }

    public long getRamMb() {
        return ramMb;
    }

    public long getBwMbps() {
        return bwMbps;
    }

    public long getStorageMb() {
        return storageMb;
    }

    public double getCostPerSecond() {
        return costPerSecond;
    }

    @Override
    public String toString() {
        return "ResourceCandidate{idx=" + index + ", cloud=" + cloudName
                + ", mips=" + mips + ", cost/s=" + costPerSecond + "}";
    }
}
