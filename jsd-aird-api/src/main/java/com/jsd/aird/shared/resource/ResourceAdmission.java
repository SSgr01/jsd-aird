package com.jsd.aird.shared.resource;

/** HTTP preparation and leased worker tasks compete for the same per-instance heavy slots. */
public final class ResourceAdmission {
    public final BoundedGate requests,writes,heavy,streams;
    public ResourceAdmission(CapacityProperties limits){
        requests=new BoundedGate(limits.requests(),limits.requestQueue());writes=new BoundedGate(limits.writes(),0);
        heavy=new BoundedGate(limits.heavy(),limits.heavyQueue());streams=new BoundedGate(limits.streams(),0);
    }
}
