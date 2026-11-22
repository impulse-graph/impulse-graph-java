package org.impulsegraph.compiler.passes.stage1;

import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.ScmCelExpr;
import org.impulsegraph.compiler.ast.ScmFixpoint;
import org.impulsegraph.compiler.ast.ScmLaneReduceAttr;
import org.impulsegraph.compiler.ast.ScmLaneWalk;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmSetOp;
import org.impulsegraph.compiler.ast.ScmStreamFilter;
import org.impulsegraph.compiler.ast.ScmStreamProject;
import org.impulsegraph.compiler.ast.ScmVectorAnnSearch;
import org.impulsegraph.compiler.ast.ScmVectorFilter;
import org.impulsegraph.compiler.ast.ScmVectorRerankDot;
import org.impulsegraph.compiler.ast.ScmWalk;
import org.impulsegraph.compiler.ast.ScmWalk2Hop;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelParser;
import org.impulsegraph.compiler.cel.ProjectionAstNode;
import org.impulsegraph.compiler.cel.ProjectionParser;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;
import org.impulsegraph.compiler.passes.stage1.SchemaView.AttributeInfo;
import org.impulsegraph.compiler.passes.stage1.SchemaView.DomainInfo;
import org.impulsegraph.compiler.passes.stage1.SchemaView.RelationInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Stage 1 Pass: Snapshot-agnostic build-time schema validation.
 *
 * <p>
 * Validates an {@link ImpScmNode} AST against a {@link SchemaView} (derived
 * from a {@code GraphManifest} or {@code ImpulseGraphSnapshot}) without
 * requiring a physical {@code .imps} snapshot file. Collects all violations
 * into a {@link SchemaValidationException} rather than stopping at the first
 * error.
 */
public final class SchemaBindValidatorPass implements CompilerPass {

	public static final SchemaBindValidatorPass INSTANCE = new SchemaBindValidatorPass(null, null);

	private static final Set<String> RESERVED_IDENTIFIERS = Set.of("node", "edge", "src", "tgt", "state", "n", "e",
			"rel", "true", "false", "null");

	private final SchemaView schema;
	private final String anchorDomain;

	public SchemaBindValidatorPass(SchemaView schema) {
		this(schema, null);
	}

	public SchemaBindValidatorPass(SchemaView schema, String anchorDomain) {
		this.schema = schema;
		this.anchorDomain = anchorDomain;
	}

	@Override
	public String name() {
		return "SchemaBindValidatorPass";
	}

