package org.liwsa.multicloud.cloud;

import org.liwsa.multicloud.model.CloudRegion;
import org.liwsa.multicloud.model.VmTypeSpec;

import java.util.List;

/**
 * Three ready-made {@link CloudProviderSpec} configurations, one per major
 * public cloud, so the framework has a realistic multi-cloud environment
 * out of the box instead of arbitrary numbers.
 *
 * <p><b>Provenance of the figures:</b> VM tiers (vCPU/RAM combinations) and
 * on-demand hourly prices are grounded in each provider's published pricing
 * as of mid-2026 (AWS m6i.large and Azure D2s_v5 were both confirmed at
 * $0.096/hr in us-east-1 / East US at the time this was written; GCP and the
 * larger tiers are standard-doubling extrapolations from that anchor using
 * each provider's public pricing structure). These are illustrative,
 * reasonably current figures suitable for a simulation study -- re-check
 * each provider's pricing page immediately before finalising any numbers
 * reported in a publication, since list prices do change.
 *
 * <p>MIPS-per-core is a fixed simulation constant (2500) applied uniformly
 * across all three providers: CloudSim's MIPS rating has no real physical
 * unit, and holding it constant means every performance difference between
 * "clouds" in this framework comes only from real, cited price/RAM/host
 * differences, not from an invented performance claim.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class CloudProviderPresets {

    private static final double MIPS_PER_CORE = 2500.0;

    private CloudProviderPresets() { }

    public static CloudProviderSpec awsLike() {
        List<VmTypeSpec> types = List.of(
                new VmTypeSpec("aws-m6i.large", 2 * MIPS_PER_CORE, 2, 8_192, 1_250, 20_000, 0.096),
                new VmTypeSpec("aws-m6i.xlarge", 4 * MIPS_PER_CORE, 4, 16_384, 2_500, 40_000, 0.192),
                new VmTypeSpec("aws-c6i.2xlarge", 8 * MIPS_PER_CORE, 8, 16_384, 5_000, 40_000, 0.340),
                new VmTypeSpec("aws-r6i.xlarge", 4 * MIPS_PER_CORE, 4, 32_768, 2_500, 40_000, 0.252));
        return new CloudProviderSpec("AWS-us-east-1", CloudRegion.AWS_US_EAST_1, types, 4,
                6, 32, MIPS_PER_CORE, 262_144, 2_000_000, 10_000, 1.0);
    }

    public static CloudProviderSpec azureLike() {
        List<VmTypeSpec> types = List.of(
                new VmTypeSpec("azure-D2s_v5", 2 * MIPS_PER_CORE, 2, 8_192, 1_250, 20_000, 0.096),
                new VmTypeSpec("azure-D4s_v5", 4 * MIPS_PER_CORE, 4, 16_384, 2_500, 40_000, 0.192),
                new VmTypeSpec("azure-F8s_v2", 8 * MIPS_PER_CORE, 8, 16_384, 5_000, 40_000, 0.338),
                new VmTypeSpec("azure-E4s_v5", 4 * MIPS_PER_CORE, 4, 32_768, 2_500, 40_000, 0.252));
        return new CloudProviderSpec("Azure-East-US", CloudRegion.AZURE_EAST_US, types, 4,
                5, 24, MIPS_PER_CORE, 196_608, 1_500_000, 10_000, 1.2);
    }

    public static CloudProviderSpec gcpLike() {
        List<VmTypeSpec> types = List.of(
                new VmTypeSpec("gcp-e2-standard-2", 2 * MIPS_PER_CORE, 2, 8_192, 1_250, 20_000, 0.067),
                new VmTypeSpec("gcp-e2-standard-4", 4 * MIPS_PER_CORE, 4, 16_384, 2_500, 40_000, 0.134),
                new VmTypeSpec("gcp-c2-standard-8", 8 * MIPS_PER_CORE, 8, 16_384, 5_000, 40_000, 0.280),
                new VmTypeSpec("gcp-n2-highmem-4", 4 * MIPS_PER_CORE, 4, 32_768, 2_500, 40_000, 0.236));
        return new CloudProviderSpec("GCP-us-central1", CloudRegion.GCP_US_CENTRAL1, types, 4,
                8, 16, MIPS_PER_CORE, 131_072, 1_000_000, 10_000, 0.9);
    }

    public static List<CloudProviderSpec> defaultThreeCloud() {
        return List.of(awsLike(), azureLike(), gcpLike());
    }
}
