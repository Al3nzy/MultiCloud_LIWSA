package org.liwsa.multicloud.broker;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.EX.MonitoringBrokerEX;
import org.cloudbus.cloudsim.Vm;
import org.liwsa.multicloud.algorithm.SchedulingResult;
import org.liwsa.multicloud.cloud.MultiCloudEnvironment;
import org.liwsa.multicloud.cloud.ProvisionedCloud;
import org.liwsa.multicloud.model.CloudTask;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@code DatacenterBroker} that is aware of more than one cloud provider
 * at once. Unlike a broker that makes independent runtime placement
 * decisions, this one is deliberately a thin executor of a plan already
 * computed by one of this framework's scheduling algorithms (LIWSA-Task,
 * LIWSA-Task-ML, or a baseline) against the same {@link MultiCloudEnvironment}
 * -- mirroring how the original LIWSA planner decided a schedule up front
 * and the simulation engine then just carried it out.
 *
 * <p>Typical usage:
 * <pre>
 *   CloudSim.init(1, Calendar.getInstance(), false);
 *   List&lt;ProvisionedCloud&gt; clouds = CloudProviderFactory.buildAll(CloudProviderPresets.defaultThreeCloud());
 *   MultiCloudEnvironment env = new MultiCloudEnvironment(clouds);
 *
 *   SchedulingResult plan = new LiwsaTaskPlanningAlgorithm(tasks, env.getResourceCandidates()).run();
 *
 *   MultiCloudBroker broker = new MultiCloudBroker("broker-0", env, -1);
 *   broker.submitTasks(plan, tasks);
 *   CloudSim.startSimulation();
 * </pre>
 *
 * @author LIWSA Multi-Cloud Framework
 */
public class MultiCloudBroker extends MonitoringBrokerEX {

    private final MultiCloudEnvironment environment;
    private final Map<Integer, Integer> vmIdToCloudId = new HashMap<>();
    private final Map<Integer, Integer> taskIdToVmId = new HashMap<>();

    /**
     * @param name broker entity name (must be unique in the simulation)
     * @param environment the merged multi-cloud view this broker will submit VMs/tasks into
     * @param monitoringPeriod how often (simulation seconds) to record utilisation samples via the
     *                         inherited {@code MonitoringBrokerEX} mechanism; -1 disables periodic monitoring
     */
    public MultiCloudBroker(String name, MultiCloudEnvironment environment, double monitoringPeriod) throws Exception {
        super(name, -1, monitoringPeriod, -1);
        this.environment = environment;
        for (ProvisionedCloud cloud : environment.getClouds()) {
            int cloudId = cloud.getDatacenter().getId();
            for (Vm vm : cloud.getVms()) {
                vmIdToCloudId.put(vm.getId(), cloudId);
            }
        }
    }

    /**
     * Applies {@code plan} (as produced by any of this framework's
     * scheduling algorithms run against {@code environment.getResourceCandidates()})
     * to this simulation: takes ownership of every VM and task, submits
     * them to CloudSim, then binds each task to the VM its assigned
     * resource candidate corresponds to. Call after this broker is
     * constructed and before {@code CloudSim.startSimulation()}.
     */
    public void submitTasks(SchedulingResult plan, List<CloudTask> tasks) {
        List<Vm> allVms = environment.getAllVms();
        for (Vm vm : allVms) {
            vm.setUserId(getId());
        }
        Map<Integer, CloudTask> byId = new HashMap<>();
        for (CloudTask task : tasks) {
            task.setUserId(getId());
            byId.put(task.getCloudletId(), task);
        }
        submitGuestList(allVms);
        submitCloudletList(tasks);

        for (Map.Entry<Integer, Integer> entry : plan.taskIdToResourceIndex().entrySet()) {
            int taskId = entry.getKey();
            int resourceIndex = entry.getValue();
            int vmId = allVms.get(resourceIndex).getId();
            bindCloudletToVm(taskId, vmId);
            taskIdToVmId.put(taskId, vmId);
            CloudTask task = byId.get(taskId);
            if (task != null) {
                task.setAssignedCloudId(vmIdToCloudId.getOrDefault(vmId, -1));
            }
        }
    }

    /** @return the cloud (datacenter id) a task has been assigned to, or -1 if it has no assignment yet. */
    public int selectCloud(int taskId) {
        Integer vmId = taskIdToVmId.get(taskId);
        return (vmId == null) ? -1 : vmIdToCloudId.getOrDefault(vmId, -1);
    }

    /** @return the VM id a task has been assigned to, or -1 if it has no assignment yet. */
    public int selectVM(int taskId) {
        return taskIdToVmId.getOrDefault(taskId, -1);
    }

    /**
     * Best-effort re-assignment: only meaningful while the task is still
     * queued in this broker (i.e. before CloudSim has dispatched it to a
     * VM). CloudSim's Cloudlet execution model has no notion of relocating
     * an already-running Cloudlet to a different VM; genuine live migration
     * in this framework happens one layer down, at the VM/host level, via
     * CloudSim's power-aware {@code VmAllocationPolicyMigration*} policies
     * -- a separate mechanism from this method, which only ever moves a
     * not-yet-started task's binding.
     *
     * @return true if the task was still queued and was re-bound, false if it had already been dispatched
     */
    public boolean migrateTask(int taskId, int newVmId) {
        for (Cloudlet c : getCloudletList()) {
            if (c.getCloudletId() == taskId) {
                bindCloudletToVm(taskId, newVmId);
                taskIdToVmId.put(taskId, newVmId);
                if (c instanceof CloudTask ct) {
                    ct.setAssignedCloudId(vmIdToCloudId.getOrDefault(newVmId, -1));
                }
                return true;
            }
        }
        return false;
    }

    /** @return the datacenter (cloud) ids known to this broker, discovered from its {@link MultiCloudEnvironment} at construction time. */
    public List<Integer> discoverClouds() {
        return environment.getClouds().stream()
                .map(cloud -> cloud.getDatacenter().getId())
                .toList();
    }

    /** @return how many of this broker's submitted-but-not-yet-returned tasks are currently assigned to {@code vmId}. */
    public int getQueueLength(int vmId) {
        int count = 0;
        for (Cloudlet c : getCloudletList()) {
            if (c.getGuestId() == vmId) {
                count++;
            }
        }
        return count;
    }

    /** @return an immutable snapshot of every task's current VM assignment, keyed by task (cloudlet) id. */
    public Map<Integer, Integer> collectMetrics() {
        return Collections.unmodifiableMap(taskIdToVmId);
    }

    public MultiCloudEnvironment getEnvironment() {
        return environment;
    }
}
