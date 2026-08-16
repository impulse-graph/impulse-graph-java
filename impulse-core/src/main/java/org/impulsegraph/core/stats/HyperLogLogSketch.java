package org.impulsegraph.core.stats;

/**
 * Minimal zero-dependency HyperLogLog implementation for distinct count estimation.
 */
public class HyperLogLogSketch {
    private final int p;
    private final int m;
    private final byte[] registers;

    public HyperLogLogSketch(int p) {
        if (p < 4 || p > 16) throw new IllegalArgumentException("p must be between 4 and 16");
        this.p = p;
        this.m = 1 << p;
        this.registers = new byte[m];
    }

    public void offer(long hash) {
        int idx = (int) (hash >>> (64 - p));
        int rank = Long.numberOfLeadingZeros((hash << p) | (1L << (p - 1))) + 1;
        if (rank > registers[idx]) {
            registers[idx] = (byte) rank;
        }
    }
    
    public void offerString(String val) {
        if (val == null) return;
        offer(murmur3_64(val.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    
    public void offerLong(long val) {
        // simple mix
        val ^= val >>> 33;
        val *= 0xff51afd7ed558ccdL;
        val ^= val >>> 33;
        val *= 0xc4ceb9fe1a85ec53L;
        val ^= val >>> 33;
        offer(val);
    }

    public long estimate() {
        double sum = 0.0;
        int zeroCount = 0;
        for (byte r : registers) {
            sum += 1.0 / (1L << r);
            if (r == 0) zeroCount++;
        }
        
        double alpha;
        switch (p) {
            case 4: alpha = 0.673; break;
            case 5: alpha = 0.697; break;
            case 6: alpha = 0.709; break;
            default: alpha = 0.7213 / (1.0 + 1.079 / m); break;
        }
        
        double estimate = alpha * m * m / sum;
        
        if (estimate <= 2.5 * m) {
            if (zeroCount != 0) {
                estimate = m * Math.log((double) m / zeroCount);
            }
        }
        return (long) estimate;
    }
    
    private static long murmur3_64(byte[] data) {
        long h = 0x123456789ABCDEFL;
        long c1 = 0x87c37b91114253d5L;
        long c2 = 0x4cf5ad432745937fL;
        
        int length = data.length;
        int numBlocks = length / 8;
        for (int i = 0; i < numBlocks; i++) {
            long k = 0;
            for (int j = 0; j < 8; j++) {
                k |= ((long) (data[i * 8 + j] & 0xFF)) << (j * 8);
            }
            k *= c1;
            k = Long.rotateLeft(k, 31);
            k *= c2;
            h ^= k;
            h = Long.rotateLeft(h, 27);
            h = h * 5 + 0x52dce729;
        }
        
        long k1 = 0;
        for (int i = length - 1; i >= numBlocks * 8; i--) {
            k1 <<= 8;
            k1 |= (data[i] & 0xFF);
        }
        k1 *= c1;
        k1 = Long.rotateLeft(k1, 31);
        k1 *= c2;
        h ^= k1;
        
        h ^= length;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        
        return h;
    }
}
