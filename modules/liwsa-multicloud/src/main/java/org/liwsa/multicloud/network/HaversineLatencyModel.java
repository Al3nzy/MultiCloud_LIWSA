package org.liwsa.multicloud.network;

import org.liwsa.multicloud.model.CloudRegion;

/**
 * Estimates inter-cloud network latency from the great-circle (Haversine)
 * distance between two {@link CloudRegion}s, rather than CloudSim 7G's
 * built-in {@code geolocation} package (which is IP + MaxMind-GeoIP2-database
 * driven and needs a licensed database file this framework doesn't ship).
 *
 * <p>The estimate is deliberately simple and explicitly labelled as such:
 * a fixed processing/routing overhead plus propagation delay at roughly
 * 0.667c (the typical effective speed of light in fibre), which is the same
 * order-of-magnitude approach used in the geo-distributed-systems latency
 * literature for back-of-envelope estimates. It is not a substitute for a
 * measured latency matrix if a study needs one.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class HaversineLatencyModel {

    private static final double EARTH_RADIUS_KM = 6371.0;
    /** Effective propagation speed in optical fibre (~0.667c). */
    private static final double FIBER_SPEED_KM_PER_MS = 200.0;
    /** Fixed routing/switching overhead per hop, illustrative. */
    private static final double BASE_PROCESSING_OVERHEAD_MS = 5.0;

    private HaversineLatencyModel() { }

    public static double distanceKm(CloudRegion a, CloudRegion b) {
        return distanceKm(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
    }

    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double rLat1 = Math.toRadians(lat1);
        double rLat2 = Math.toRadians(lat2);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
        return EARTH_RADIUS_KM * c;
    }

    /** One-way estimated network latency in milliseconds between two regions (same region = overhead only). */
    public static double oneWayLatencyMs(CloudRegion a, CloudRegion b) {
        if (a == b) {
            return BASE_PROCESSING_OVERHEAD_MS / 2.0;
        }
        return BASE_PROCESSING_OVERHEAD_MS + distanceKm(a, b) / FIBER_SPEED_KM_PER_MS;
    }

    public static double roundTripMs(CloudRegion a, CloudRegion b) {
        return 2 * oneWayLatencyMs(a, b);
    }
}
