package org.liwsa.multicloud.model;

/**
 * A catalog entry describing one purchasable VM type/size (e.g. "an
 * AWS m6i.large"): its capacity and its on-demand hourly price.
 *
 * @author LIWSA Multi-Cloud Framework
 */
public final class VmTypeSpec {

    private final String name;
    private final double mips;
    private final int pes;
    private final long ramMb;
    private final long bwMbps;
    private final long storageMb;
    private final double costPerHour;

    public VmTypeSpec(String name, double mips, int pes, long ramMb, long bwMbps,
                       long storageMb, double costPerHour) {
        this.name = name;
        this.mips = mips;
        this.pes = pes;
        this.ramMb = ramMb;
        this.bwMbps = bwMbps;
        this.storageMb = storageMb;
        this.costPerHour = costPerHour;
    }

    public String getName() { return name; }
    public double getMips() { return mips; }
    public int getPes() { return pes; }
    public long getRamMb() { return ramMb; }
    public long getBwMbps() { return bwMbps; }
    public long getStorageMb() { return storageMb; }
    public double getCostPerHour() { return costPerHour; }
    public double getCostPerSecond() { return costPerHour / 3600.0; }

    @Override
    public String toString() {
        return name + "{mips=" + mips + ", pes=" + pes + ", ramMb=" + ramMb + ", $/hr=" + costPerHour + "}";
    }
}
