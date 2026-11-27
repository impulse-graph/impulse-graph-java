package org.impulsegraph.compiler.impk;

import java.util.List;
import java.util.Objects;

/**
 * Parsed statement in an ImpK (.impk) script.
 */
public record ImpKStatement(String targetVar, ImpKOpType opType, String matrixVar, String vectorVar1, String vectorVar2,
		double scalarParam, int topK, int intParam1, int intParam2, boolean boolParam, List<Long> intList,
		List<Float> floatList, String exprText, String typeAnnotation) {

	public ImpKStatement {
		Objects.requireNonNull(targetVar, "targetVar must not be null");
		Objects.requireNonNull(opType, "opType must not be null");
		matrixVar = matrixVar != null ? matrixVar : "";
		vectorVar1 = vectorVar1 != null ? vectorVar1 : "";
		vectorVar2 = vectorVar2 != null ? vectorVar2 : "";
		intList = intList != null ? List.copyOf(intList) : List.of();
		floatList = floatList != null ? List.copyOf(floatList) : List.of();
		exprText = exprText != null ? exprText : "";
		typeAnnotation = typeAnnotation != null ? typeAnnotation : "";
	}

	public ImpKStatement(String targetVar, ImpKOpType opType, String matrixVar, String vectorVar1, String vectorVar2,
			double scalarParam, int topK, int intParam1, int intParam2, boolean boolParam, List<Long> intList,
			List<Float> floatList) {
		this(targetVar, opType, matrixVar, vectorVar1, vectorVar2, scalarParam, topK, intParam1, intParam2, boolParam,
				intList, floatList, "", "");
	}

	public static ImpKStatement basic(String targetVar, ImpKOpType opType, String matrixVar, String vectorVar1,
			String vectorVar2, double scalarParam, int topK) {
		return new ImpKStatement(targetVar, opType, matrixVar, vectorVar1, vectorVar2, scalarParam, topK, 0, 0, false,
				List.of(), List.of(), "", "");
	}

	public static ImpKStatement expr(String targetVar, ImpKOpType opType, String exprText, String typeAnnotation) {
		return new ImpKStatement(targetVar, opType, "", "", "", 0.0, 0, 0, 0, false, List.of(), List.of(), exprText,
				typeAnnotation);
	}
}
