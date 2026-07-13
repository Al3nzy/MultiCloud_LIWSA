package org.liwsa.multicloud.cloud;

import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.liwsa.multicloud.model.ResourceCandidate;
import org.liwsa.multicloud.model.VmTypeSpec;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Turns a {@link CloudProviderSpec} into a live CloudSim {@link Datacenter}
 * (with its own {@code Host} pool and {@code VmAllocationPolicySimple}) plus
 * the {@link Vm} pool and {@link ResourceCandidate} list the rest of this
 * framework operates on.
 *
 * <p><b>Must be called after {@code CloudSim.init(...)}</b>: constructing a
 * {@code Datacenter} registers a new simulation entity, which requires the
 * simulation to already be initialised. This class does not call
 * {@code CloudSim.init(...)} itself -- that is the orchestrating code's
 * (e.g. the eventual {@code ExperimentRunner}'s) responsibility, since it
 * owns the simulation lifecycle.
 *
 * <p>VM ids must be globally unique across every cloud in a run, so
 * {@link #buildAll(List)} is the entry point most callers want: it assigns
 * non-overlapping VM id ranges across providers automatically. The single-
 * provider {@link #build(CloudProviderSpec, int)} is available directly for
 * callers that want to manage id ranges themselves.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class CloudProviderFactory {

    private CloudProviderFactory() { }

    /** Builds every provider in {@code specs}, assigning each a non-overlapping VM id range. */
    public static List<ProvisionedCloud> buildAll(List<CloudProviderSpec> specs) throws Exception {
        List<ProvisionedCloud> result = new ArrayList<>();
        int vmIdCursor = 0;
        for (CloudProviderSpec spec : specs) {
            ProvisionedCloud cloud = build(spec, vmIdCursor);
            result.add(cloud);
            vmIdCursor += cloud.getVms().size();
        }
        return result;
    }

    /**
     * Builds a single provider. VM ids are assigned starting at
     * {@code vmIdStart}; {@link ResourceCandidate#getIndex()} values here
     * are only locally meaningful (0-based within this provider) -- use
     * {@link MultiCloudEnvironment}, not these candidates directly, once
     * you have more than one cloud, since it renumbers them globally to
     * stay aligned with the merged VM list.
     */
    public static ProvisionedCloud build(CloudProviderSpec spec, int vmIdStart) throws Exception {
        List<Host> hosts = new ArrayList<>();
        for (int h = 0; h < spec.getHostCount(); h++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < spec.getHostPes(); p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(spec.getHostMipsPerPe())));
            }
            hosts.add(new Host(
                    h,
                    new RamProvisionerSimple((int) spec.getHostRamMb()),
                    new BwProvisionerSimple(spec.getHostBwMbps()),
                    spec.getHostStorageMb(),
                    peList,
                    new VmSchedulerTimeShared(peList)));
        }

        // Per-VM pricing lives on ResourceCandidate/VmTypeSpec, not on the
        // datacenter-wide flat cost fields here, since providers in this
        // framework price per VM type rather than uniformly.
        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hosts, 10.0, 0.0, 0.0, 0.0, 0.0);

        Datacenter datacenter = new Datacenter(
                spec.getName(), characteristics, new VmAllocationPolicySimple(hosts),
                new LinkedList<>(), 0.0);

        List<Vm> vms = new ArrayList<>();
        List<ResourceCandidate> candidates = new ArrayList<>();
        int vmId = vmIdStart;
        int localIndex = 0;
        for (VmTypeSpec type : spec.getVmTypes()) {
            for (int inst = 0; inst < spec.getInstancesPerType(); inst++) {
                Vm vm = new Vm(vmId, -1, type.getMips(), type.getPes(), (int) type.getRamMb(),
                        type.getBwMbps(), type.getStorageMb(), "Xen", new CloudletSchedulerTimeShared());
                vms.add(vm);
                candidates.add(new ResourceCandidate(localIndex, datacenter.getId(), spec.getName(),
                        type.getMips(), type.getPes(), type.getRamMb(), type.getBwMbps(),
                        type.getStorageMb(), type.getCostPerSecond()));
                vmId++;
                localIndex++;
            }
        }

        return new ProvisionedCloud(spec, datacenter, vms, candidates);
    }
}
