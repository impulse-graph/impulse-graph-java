package org.impulsegraph.storage.stats;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Space-Saving algorithm for finding top-k frequent elements in a stream.
 */
public class HeavyHittersSketch {
    private final int k;
    private final Map<String, Counter> counters;

    public HeavyHittersSketch(int k) {
        this.k = k;
        this.counters = new HashMap<>(k * 2);
    }

    public void offer(String item) {
        if (item == null) return;
        Counter c = counters.get(item);
        if (c != null) {
            c.count++;
        } else if (counters.size() < k) {
            counters.put(item, new Counter(item, 1));
        } else {
            // Evict minimum
            Counter min = null;
            for (Counter candidate : counters.values()) {
                if (min == null || candidate.count < min.count) {
                    min = candidate;
                }
            }
            if (min != null) {
                counters.remove(min.item);
                counters.put(item, new Counter(item, min.count + 1));
            }
        }
    }

    public Map<String, Long> getTopK() {
        Map<String, Long> result = new HashMap<>();
        PriorityQueue<Counter> pq = new PriorityQueue<>((a, b) -> Long.compare(b.count, a.count));
        pq.addAll(counters.values());
        
        while (!pq.isEmpty()) {
            Counter c = pq.poll();
            result.put(c.item, c.count);
        }
        return result;
    }

    private static class Counter {
        final String item;
        long count;

        Counter(String item, long count) {
            this.item = item;
            this.count = count;
        }
    }
}
