package org.impulsegraph.vm;

import org.impulsegraph.core.csr.GraphSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.*;

/**
 * Runtime Execution Context for Impulse VM.
 * Holds off-heap buffer pools, bitsets, vectors, value maps, and graph snapshot references.
 */
public final class VmQueryContext implements AutoCloseable {

    private final GraphSnapshot snapshot;
    private final Arena arena;

    // Bitset Pool
    private final List<BitSet> bitsets = new ArrayList<>();
    private final BitSet freeBitsetHandles = new BitSet();

    // Vector Pools
    private final List<float[]> floatVectors = new ArrayList<>();
    private final List<double[]> doubleVectors = new ArrayList<>();
    private final List<long[]> longVectors = new ArrayList<>();
    private final List<String[]> stringVectors = new ArrayList<>();

    // Value Map Pool
    private final List<Map<Integer, Object>> valueMaps = new ArrayList<>();

    public VmQueryContext(GraphSnapshot snapshot, Arena arena) {
        this.snapshot = snapshot;
        this.arena = (arena != null) ? arena : Arena.ofShared();
    }

    public GraphSnapshot snapshot() {
        return snapshot;
    }

    public Arena arena() {
        return arena;
    }

    /**
     * Allocate a 640-byte off-heap MemorySegment representing impulse_vm_state_t.
     */
    public MemorySegment allocateStateSegment() {
        MemorySegment state = arena.allocate(VmStateLayout.VM_STATE_LAYOUT);
        state.fill((byte) 0);
        return state;
    }

    // --- Bitset Management ---

    public int acquireBitset() {
        int handle = freeBitsetHandles.nextSetBit(0);
        if (handle >= 0) {
            freeBitsetHandles.clear(handle);
            bitsets.get(handle).clear();
            return handle;
        }
        int newHandle = bitsets.size();
        bitsets.add(new BitSet());
        return newHandle;
    }

    public void releaseBitset(int handle) {
        if (handle >= 0 && handle < bitsets.size()) {
            bitsets.get(handle).clear();
            freeBitsetHandles.set(handle);
        }
    }

    public BitSet getBitset(int handle) {
        if (handle >= 0 && handle < bitsets.size()) {
            return bitsets.get(handle);
        }
        return null;
    }

    // --- Vector Management ---

    public int registerFloatVector(float[] vec) {
        int handle = floatVectors.size();
        floatVectors.add(vec);
        return handle;
    }

    public float[] getFloatVector(int handle) {
        return (handle >= 0 && handle < floatVectors.size()) ? floatVectors.get(handle) : null;
    }

    public int registerDoubleVector(double[] vec) {
        int handle = doubleVectors.size();
        doubleVectors.add(vec);
        return handle;
    }

    public double[] getDoubleVector(int handle) {
        return (handle >= 0 && handle < doubleVectors.size()) ? doubleVectors.get(handle) : null;
    }

    public int registerLongVector(long[] vec) {
        int handle = longVectors.size();
        longVectors.add(vec);
        return handle;
    }

    public long[] getLongVector(int handle) {
        return (handle >= 0 && handle < longVectors.size()) ? longVectors.get(handle) : null;
    }

    public int registerStringVector(String[] vec) {
        int handle = stringVectors.size();
        stringVectors.add(vec);
        return handle;
    }

    public String[] getStringVector(int handle) {
        return (handle >= 0 && handle < stringVectors.size()) ? stringVectors.get(handle) : null;
    }

    // --- Value Map Management ---

    public int registerValueMap(Map<Integer, Object> map) {
        int handle = valueMaps.size();
        valueMaps.add(map);
        return handle;
    }

    public Map<Integer, Object> getValueMap(int handle) {
        return (handle >= 0 && handle < valueMaps.size()) ? valueMaps.get(handle) : null;
    }

    @Override
    public void close() {
        bitsets.clear();
        floatVectors.clear();
        doubleVectors.clear();
        longVectors.clear();
        stringVectors.clear();
        valueMaps.clear();
    }
}
