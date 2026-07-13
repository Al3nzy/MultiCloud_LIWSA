package org.liwsa.multicloud.cloud;

import org.cloudbus.cloudsim.Vm;
import org.liwsa.multicloud.model.CloudRegion;
import org.liwsa.multicloud.model.ResourceCandidate;
import org.liwsa.multicloud.network.HaversineLatencyModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges a list of independently-built {@link ProvisionedCloud}s into one
 * globally-consistent view: a flat {@code List<Vm>} and a flat, index-
 * aligned {@code List<ResourceCandidate>} spanning every cloud, plus
 * inter-cloud network latency via {@link HaversineLatencyModel} applied to
 * each cloud's {@link CloudRegion}.
 *
 * <p>This is the one authoritative place that renumbers
 * {@link ResourceCandidate#getIndex()} to match position in the merged VM
 * list, so both the scheduling algorithms (which only see the flat
 * candidate list) and {@code MultiCloudBroker} (which needs
 * {@code resourceIndex -> actual Vm} lookups to apply a plan) stay in sync
 * without duplicating that bookkeeping in two places.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class MultiCloudEnvironment {

    private final List<ProvisionedCloud> clouds;
    private final List<Vm> allVms;
    private final List<ResourceCandidate> resourceCandidates;
    private final Map<Integer, CloudRegion> regionByCloudId = new HashMap<>();

    public MultiCloudEnvironment(List<ProvisionedCloud> clouds) {
        this.clouds = clouds;
        this.allVms = new ArrayList<>();
        this.resourceCandidates = new ArrayList<>();
        for (ProvisionedCloud cloud : clouds) {
            int cloudId = cloud.getDatacenter().getId();
            regionByCloudId.put(cloudId, cloud.getSpec().getRegion());

            List<Vm> vms = cloud.getVms();
            List<ResourceCandidate> localCandidates = cloud.getResourceCandidates();
            for (int i = 0; i < vms.size(); i++) {
                int globalIndex = allVms.size();
                ResourceCandidate local = localCandidates.get(i);
                allVms.add(vms.get(i));
                resourceCandidates.add(new ResourceCandidate(globalIndex, local.getCloudId(), local.getCloudName(),
                        local.getMips(), local.getPes(), local.getRamMb(), local.getBwMbps(),
                        local.getStorageMb(), local.getCostPerSecond()));
            }
        }
    }

    public List<ProvisionedCloud> getClouds() { return clouds; }

    /** @return the flat VM list; index i here always describes the same VM as getResourceCandidates().get(i). */
    public List<Vm> getAllVms() { return allVms; }

    /** @return the flat, globally re-indexed ResourceCandidate list the scheduling algorithms should be given. */
    public List<ResourceCandidate> getResourceCandidates() { return resourceCandidates; }

    /** @return the region a cloud (datacenter id) is provisioned in, or null if that id isn't one of this environment's clouds. */
    public CloudRegion getRegion(int cloudId) {
        return regionByCloudId.get(cloudId);
    }

    /**
     * @return estimated one-way network latency in milliseconds between two clouds
     *         (by datacenter id), via {@link HaversineLatencyModel}; 0.0 if either
     *         cloud id is unknown to this environment.
     */
    public double getLatencyMs(int cloudIdA, int cloudIdB) {
        CloudRegion a = regionByCloudId.get(cloudIdA);
        CloudRegion b = regionByCloudId.get(cloudIdB);
        if (a == null || b == null) {
            return 0.0;
        }
        return HaversineLatencyModel.oneWayLatencyMs(a, b);
    }
}

