package org.impulsegraph.api.schema;

import org.impulsegraph.api.ArgType;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Code generator for producing strongly-typed QueryBuilder Java classes from a {@link GraphSchema}.
 * Generates compile-time type-checked Java classes with IDE auto-complete.
 */
public class SchemaCodeGenerator {

    private final String packageName;

    public SchemaCodeGenerator(String packageName) {
        this.packageName = Objects.requireNonNull(packageName, "packageName must not be null");
    }

    /**
     * Generates a Map of class names -> Java source code contents for all entities in the schema.
     */
    public Map<String, String> generateClasses(GraphSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        Map<String, String> classes = new HashMap<>();

        for (GraphSchema.EntityDef entity : schema.entities()) {
            String className = capitalize(entity.name()) + "QueryBuilder";
            String source = generateEntityClass(schema, entity, className);
            classes.put(className, source);
        }

        return classes;
    }

    private String generateEntityClass(GraphSchema schema, GraphSchema.EntityDef entity, String className) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import org.impulsegraph.api.ArgType;\n");
        sb.append("import org.impulsegraph.api.ImpulseQueryBuilder;\n");
        sb.append("import org.impulsegraph.api.schema.TypedQueryBuilder;\n\n");
        sb.append("/**\n");
        sb.append(" * Strongly-typed auto-generated Query Builder for entity: ").append(entity.name()).append("\n");
        sb.append(" * Provides compile-time type checking and IDE auto-complete.\n");
        sb.append(" */\n");
        sb.append("public class ").append(className).append("<R> extends TypedQueryBuilder<Object, R> {\n\n");

        // Constructor
        sb.append("    public ").append(className).append("(ImpulseQueryBuilder<R> builder) {\n");
        sb.append("        super(builder);\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(className).append("(String entityType, ArgType argType) {\n");
        sb.append("        super(entityType, argType);\n");
        sb.append("    }\n\n");

        // Factory Method
        sb.append("    public static ").append(className).append("<Object> from(Object input) {\n");
        sb.append("        ArgType argType = (input instanceof Number) ? ArgType.SINGLE_NODE : ArgType.ROARING_BITSET;\n");
        sb.append("        return new ").append(className).append("<Object>(\"").append(entity.name()).append("\", argType);\n");
        sb.append("    }\n\n");

        // Generate relation traversal methods
        for (GraphSchema.RelationDef rel : schema.getOutgoingRelations(entity.name())) {
            String targetClassName = capitalize(rel.targetEntity()) + "QueryBuilder";
            String methodName = "walk" + capitalize(rel.name());

            sb.append("    public ").append(targetClassName).append("<R> ").append(methodName).append("() {\n");
            sb.append("        this.builder.walkEdge(\"").append(rel.name()).append("\");\n");
            sb.append("        return new ").append(targetClassName).append("<R>(this.builder);\n");
            sb.append("    }\n\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}
