package org.liwsa.multicloud.model;

/**
 * A geographically-anchored cloud region, used both for display purposes and
 * as the coordinate source for {@code HaversineLatencyModel} (see the
 * {@code network} package), which derives inter-cloud network latency from
 * great-circle distance instead of requiring a licensed IP-geolocation
 * database.
 *
 * <p>Coordinates approximate the public, well-documented locations of each
 * provider's named region (city-level precision is sufficient for a
 * propagation-delay estimate; exact datacenter addresses are not published
 * by any provider).
 *
 * @author LIWSA Multi-Cloud Framework
 */
public enum CloudRegion {

    AWS_US_EAST_1("aws-us-east-1", "AWS US East (N. Virginia)", 39.0438, -77.4874),
    AWS_EU_WEST_1("aws-eu-west-1", "AWS EU West (Ireland)", 53.3498, -6.2603),
    AWS_AP_SOUTHEAST_1("aws-ap-southeast-1", "AWS Asia Pacific (Singapore)", 1.3521, 103.8198),

    AZURE_EAST_US("azure-east-us", "Azure East US (Virginia)", 37.3719, -79.8164),
    AZURE_WEST_EUROPE("azure-west-europe", "Azure West Europe (Netherlands)", 52.3676, 4.9041),
    AZURE_SOUTHEAST_ASIA("azure-southeast-asia", "Azure Southeast Asia (Singapore)", 1.3521, 103.8198),

    GCP_US_CENTRAL1("gcp-us-central1", "GCP us-central1 (Iowa)", 41.2619, -95.8608),
    GCP_EUROPE_WEST1("gcp-europe-west1", "GCP europe-west1 (Belgium)", 50.4542, 3.8231),
    GCP_ASIA_SOUTHEAST1("gcp-asia-southeast1", "GCP asia-southeast1 (Singapore)", 1.3521, 103.8198);

    private final String id;
    private final String displayName;
    private final double latitude;
    private final double longitude;

    CloudRegion(String id, String displayName, double latitude, double longitude) {
        this.id = id;
        this.displayName = displayName;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }
}
