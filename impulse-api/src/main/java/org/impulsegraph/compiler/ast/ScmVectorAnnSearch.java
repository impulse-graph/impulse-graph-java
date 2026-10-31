package org.impulsegraph.compiler.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Hybrid vector ANN search step (opcode {@code 0xCA OP_VECTOR_ANN_SEARCH}).
 */
public record ScmVectorAnnSearch(float[] queryVector, int topK, int indexOrAttrId, String indexOrAttrName,
		boolean multiLaneHandshake) implements ImpScmNode {

	public ScmVectorAnnSearch(float[] queryVector, int topK, int indexOrAttrId, String indexOrAttrName,
			boolean multiLaneHandshake) {
		this.queryVector = queryVector != null ? queryVector.clone() : new float[0];
		this.topK = Math.max(1, topK);
		this.indexOrAttrId = Math.max(0, indexOrAttrId);
		this.indexOrAttrName = indexOrAttrName != null ? indexOrAttrName : "";
		this.multiLaneHandshake = multiLaneHandshake;
	}

	public static ScmVectorAnnSearch fromInlineVector(List<Float> queryVec, int topK, int indexOrAttrId,
			boolean fusedLaneFrontier) {
		float[] arr = new float[queryVec != null ? queryVec.size() : 0];
		if (queryVec != null) {
			for (int i = 0; i < queryVec.size(); i++) {
				Float v = queryVec.get(i);
				arr[i] = v != null ? v : 0.0f;
			}
		}
		return new ScmVectorAnnSearch(arr, topK, indexOrAttrId, "", fusedLaneFrontier);
	}

	public static ScmVectorAnnSearch fromRegister(int queryReg, int topK, int indexOrAttrId,
			boolean fusedLaneFrontier) {
		return new ScmVectorAnnSearch(new float[0], topK, indexOrAttrId, "reg:" + queryReg, fusedLaneFrontier);
	}

	public int queryReg() {
		if (indexOrAttrName.startsWith("reg:")) {
			try {
				return Integer.parseInt(indexOrAttrName.substring(4));
			} catch (NumberFormatException ignored) {
			}
		}
		return 10;
	}

	public boolean fusedLaneFrontier() {
		return multiLaneHandshake;
	}

	public List<Float> queryVectorList() {
		List<Float> list = new ArrayList<>(queryVector.length);
		for (float v : queryVector) {
			list.add(v);
		}
		return list;
	}

	@Override
	public float[] queryVector() {
		return queryVector.clone();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof ScmVectorAnnSearch that))
			return false;
		return topK == that.topK && indexOrAttrId == that.indexOrAttrId && multiLaneHandshake == that.multiLaneHandshake
				&& Arrays.equals(queryVector, that.queryVector) && indexOrAttrName.equals(that.indexOrAttrName);
	}

	@Override
	public int hashCode() {
		int result = Arrays.hashCode(queryVector);
		result = 31 * result + topK;
		result = 31 * result + indexOrAttrId;
		result = 31 * result + indexOrAttrName.hashCode();
		result = 31 * result + Boolean.hashCode(multiLaneHandshake);
		return result;
	}

	@Override
	public String toScmString() {
		StringBuilder sb = new StringBuilder("(vector-ann-search (");
		for (int i = 0; i < queryVector.length; i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(queryVector[i]);
		}
		sb.append(") ").append(topK).append(' ').append(indexOrAttrId);
		if (multiLaneHandshake) {
			sb.append(" #t");
		}
		sb.append(')');
		return sb.toString();
	}

	@Override
	public <R> R accept(ImpScmVisitor<R> visitor) {
		return visitor.visitVectorAnnSearch(this);
	}
}
