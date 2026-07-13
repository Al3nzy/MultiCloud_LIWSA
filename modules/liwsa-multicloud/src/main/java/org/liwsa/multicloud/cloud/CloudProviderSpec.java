package org.liwsa.multicloud.cloud;

import org.liwsa.multicloud.model.CloudRegion;
import org.liwsa.multicloud.model.VmTypeSpec;

import java.util.List;

/**
 * Configuration for one independent cloud provider: its region (used later
 * for geo-latency), physical host pool, and VM type catalog. Each provider
 * is free to differ in host count/capacity and VM pricing -- see
 * {@link CloudProviderPresets} for the three ready-made AWS-like/Azure-like/
 * GCP-like configurations.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class CloudProviderSpec {

    private final String name;
    private final CloudRegion region;
    private final List<VmTypeSpec> vmTypes;
    private final int instancesPerType;

    private final int hostCount;
    private final int hostPes;
    private final double hostMipsPerPe;
    private final long hostRamMb;
    private final long hostStorageMb;
    private final long hostBwMbps;

    /** Illustrative baseline one-way network latency to this cloud's region, in milliseconds. */
    private final double baseLatencyMs;

    public CloudProviderSpec(String name, CloudRegion region, List<VmTypeSpec> vmTypes, int instancesPerType,
                              int hostCount, int hostPes, double hostMipsPerPe, long hostRamMb,
                              long hostStorageMb, long hostBwMbps, double baseLatencyMs) {
        this.name = name;
        this.region = region;
        this.vmTypes = vmTypes;
        this.instancesPerType = instancesPerType;
        this.hostCount = hostCount;
        this.hostPes = hostPes;
        this.hostMipsPerPe = hostMipsPerPe;
        this.hostRamMb = hostRamMb;
        this.hostStorageMb = hostStorageMb;
        this.hostBwMbps = hostBwMbps;
        this.baseLatencyMs = baseLatencyMs;
    }

    public String getName() { return name; }
    public CloudRegion getRegion() { return region; }
    public List<VmTypeSpec> getVmTypes() { return vmTypes; }
    public int getInstancesPerType() { return instancesPerType; }
    public int getHostCount() { return hostCount; }
    public int getHostPes() { return hostPes; }
    public double getHostMipsPerPe() { return hostMipsPerPe; }
    public long getHostRamMb() { return hostRamMb; }
    public long getHostStorageMb() { return hostStorageMb; }
    public long getHostBwMbps() { return hostBwMbps; }
    public double getBaseLatencyMs() { return baseLatencyMs; }
}
