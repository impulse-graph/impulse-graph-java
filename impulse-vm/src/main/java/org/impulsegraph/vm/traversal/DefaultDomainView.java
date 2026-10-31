package org.impulsegraph.vm.traversal;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.api.traversal.DomainView;
import org.impulsegraph.api.traversal.MultiLaneBuilder;
import org.impulsegraph.api.traversal.Traversal;
import org.impulsegraph.compiler.ast.ScmCollect;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmVectorAnnSearch;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.vm.CompiledQuery;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Domain Anchor Context implementation.
 */
public class DefaultDomainView implements DomainView {

	private static final java.util.Map<ImpulseGraphSnapshot, java.util.concurrent.ConcurrentHashMap<String, DomainView>> DOMAIN_CACHE = java.util.Collections
			.synchronizedMap(new java.util.WeakHashMap<>());

	private record CachedSnapshotDomains(ImpulseGraphSnapshot snapshot,
			java.util.concurrent.ConcurrentHashMap<String, DomainView> domainMap) {
	}

	private static volatile CachedSnapshotDomains lastSnapshotDomains;

	public static DomainView getOrCreate(ImpulseGraphSnapshot snapshot, String domainName, int domainId,
			long nodeCount) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		CachedSnapshotDomains cached = lastSnapshotDomains;
		java.util.concurrent.ConcurrentHashMap<String, DomainView> domainMap;
		if (cached != null && cached.snapshot == snapshot) {
			domainMap = cached.domainMap;
		} else {
			domainMap = DOMAIN_CACHE.computeIfAbsent(snapshot, k -> new java.util.concurrent.ConcurrentHashMap<>());
			lastSnapshotDomains = new CachedSnapshotDomains(snapshot, domainMap);
		}
		String key = (domainName != null) ? domainName : "default";
		DomainView existing = domainMap.get(key);
		if (existing != null) {
			return existing;
		}
		return domainMap.computeIfAbsent(key, k -> {
			long resolvedCount = nodeCount;
			if (resolvedCount < 0) {
				resolvedCount = snapshot.getNodeCount(domainName);
				if (resolvedCount <= 0 && !snapshot.getAllRelationSnapshots().isEmpty()) {
					var firstRel = snapshot.getRelationSnapshot(0);
					if (firstRel == null) {
						firstRel = snapshot.getAllRelationSnapshots().values().iterator().next();
					}
					if (firstRel != null) {
						resolvedCount = firstRel.getNodeCount();
					}
				}
			}
			return new DefaultDomainView(snapshot, k, domainId, Math.max(0L, resolvedCount));
		});
	}

	private final ImpulseGraphSnapshot snapshot;
	private final String domainName;
	private final int domainId;
	private final long nodeCount;

	public DefaultDomainView(ImpulseGraphSnapshot snapshot, String domainName, int domainId, long nodeCount) {
		this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
		this.domainName = (domainName != null) ? domainName : "node";
		this.domainId = domainId;
		this.nodeCount = nodeCount;
	}

	@Override
	public String domainName() {
		return domainName;
	}

	@Override
	public int domainId() {
		return domainId;
	}

	@Override
	public long nodeCount() {
		return nodeCount;
	}

	@Override
	public Traversal<ImpulseBitSet> all() {
		ImpulseBitSet bs = new OffHeapBitSet(Arena.ofAuto(), (int) Math.max(1, nodeCount));
		for (int i = 0; i < nodeCount; i++) {
			bs.set(i);
		}
		return new DefaultTraversal<>(snapshot, domainName, bs);
	}

	@Override
	public long toDenseId(String key) {
		if (key == null)
			return -1;

		// Check if snapshot metadata contains key mapping
		String metaKey = "domain." + domainName + ".key." + key;
		String metaVal = snapshot.getMetadata(metaKey);
		if (metaVal != null) {
			try {
				long val = Long.parseLong(metaVal);
				if (val >= 0 && (nodeCount <= 0 || val < nodeCount))
					return val;
			} catch (NumberFormatException ignored) {
			}
		}

		java.util.Map<String, String> metaMap = snapshot.getMetadataMap();
		if (metaMap != null) {
			String prefix1 = "domain." + domainName + ".id.";
			String prefix2 = "key." + domainName + ".";
			for (java.util.Map.Entry<String, String> entry : metaMap.entrySet()) {
				if (key.equals(entry.getValue())) {
					String k = entry.getKey();
					String numStr = null;
					if (k.startsWith(prefix1)) {
						numStr = k.substring(prefix1.length());
					} else if (k.startsWith(prefix2)) {
						numStr = k.substring(prefix2.length());
					}
					if (numStr != null) {
						try {
							long val = Long.parseLong(numStr);
							if (val >= 0 && (nodeCount <= 0 || val < nodeCount))
								return val;
						} catch (NumberFormatException ignored) {
						}
					}
				}
			}
		}

		// Fallback 1: Parse direct numeric integer
		try {
			long val = Long.parseLong(key);
			if (val >= 0 && (nodeCount <= 0 || val < nodeCount))
				return val;
		} catch (NumberFormatException ignored) {
		}

		// Fallback 2: Parse domain prefix (e.g. "User_42", "User#42", "Compound_10")
		int sep = Math.max(key.lastIndexOf('_'), key.lastIndexOf('#'));
		if (sep >= 0 && sep < key.length() - 1) {
			try {
				long val = Long.parseLong(key.substring(sep + 1));
				if (val >= 0 && (nodeCount <= 0 || val < nodeCount))
					return val;
			} catch (NumberFormatException ignored) {
			}
		}

		return -1;
	}

	@Override
	public String toKey(long denseId) {
		String metaKey = "domain." + domainName + ".id." + denseId;
		String metaVal = snapshot.getMetadata(metaKey);
		if (metaVal != null && !metaVal.isBlank())
			return metaVal;
		metaVal = snapshot.getMetadata("key." + domainName + "." + denseId);
		if (metaVal != null && !metaVal.isBlank())
			return metaVal;
		return domainName + "_" + denseId;
	}

	@Override
	public Traversal<ImpulseBitSet> from(long nodeId) {
		return new DefaultTraversal<>(snapshot, domainName, nodeId);
	}

	@Override
	public Traversal<ImpulseBitSet> from(long... nodeIds) {
		return new DefaultTraversal<>(snapshot, domainName, nodeIds);
	}

	@Override
	public Traversal<ImpulseBitSet> from(ImpulseBitSet bitset) {
		return new DefaultTraversal<>(snapshot, domainName, bitset);
	}

	@Override
	public MultiLaneBuilder multiLane() {
		return new DefaultMultiLaneBuilder(snapshot, this);
	}

	@Override
	public Traversal<ImpulseBitSet> vectorSearch(String indexOrAttr, float[] queryVector, int topK) {
		if (queryVector == null || queryVector.length == 0 || topK <= 0) {
			return from(new long[0]);
		}
		List<Float> qList = new ArrayList<>(queryVector.length);
		for (float f : queryVector) {
			qList.add(f);
		}
		ScmProgram prog = new ScmProgram(
				List.of(ScmVectorAnnSearch.fromInlineVector(qList, topK, 0, false), ScmCollect.vector()));
		try (Arena arena = Arena.ofConfined()) {
			CompiledQuery cq = ImpOpsBytecodeEmitter.compileToExecutable(prog, snapshot, arena);
			Object res = cq.execute(snapshot, 0L, arena);
			if (res instanceof int[] arr) {
				long[] seeds = new long[arr.length];
				for (int i = 0; i < arr.length; i++) {
					seeds[i] = arr[i];
				}
				return from(seeds);
			}
		}
		return from(new long[0]);
	}
}
