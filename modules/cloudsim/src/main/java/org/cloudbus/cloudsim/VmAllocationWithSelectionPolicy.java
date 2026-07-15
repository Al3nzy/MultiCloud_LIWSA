package org.cloudbus.cloudsim;

import org.cloudbus.cloudsim.selectionPolicies.SelectionPolicy;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.core.HostEntity;

import java.util.*;

/**
 * Created by sareh on 16/12/15.
 * Modified by Remo Andreoli (March 2024)
 * Modified by Mohammed Ala'anzy 15 July 2026
 */
public class VmAllocationWithSelectionPolicy extends VmAllocationPolicy {
    private SelectionPolicy<HostEntity> selectionPolicy;

    private Set<HostEntity> excludedHostCandidates;

    public VmAllocationWithSelectionPolicy(List<? extends HostEntity> list, SelectionPolicy<HostEntity> selectionPolicy) {
        super(list);
        setExcludedHostCandidates(new HashSet<>());
        setSelectionPolicy(selectionPolicy);
    }

    public SelectionPolicy<HostEntity> getSelectionPolicy() { return selectionPolicy; }
    public void setSelectionPolicy(SelectionPolicy<HostEntity> selectionPolicy) { this.selectionPolicy = selectionPolicy; }

    public Set<HostEntity> getExcludedHostCandidates() { return excludedHostCandidates; }
    public void setExcludedHostCandidates(Set<HostEntity> excludedHostCandidates) { this.excludedHostCandidates = excludedHostCandidates; }

    @Override
    public HostEntity findHostForGuest(GuestEntity guest) {
        clearExcludedHostCandidates();
        int tries = 0;

        do{
            HostEntity selectedHost = getSelectionPolicy().select(getHostList(), guest, excludedHostCandidates);
            if(selectedHost == null){
                return null;
            }

            if (selectedHost.isSuitableForGuest(guest)) {
                return selectedHost;
            } else {
                System.out.println("REJECT host#" + selectedHost.getId()
                    + " peCap=" + selectedHost.getGuestScheduler().getPeCapacity()
                    + " reqMaxMips=" + guest.getCurrentRequestedMaxMips()
                    + " availMips=" + selectedHost.getGuestScheduler().getAvailableMips()
                    + " reqTotalMips=" + guest.getCurrentRequestedTotalMips()
                    + " ramOK=" + selectedHost.getGuestRamProvisioner().isSuitableForGuest(guest, guest.getCurrentRequestedRam())
                    + " bwOK=" + selectedHost.getGuestBwProvisioner().isSuitableForGuest(guest, guest.getCurrentRequestedBw()));
                excludedHostCandidates.add(selectedHost);
                tries++;
            }
            } while (tries < getHostList().size());
        return null;
    }

    public void clearExcludedHostCandidates() {
        excludedHostCandidates.clear();
    }
}
