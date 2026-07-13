package org.liwsa.multicloud;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.liwsa.multicloud.algorithm.SchedulingResult;
import org.liwsa.multicloud.algorithm.liwsa.LiwsaTaskPlanningAlgorithm;
import org.liwsa.multicloud.broker.MultiCloudBroker;
import org.liwsa.multicloud.cloud.CloudProviderFactory;
import org.liwsa.multicloud.cloud.CloudProviderPresets;
import org.liwsa.multicloud.cloud.MultiCloudEnvironment;
import org.liwsa.multicloud.cloud.ProvisionedCloud;
import org.liwsa.multicloud.model.CloudTask;
import org.liwsa.multicloud.model.TaskPriority;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * End-to-end smoke test, unlike {@link Demo} (which only exercises the
 * algorithm layer against synthetic in-memory resources): this one actually
 * runs a CloudSim simulation across three provisioned clouds
 * ({@link CloudProviderPresets}), asks LIWSA-Task to plan the schedule, has
 * {@link MultiCloudBroker} execute that plan, and compares the algorithm's
 * predicted makespan against what CloudSim actually simulated -- the two
 * should match closely (small floating-point/timing differences aside),
 * which is a useful sanity check that the decoder's assumptions (insertion-
 * based per-VM scheduling, MIPS-based duration) line up with how CloudSim
 * itself executes cloudlets.
 *
 * <p>Run with: {@code mvn -q exec:java -Dexec.mainClass=org.liwsa.multicloud.CloudSimDemo}
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class CloudSimDemo {

    private CloudSimDemo() { }

    public static void main(String[] args) throws Exception {
        CloudSim.init(1, Calendar.getInstance(), false);

        List<ProvisionedCloud> clouds = CloudProviderFactory.buildAll(CloudProviderPresets.defaultThreeCloud());
        MultiCloudEnvironment env = new MultiCloudEnvironment(clouds);
        System.out.println("Provisioned " + clouds.size() + " clouds, "
                + env.getAllVms().size() + " VMs total.");

        List<CloudTask> tasks = buildSyntheticTasks(60);

        LiwsaTaskPlanningAlgorithm algo = new LiwsaTaskPlanningAlgorithm(tasks, env.getResourceCandidates());
        algo.setRandomSeed(42);
        SchedulingResult plan = algo.run();
        System.out.printf("LIWSA-Task plan: predicted makespan=%.2f  predicted cost=%.4f%n",
                plan.getMakespan(), plan.getCost());

        MultiCloudBroker broker = new MultiCloudBroker("broker-0", env, -1);
        broker.submitTasks(plan, tasks);

        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        List<Cloudlet> finished = broker.getCloudletReceivedList();
        double simulatedMakespan = 0.0;
        for (Cloudlet c : finished) {
            simulatedMakespan = Math.max(simulatedMakespan, c.getExecFinishTime());
        }
        System.out.println("Simulation finished: " + finished.size() + " of " + tasks.size() + " tasks completed.");
        System.out.printf("CloudSim-simulated makespan=%.2f (planner predicted %.2f)%n",
                simulatedMakespan, plan.getMakespan());
    }

    private static List<CloudTask> buildSyntheticTasks(int count) {
        Random rnd = new Random(7);
        List<CloudTask> tasks = new ArrayList<>();
        TaskPriority[] priorities = TaskPriority.values();
        for (int i = 0; i < count; i++) {
            long length = 5_000 + rnd.nextInt(45_000);
            tasks.add(new CloudTask.Builder(i, length)
                    .pes(1)
                    .priority(priorities[rnd.nextInt(priorities.length)])
                    .arrivalTime(rnd.nextInt(50))
                    .build());
        }
        return tasks;
    }
}
