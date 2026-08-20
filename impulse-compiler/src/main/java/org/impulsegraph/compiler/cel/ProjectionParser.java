package org.impulsegraph.compiler.cel;

import org.impulsegraph.api.traversal.Reducer;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for Impulse Graph state projection expressions.
 * E.g. "state.lowest_cost:MIN = src.cost + edge.fee, state.best_supplier:ARGMIN(state.lowest_cost) = src.id"
 */
public class ProjectionParser {
    
    public static List<ProjectionAstNode> parse(String projectionString) {
        List<ProjectionAstNode> nodes = new ArrayList<>();
        if (projectionString == null || projectionString.trim().isEmpty()) {
            return nodes;
        }

        // Safe split by comma (ignoring commas inside parenthesis in CEL RHS)
        List<String> chunks = safeSplitByComma(projectionString);
        for (String chunk : chunks) {
            String trimmed = chunk.trim();
            if (!trimmed.isEmpty()) {
                ProjectionAstNode n = parseSingle(trimmed);
                ProjectionValidator.validate(n);
                nodes.add(n);
            }
        }
        return nodes;
    }

    private static List<String> safeSplitByComma(String input) {
        List<String> result = new ArrayList<>();
        int parenDepth = 0;
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '(') parenDepth++;
            else if (c == ')') parenDepth--;
            else if (c == ',' && parenDepth == 0) {
                result.add(input.substring(start, i));
                start = i + 1;
            }
        }
        if (start < input.length()) {
            result.add(input.substring(start));
        }
        return result;
    }

    private static ProjectionAstNode parseSingle(String chunk) {
        int eqIdx = chunk.indexOf('=');
        if (eqIdx == -1) {
            throw new IllegalArgumentException("Invalid projection clause, missing '=': " + chunk);
        }
        String lhs = chunk.substring(0, eqIdx).trim();
        String rhs = chunk.substring(eqIdx + 1).trim();

        if (!lhs.startsWith("state.")) {
            throw new IllegalArgumentException("Projection LHS must start with 'state.': " + lhs);
        }
        String targetPart = lhs.substring(6); // remove "state."
        
        int colonIdx = targetPart.indexOf(':');
        if (colonIdx == -1) {
            throw new IllegalArgumentException("Projection LHS must declare a reducer (e.g. :MIN): " + lhs);
        }
        
        String targetField = targetPart.substring(0, colonIdx).trim();
        String reducerPart = targetPart.substring(colonIdx + 1).trim();
        
        String reducerName = reducerPart;
        String param = null;
        
        int parenStart = reducerPart.indexOf('(');
        if (parenStart != -1) {
            int parenEnd = reducerPart.indexOf(')', parenStart);
            if (parenEnd == -1) {
                throw new IllegalArgumentException("Malformed reducer parameter: " + reducerPart);
            }
            reducerName = reducerPart.substring(0, parenStart).trim();
            param = reducerPart.substring(parenStart + 1, parenEnd).trim();
            
            if (param.startsWith("state.")) {
                param = param.substring(6);
            }
        }

        Reducer reducer;
        try {
            reducer = Reducer.valueOf(reducerName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown Reducer: " + reducerName, e);
        }
        
        CelAstNode rhsAst = CelParser.parse(rhs);
        return new ProjectionAstNode(targetField, reducer, param, rhsAst);
    }
}
