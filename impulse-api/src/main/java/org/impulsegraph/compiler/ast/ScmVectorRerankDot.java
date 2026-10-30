package org.impulsegraph.compiler.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Vector similarity reranking step over active frontier nodes (opcode
 * {@code 0xCB OP_VECTOR_RERANK_DOT}).
 */
public record ScmVectorRerankDot(float[] queryVector, int attrId, String attrName, int metric) implements ImpScmNode {

	public ScmVectorRerankDot(float[] queryVector, int attrId, String attrName, int metric) {
		this.queryVector = queryVector != null ? queryVector.clone() : new float[0];
		this.attrId = Math.max(0, attrId);
		this.attrName = attrName != null ? attrName : "";
		this.metric = metric & 0x03;
	}

	public static ScmVectorRerankDot fromInlineVector(List<Float> queryVec, int attrId, int metric) {
		float[] arr = new float[queryVec != null ? queryVec.size() : 0];
		if (queryVec != null) {
			for (int i = 0; i < queryVec.size(); i++) {
				Float v = queryVec.get(i);
				arr[i] = v != null ? v : 0.0f;
			}
		}
		return new ScmVectorRerankDot(arr, attrId, "", metric);
	}

	public static ScmVectorRerankDot fromRegister(int queryReg, int attrId, int metric) {
		return new ScmVectorRerankDot(new float[0], attrId, "reg:" + queryReg, metric);
	}

	public int queryReg() {
		if (attrName.startsWith("reg:")) {
			try {
				return Integer.parseInt(attrName.substring(4));
			} catch (NumberFormatException ignored) {
			}
		}
		return 10;
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
		if (!(o instanceof ScmVectorRerankDot that))
			return false;
		return attrId == that.attrId && metric == that.metric && Arrays.equals(queryVector, that.queryVector)
				&& attrName.equals(that.attrName);
	}

	@Override
	public int hashCode() {
		int result = Arrays.hashCode(queryVector);
		result = 31 * result + attrId;
		result = 31 * result + attrName.hashCode();
		result = 31 * result + metric;
		return result;
	}

	@Override
	public String toScmString() {
		StringBuilder sb = new StringBuilder("(vector-rerank-dot (");
		for (int i = 0; i < queryVector.length; i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(queryVector[i]);
		}
		sb.append(") ").append(attrId).append(' ').append(metric).append(')');
		return sb.toString();
	}

	@Override
	public <R> R accept(ImpScmVisitor<R> visitor) {
		return visitor.visitVectorRerankDot(this);
	}
}
