package org.impulsegraph.storage.stats;

import java.util.HashMap;
import java.util.Map;

/**
 * Single-pass collector for computing Query Planner statistics.
 */
public class StreamingStatsCollector {
    
    // Relation stats: relationId -> DegreeDistributionSketch
    private final Map<Integer, DegreeDistributionSketch> outDegreeSketches = new HashMap<>();
    
    // Domain Attribute stats: domainId -> (attrId -> sketches)
    private final Map<Integer, Map<Integer, NumericStat>> domainNumericStats = new HashMap<>();
    private final Map<Integer, Map<Integer, StringStat>> domainStringStats = new HashMap<>();
    
    // Relation Attribute stats: relationId -> (attrId -> sketches)
    private final Map<Integer, Map<Integer, NumericStat>> relationNumericStats = new HashMap<>();
    private final Map<Integer, Map<Integer, StringStat>> relationStringStats = new HashMap<>();

    public void observeOutDegree(int relationId, int degree) {
        outDegreeSketches.computeIfAbsent(relationId, k -> new DegreeDistributionSketch(10000)).offer(degree);
    }
    
    public void observeDomainNumeric(int domainId, int attrId, double value) {
        domainNumericStats.computeIfAbsent(domainId, k -> new HashMap<>())
            .computeIfAbsent(attrId, k -> new NumericStat())
            .offer(value);
    }
    
    public void observeDomainString(int domainId, int attrId, String value) {
        domainStringStats.computeIfAbsent(domainId, k -> new HashMap<>())
            .computeIfAbsent(attrId, k -> new StringStat())
            .offer(value);
    }
    
    public void observeRelationNumeric(int relationId, int attrId, double value) {
        relationNumericStats.computeIfAbsent(relationId, k -> new HashMap<>())
            .computeIfAbsent(attrId, k -> new NumericStat())
            .offer(value);
    }
    
    public void observeRelationString(int relationId, int attrId, String value) {
        relationStringStats.computeIfAbsent(relationId, k -> new HashMap<>())
            .computeIfAbsent(attrId, k -> new StringStat())
            .offer(value);
    }

    public Map<String, String> toJsonMap() {
        Map<String, String> result = new HashMap<>();
        
        for (Map.Entry<Integer, DegreeDistributionSketch> e : outDegreeSketches.entrySet()) {
            DegreeDistributionSketch sketch = e.getValue();
            String json = String.format("{\"min\":%d,\"max\":%d,\"p50\":%d,\"p90\":%d,\"p99\":%d,\"zero_count\":%d}",
                sketch.getMin(), sketch.getMax(), sketch.getPercentile(0.50), sketch.getPercentile(0.90), sketch.getPercentile(0.99), sketch.getZeroCount());
            result.put("impulse.stats.relation." + e.getKey() + ".out_degree", json);
        }
        
        // Similarly serialize numeric and string stats
        for (Map.Entry<Integer, Map<Integer, NumericStat>> domainEntry : domainNumericStats.entrySet()) {
            for (Map.Entry<Integer, NumericStat> attrEntry : domainEntry.getValue().entrySet()) {
                result.put("impulse.stats.domain." + domainEntry.getKey() + ".attr." + attrEntry.getKey(), attrEntry.getValue().toJson());
            }
        }
        for (Map.Entry<Integer, Map<Integer, StringStat>> domainEntry : domainStringStats.entrySet()) {
            for (Map.Entry<Integer, StringStat> attrEntry : domainEntry.getValue().entrySet()) {
                result.put("impulse.stats.domain." + domainEntry.getKey() + ".attr." + attrEntry.getKey(), attrEntry.getValue().toJson());
            }
        }
        for (Map.Entry<Integer, Map<Integer, NumericStat>> relEntry : relationNumericStats.entrySet()) {
            for (Map.Entry<Integer, NumericStat> attrEntry : relEntry.getValue().entrySet()) {
                result.put("impulse.stats.relation." + relEntry.getKey() + ".attr." + attrEntry.getKey(), attrEntry.getValue().toJson());
            }
        }
        for (Map.Entry<Integer, Map<Integer, StringStat>> relEntry : relationStringStats.entrySet()) {
            for (Map.Entry<Integer, StringStat> attrEntry : relEntry.getValue().entrySet()) {
                result.put("impulse.stats.relation." + relEntry.getKey() + ".attr." + attrEntry.getKey(), attrEntry.getValue().toJson());
            }
        }
        
        return result;
    }
    
    private static class NumericStat {
        final HyperLogLogSketch hll = new HyperLogLogSketch(10);
        final EquiDepthHistogramBuilder hist = new EquiDepthHistogramBuilder(10000);
        
        void offer(double val) {
            hll.offerLong(Double.doubleToRawLongBits(val));
            hist.offer(val);
        }
        
        String toJson() {
            double[] buckets = hist.buildBuckets(20);
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < buckets.length; i++) {
                sb.append(buckets[i]);
                if (i < buckets.length - 1) sb.append(",");
            }
            sb.append("]");
            return String.format("{\"approx_distinct\":%d,\"min\":%f,\"max\":%f,\"null_count\":%d,\"histogram\":%s}",
                hll.estimate(), hist.getMin(), hist.getMax(), hist.getNullCount(), sb.toString());
        }
    }
    
    private static class StringStat {
        final HyperLogLogSketch hll = new HyperLogLogSketch(10);
        final HeavyHittersSketch topK = new HeavyHittersSketch(20);
        long nullCount = 0;
        
        void offer(String val) {
            if (val == null) {
                nullCount++;
                return;
            }
            hll.offerString(val);
            topK.offer(val);
        }
        
        String toJson() {
            StringBuilder topKSb = new StringBuilder();
            topKSb.append("{");
            Map<String, Long> top = topK.getTopK();
            int i = 0;
            for (Map.Entry<String, Long> e : top.entrySet()) {
                topKSb.append("\"").append(escapeJson(e.getKey())).append("\":").append(e.getValue());
                if (i < top.size() - 1) topKSb.append(",");
                i++;
            }
            topKSb.append("}");
            return String.format("{\"approx_distinct\":%d,\"null_count\":%d,\"top_k\":%s}",
                hll.estimate(), nullCount, topKSb.toString());
        }
        
        private String escapeJson(String s) {
            return s.replace("\"", "\\\"");
        }
    }
}
