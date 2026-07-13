package org.liwsa.multicloud.cloud;

import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.Vm;
import org.liwsa.multicloud.model.ResourceCandidate;

import java.util.List;

/**
 * The output of {@link CloudProviderFactory}: one provider's live CloudSim
 * {@link Datacenter}, the {@link Vm} pool created inside it, and the
 * parallel (index-aligned) list of {@link ResourceCandidate} descriptors
 * the algorithm layer consumes. {@code getVms().get(i)} and
 * {@code getResourceCandidates().get(i)} always describe the same VM.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class ProvisionedCloud {

    private final CloudProviderSpec spec;
    private final Datacenter datacenter;
    private final List<Vm> vms;
    private final List<ResourceCandidate> resourceCandidates;

    public ProvisionedCloud(CloudProviderSpec spec, Datacenter datacenter, List<Vm> vms,
                             List<ResourceCandidate> resourceCandidates) {
        this.spec = spec;
        this.datacenter = datacenter;
        this.vms = vms;
        this.resourceCandidates = resourceCandidates;
    }

    public CloudProviderSpec getSpec() { return spec; }
    public Datacenter getDatacenter() { return datacenter; }
    public List<Vm> getVms() { return vms; }
    public List<ResourceCandidate> getResourceCandidates() { return resourceCandidates; }
}