	@Override
	public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
		Objects.requireNonNull(ast, "ast must not be null");
		SchemaView effectiveSchema = this.schema;
		if (effectiveSchema == null && context != null && context.snapshot() != null) {
			effectiveSchema = SchemaView.fromSnapshot(context.snapshot());
		}
		if (effectiveSchema == null) {
			return ast;
		}
		validate(ast, effectiveSchema, this.anchorDomain);
		return ast;
	}

	/**
	 * Validates {@code ast} against {@code schema} without an explicit anchor
	 * domain (infers active domain from the first walk step).
	 */
	public static void validate(ImpScmNode ast, SchemaView schema) {
		validate(ast, schema, null);
	}

	/**
	 * Validates {@code ast} against {@code schema} starting from
	 * {@code anchorDomain} (optional).
	 *
	 * @throws SchemaValidationException
	 *             if one or more schema violations are detected
	 */
	public static void validate(ImpScmNode ast, SchemaView schema, String anchorDomain) {
		Objects.requireNonNull(ast, "ast must not be null");
		Objects.requireNonNull(schema, "schema must not be null");

		List<String> violations = new ArrayList<>();
		String activeDomain = null;

		if (anchorDomain != null && !anchorDomain.isEmpty()) {
			DomainInfo info = schema.domain(anchorDomain);
			if (info == null) {
				violations.add("Unknown anchor domain '" + anchorDomain + "' (available domains: "
						+ schema.domains().keySet() + ")");
			} else {
				activeDomain = info.name();
			}
		}

		validateNode(ast, schema, activeDomain, null, null, violations);

		if (!violations.isEmpty()) {
			throw new SchemaValidationException(violations);
		}
	}

	private static String validateNode(ImpScmNode node, SchemaView schema, String activeDomain, String targetDomain,
			RelationInfo activeRelation, List<String> violations) {
		if (node == null) {
			return activeDomain;
		}

		if (node instanceof ScmProgram prog) {
			String cur = activeDomain;
			for (ImpScmNode step : prog.steps()) {
				cur = validateNode(step, schema, cur, null, null, violations);
			}
			return cur;
		}

		if (node instanceof ScmWalk walk) {
			return validateWalk(walk.relationName(), walk.direction(), walk.shaderSteps(), walk.subSteps(), schema,
					activeDomain, violations);
		}

		if (node instanceof ScmWalk2Hop hop2) {
			String midDomain = validateWalk(hop2.relation1Name(), ScmWalk.Direction.FORWARD_CSR, List.of(), List.of(),
					schema, activeDomain, violations);
			return validateWalk(hop2.relation2Name(), ScmWalk.Direction.FORWARD_CSR, List.of(), List.of(), schema,
					midDomain, violations);
		}

		if (node instanceof ScmLaneWalk laneWalk) {
			return validateWalk(laneWalk.relationName(), laneWalk.direction(), List.of(), List.of(), schema,
					activeDomain, violations);
		}

		if (node instanceof ScmFixpoint fp) {
			String cur = activeDomain;
			if (!fp.relationName().isEmpty()) {
				cur = validateWalk(fp.relationName(), fp.direction(), List.of(), List.of(), schema, cur, violations);
			}
			for (ImpScmNode bodyStep : fp.bodySteps()) {
				cur = validateNode(bodyStep, schema, cur, null, null, violations);
			}
			return cur;
		}

		if (node instanceof ScmSetOp setOp) {
			if (setOp.lhs() != null) {
				validateNode(setOp.lhs(), schema, activeDomain, null, null, violations);
			}
			return validateNode(setOp.rhs(), schema, activeDomain, null, null, violations);
		}

		if (node instanceof ScmVectorFilter vf) {
			validateNode(vf.predicate(), schema, activeDomain, targetDomain, activeRelation, violations);
			return activeDomain;
		}

		if (node instanceof ScmStreamFilter sf) {
			validateNode(sf.predicate(), schema, activeDomain, targetDomain, activeRelation, violations);
			return activeDomain;
		}

		if (node instanceof ScmStreamProject sp) {
			validateProjection(sp.expr(), schema, activeDomain, targetDomain, activeRelation, violations);
			return activeDomain;
		}

		if (node instanceof ScmCelExpr cel) {
			validateCelExpr(cel, schema, activeDomain, targetDomain, activeRelation, violations);
			return activeDomain;
		}

		if (node instanceof ScmVectorAnnSearch ann) {
			validateVectorAttribute(ann.indexOrAttrName(), "ScmVectorAnnSearch", schema, activeDomain, violations);
			return activeDomain;
		}

		if (node instanceof ScmVectorRerankDot rerank) {
			validateVectorAttribute(rerank.attrName(), "ScmVectorRerankDot", schema, activeDomain, violations);
			return activeDomain;
		}

		if (node instanceof ScmLaneReduceAttr reduceAttr) {
			if (!reduceAttr.attrName().isEmpty() && activeDomain != null) {
				DomainInfo dom = schema.domain(activeDomain);
				if (dom != null && dom.attribute(reduceAttr.attrName()) == null) {
					violations.add("Unknown attribute '" + reduceAttr.attrName() + "' on domain '" + dom.name()
							+ "' in ScmLaneReduceAttr");
				}
			}
			return activeDomain;
		}

		return activeDomain;
	}

	private static String validateWalk(String relationName, ScmWalk.Direction direction, List<ImpScmNode> shaderSteps,
			List<ImpScmNode> subSteps, SchemaView schema, String activeDomain, List<String> violations) {
		if (relationName == null || relationName.isEmpty()) {
			return activeDomain;
		}

		boolean isReverse = direction == ScmWalk.Direction.REVERSE_CSC;
		RelationInfo rel = schema.relation(relationName);
		if (rel != null) {
			if (isReverse && !rel.hasInDirection()) {
				violations.add("Reverse (CSC / in) walk on relation '" + rel.name()
						+ "' is not permitted because the relation does not declare reverse/CSC support");
			} else if (!isReverse && !rel.hasOutDirection()) {
				violations.add("Forward (CSR / out) walk on relation '" + rel.name()
						+ "' is not permitted because the relation does not declare forward/CSR support");
			}

			String expectedSrc = isReverse ? rel.targetDomain() : rel.sourceDomain();
			String nextDomain = isReverse ? rel.sourceDomain() : rel.targetDomain();

			if (activeDomain != null && !expectedSrc.isEmpty() && !activeDomain.equalsIgnoreCase(expectedSrc)) {
				violations.add("Relation '" + rel.name() + "' (" + (isReverse ? "reverse" : "forward")
						+ ") expects source domain '" + expectedSrc + "', but active traversal domain is '"
						+ activeDomain + "'");
			}

			String shaderSrcDomain = !expectedSrc.isEmpty() ? expectedSrc : activeDomain;
			String shaderTgtDomain = !nextDomain.isEmpty() ? nextDomain : activeDomain;
			for (ImpScmNode shader : shaderSteps) {
				validateNode(shader, schema, shaderSrcDomain, shaderTgtDomain, rel, violations);
			}

			String outDomain = !nextDomain.isEmpty() ? nextDomain : activeDomain;
			for (ImpScmNode sub : subSteps) {
				outDomain = validateNode(sub, schema, outDomain, null, null, violations);
			}
			return outDomain;
		}

		List<String> virtualComponents = schema.virtualRelationComponents(relationName);
		if (virtualComponents != null) {
			if (virtualComponents.isEmpty()) {
				violations.add("Virtual relation '" + relationName + "' has no component relations");
				return activeDomain;
			}
			String inferredNextDomain = null;
			for (String compName : virtualComponents) {
				RelationInfo comp = schema.relation(compName);
				if (comp == null) {
					violations.add("Virtual relation '" + relationName + "' references unknown component relation '"
							+ compName + "'");
					continue;
				}
				if (isReverse && !comp.hasInDirection()) {
					violations.add("Reverse (CSC / in) walk on virtual relation '" + relationName
							+ "' is not permitted because component relation '" + comp.name()
							+ "' does not declare reverse/CSC support");
				} else if (!isReverse && !comp.hasOutDirection()) {
					violations.add("Forward (CSR / out) walk on virtual relation '" + relationName
							+ "' is not permitted because component relation '" + comp.name()
							+ "' does not declare forward/CSR support");
				}
				String expectedSrc = isReverse ? comp.targetDomain() : comp.sourceDomain();
				String compNext = isReverse ? comp.sourceDomain() : comp.targetDomain();
				if (activeDomain != null && !expectedSrc.isEmpty() && !activeDomain.equalsIgnoreCase(expectedSrc)) {
					violations.add("Virtual relation '" + relationName + "' component '" + comp.name()
							+ "' expects source domain '" + expectedSrc + "', but active traversal domain is '"
							+ activeDomain + "'");
				}
				if (inferredNextDomain == null && !compNext.isEmpty()) {
					inferredNextDomain = compNext;
				}
			}
			String outDomain = inferredNextDomain != null ? inferredNextDomain : activeDomain;
			for (ImpScmNode shader : shaderSteps) {
				validateNode(shader, schema, activeDomain, outDomain, null, violations);
			}
			for (ImpScmNode sub : subSteps) {
				outDomain = validateNode(sub, schema, outDomain, null, null, violations);
			}
			return outDomain;
		}

		violations.add("Unknown relation '" + relationName + "' (not found in schema relations or virtual_relations)");
		for (ImpScmNode shader : shaderSteps) {
			validateNode(shader, schema, activeDomain, null, null, violations);
		}
		for (ImpScmNode sub : subSteps) {
			validateNode(sub, schema, activeDomain, null, null, violations);
		}
		return activeDomain;
	}

	private static void validateProjection(ImpScmNode exprNode, SchemaView schema, String activeDomain,
			String targetDomain, RelationInfo activeRelation, List<String> violations) {
		if (exprNode instanceof ScmCelExpr cel) {
			String raw = cel.rawText() != null ? cel.rawText().trim() : "";
			if (cel.celAst() == null && raw.startsWith("state.") && raw.contains("=")) {
				try {
					List<ProjectionAstNode> projections = ProjectionParser.parse(raw);
					for (ProjectionAstNode proj : projections) {
						validateCelAst(proj.rhsExpression(), schema, activeDomain, targetDomain, activeRelation,
								violations);
					}
					return;
				} catch (Exception e) {
					violations.add("Invalid projection expression '" + raw + "': " + e.getMessage());
					return;
				}
			}
			validateCelExpr(cel, schema, activeDomain, targetDomain, activeRelation, violations);
		} else {
			validateNode(exprNode, schema, activeDomain, targetDomain, activeRelation, violations);
		}
	}

	private static void validateCelExpr(ScmCelExpr cel, SchemaView schema, String activeDomain, String targetDomain,
			RelationInfo activeRelation, List<String> violations) {
		CelAstNode celAst = null;
		if (cel.celAst() instanceof CelAstNode existing) {
			celAst = existing;
		} else if (!cel.rawText().isEmpty()) {
			try {
				celAst = CelParser.parse(cel.rawText());
			} catch (Exception e) {
				violations.add("Invalid CEL expression '" + cel.rawText() + "': " + e.getMessage());
				return;
			}
		}
		if (celAst != null) {
			validateCelAst(celAst, schema, activeDomain, targetDomain, activeRelation, violations);
		}
	}

	private static void validateVectorAttribute(String attrName, String opName, SchemaView schema, String activeDomain,
			List<String> violations) {
		if (attrName == null || attrName.isEmpty() || attrName.startsWith("reg:")) {
			return;
		}
		if (activeDomain == null) {
			return;
		}
		DomainInfo dom = schema.domain(activeDomain);
		if (dom == null) {
			return;
		}
		AttributeInfo attr = dom.attribute(attrName);
		if (attr == null) {
			violations.add("Unknown vector attribute '" + attrName + "' on domain '" + dom.name() + "' in " + opName);
		} else if (!attr.isFloatVector()) {
			violations.add("Type mismatch in " + opName + ": attribute '" + dom.name() + "." + attr.name()
					+ "' has type '" + attr.typeName() + "', expected a float vector column");
		}
	}

	private enum ExprType {
		NUMERIC, STRING, BOOLEAN, VECTOR, UNKNOWN
	}

	private static ExprType validateCelAst(CelAstNode node, SchemaView schema, String activeDomain, String targetDomain,
			RelationInfo activeRelation, List<String> violations) {
		if (node == null) {
			return ExprType.UNKNOWN;
		}

		return switch (node.kind()) {
			case LITERAL_INT, LITERAL_FLOAT -> ExprType.NUMERIC;
			case LITERAL_STRING -> ExprType.STRING;
			case LITERAL_BOOL -> ExprType.BOOLEAN;
			case PARAMETER_REF, LIST_LITERAL -> ExprType.UNKNOWN;
			case IDENTIFIER -> resolveBareIdentifier(node.text(), schema, activeDomain, violations);
			case MEMBER_ACCESS ->
				resolveMemberAccess(node, schema, activeDomain, targetDomain, activeRelation, violations);
			case UNARY_OP -> {
				ExprType operandType = node.children().isEmpty()
						? ExprType.UNKNOWN
						: validateCelAst(node.children().get(0), schema, activeDomain, targetDomain, activeRelation,
								violations);
				String op = node.text();
				if ("-".equals(op) && (operandType == ExprType.STRING || operandType == ExprType.BOOLEAN
						|| operandType == ExprType.VECTOR)) {
					violations.add("Type mismatch: unary '-' cannot be applied to " + operandType + " expression");
					yield ExprType.UNKNOWN;
				}
				if ("!".equals(op) && (operandType == ExprType.NUMERIC || operandType == ExprType.STRING
						|| operandType == ExprType.VECTOR)) {
					violations.add("Type mismatch: logical '!' cannot be applied to " + operandType + " expression");
					yield ExprType.BOOLEAN;
				}
				yield "!".equals(op) ? ExprType.BOOLEAN : operandType;
			}
			case BINARY_OP -> {
				ExprType lhs = node.children().size() > 0
						? validateCelAst(node.children().get(0), schema, activeDomain, targetDomain, activeRelation,
								violations)
						: ExprType.UNKNOWN;
				ExprType rhs = node.children().size() > 1
						? validateCelAst(node.children().get(1), schema, activeDomain, targetDomain, activeRelation,
								violations)
						: ExprType.UNKNOWN;
				yield checkBinaryOp(node.text(), lhs, rhs, violations);
			}
			case TERNARY_OP -> {
				if (node.children().size() >= 3) {
					validateCelAst(node.children().get(0), schema, activeDomain, targetDomain, activeRelation,
							violations);
					ExprType thenType = validateCelAst(node.children().get(1), schema, activeDomain, targetDomain,
							activeRelation, violations);
					ExprType elseType = validateCelAst(node.children().get(2), schema, activeDomain, targetDomain,
							activeRelation, violations);
					yield thenType == elseType ? thenType : ExprType.UNKNOWN;
				}
				yield ExprType.UNKNOWN;
			}
			case FUNCTION_CALL -> {
				for (CelAstNode arg : node.children()) {
					validateCelAst(arg, schema, activeDomain, targetDomain, activeRelation, violations);
				}
				yield ExprType.UNKNOWN;
			}
		};
	}

	private static ExprType resolveBareIdentifier(String ident, SchemaView schema, String activeDomain,
			List<String> violations) {
		if (ident == null || ident.isEmpty() || RESERVED_IDENTIFIERS.contains(ident)) {
			return ExprType.UNKNOWN;
		}
		if (activeDomain == null) {
			return ExprType.UNKNOWN;
		}
		DomainInfo dom = schema.domain(activeDomain);
		if (dom == null || dom.attributes().isEmpty()) {
			return ExprType.UNKNOWN;
		}
		AttributeInfo attr = dom.attribute(ident);
		if (attr == null) {
			violations.add("Unknown attribute '" + ident + "' on active domain '" + dom.name() + "'");
			return ExprType.UNKNOWN;
		}
		return toExprType(attr);
	}

	private static ExprType resolveMemberAccess(CelAstNode node, SchemaView schema, String activeDomain,
			String targetDomain, RelationInfo activeRelation, List<String> violations) {
		if (node.children().isEmpty()) {
			return ExprType.UNKNOWN;
		}
		CelAstNode target = node.children().get(0);
		String field = node.text();
		if (target.kind() == CelAstNode.Kind.IDENTIFIER) {
			String qualifier = target.text();
			if ("node".equals(qualifier) || "src".equals(qualifier) || "n".equals(qualifier)) {
				if (activeDomain != null) {
					DomainInfo dom = schema.domain(activeDomain);
					if (dom != null) {
						AttributeInfo attr = dom.attribute(field);
						if (attr == null) {
							violations.add("Unknown node attribute '" + qualifier + "." + field + "' on active domain '"
									+ dom.name() + "'");
							return ExprType.UNKNOWN;
						}
						return toExprType(attr);
					}
				}
				return ExprType.UNKNOWN;
			}
			if ("tgt".equals(qualifier)) {
				String domName = targetDomain != null ? targetDomain : activeDomain;
				if (domName != null) {
					DomainInfo dom = schema.domain(domName);
					if (dom != null) {
						AttributeInfo attr = dom.attribute(field);
						if (attr == null) {
							violations.add("Unknown target node attribute 'tgt." + field + "' on target domain '"
									+ dom.name() + "'");
							return ExprType.UNKNOWN;
						}
						return toExprType(attr);
					}
				}
				return ExprType.UNKNOWN;
			}
			if ("edge".equals(qualifier) || "e".equals(qualifier) || "rel".equals(qualifier)) {
				if (activeRelation == null) {
					violations.add("Edge attribute '" + qualifier + "." + field
							+ "' referenced outside of a relation walk context");
					return ExprType.UNKNOWN;
				}
				AttributeInfo attr = activeRelation.attribute(field);
				if (attr == null) {
					violations.add("Unknown edge attribute '" + qualifier + "." + field + "' on relation '"
							+ activeRelation.name() + "'");
					return ExprType.UNKNOWN;
				}
				return toExprType(attr);
			}
			if ("state".equals(qualifier)) {
				return ExprType.UNKNOWN;
			}
		}
		validateCelAst(target, schema, activeDomain, targetDomain, activeRelation, violations);
		return ExprType.UNKNOWN;
	}

	private static ExprType checkBinaryOp(String op, ExprType lhs, ExprType rhs, List<String> violations) {
		return switch (op) {
			case "<", "<=", ">", ">=" -> {
				if (lhs == ExprType.STRING || rhs == ExprType.STRING || lhs == ExprType.BOOLEAN
						|| rhs == ExprType.BOOLEAN || lhs == ExprType.VECTOR || rhs == ExprType.VECTOR) {
					violations.add("Type mismatch: numeric comparison '" + op + "' cannot be applied to (" + lhs + ", "
							+ rhs + ")");
				}
				yield ExprType.BOOLEAN;
			}
			case "-", "*", "/", "%" -> {
				if (lhs == ExprType.STRING || rhs == ExprType.STRING || lhs == ExprType.BOOLEAN
						|| rhs == ExprType.BOOLEAN || lhs == ExprType.VECTOR || rhs == ExprType.VECTOR) {
					violations.add("Type mismatch: arithmetic operator '" + op + "' cannot be applied to (" + lhs + ", "
							+ rhs + ")");
				}
				yield ExprType.NUMERIC;
			}
			case "+" -> {
				if (lhs != ExprType.UNKNOWN && rhs != ExprType.UNKNOWN && lhs != rhs) {
					violations.add("Type mismatch: operator '+' cannot combine " + lhs + " and " + rhs);
					yield ExprType.UNKNOWN;
				}
				yield lhs != ExprType.UNKNOWN ? lhs : rhs;
			}
			case "==", "!=" -> {
				if (lhs != ExprType.UNKNOWN && rhs != ExprType.UNKNOWN && lhs != rhs) {
					violations.add(
							"Type mismatch: equality operator '" + op + "' cannot compare " + lhs + " with " + rhs);
				}
				yield ExprType.BOOLEAN;
			}
			case "&&", "||" -> {
				if (lhs == ExprType.NUMERIC || lhs == ExprType.STRING || lhs == ExprType.VECTOR
						|| rhs == ExprType.NUMERIC || rhs == ExprType.STRING || rhs == ExprType.VECTOR) {
					violations.add("Type mismatch: logical operator '" + op + "' requires boolean operands, got (" + lhs
							+ ", " + rhs + ")");
				}
				yield ExprType.BOOLEAN;
			}
			default -> ExprType.UNKNOWN;
		};
	}

	private static ExprType toExprType(AttributeInfo attr) {
		if (attr.isFloatVector()) {
			return ExprType.VECTOR;
		}
		if (attr.isNumeric()) {
			return ExprType.NUMERIC;
		}
		if (attr.isString()) {
			return ExprType.STRING;
		}
		if (attr.isBoolean()) {
			return ExprType.BOOLEAN;
		}
		return ExprType.UNKNOWN;
	}
}
