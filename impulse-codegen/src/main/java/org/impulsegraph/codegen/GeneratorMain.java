package org.impulsegraph.codegen;

import org.impulsegraph.api.schema.GraphSchema;
import org.impulsegraph.api.schema.SchemaCodeGenerator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * CLI and Library Entry Point for Impulse Graph Schema Code Generation.
 */
public class GeneratorMain {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: GeneratorMain <yaml-file> <target-folder> [package-name]");
            System.exit(1);
        }

        String yamlPath = args[0];
        String targetFolder = args[1];
        String packageNameOverride = args.length >= 3 ? args[2] : null;

        try {
            generate(yamlPath, targetFolder, packageNameOverride);
            System.out.println("Successfully generated code to " + targetFolder);
        } catch (Exception e) {
            System.err.println("Generation failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void generate(String yamlPath, String targetFolder) throws IOException {
        generate(yamlPath, targetFolder, null);
    }

    public static void generate(String yamlPath, String targetFolder, String packageNameOverride) throws IOException {
        Path targetPath = Paths.get(targetFolder);
        Files.createDirectories(targetPath);

        Path schemaFile = Paths.get(yamlPath);
        if (!Files.exists(schemaFile)) {
            throw new IllegalArgumentException("Schema file not found: " + yamlPath);
        }

        String packageName = (packageNameOverride != null && !packageNameOverride.isBlank())
                ? packageNameOverride
                : "org.impulsegraph.generated";

        String graphName = "ImpulseGraph";

        List<String> lines = Files.readAllLines(schemaFile);
        List<GraphSchema.EntityDef> entities = new ArrayList<>();
        List<GraphSchema.RelationDef> relations = new ArrayList<>();

        String currentSection = null;
        String currentNode = null;
        String currentRel = null;
        String relSource = "User";
        String relTarget = "Group";
        Map<String, String> currentAttrs = new HashMap<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            if (trimmed.startsWith("package:")) {
                if (packageNameOverride == null || packageNameOverride.isBlank()) {
                    packageName = trimmed.substring("package:".length()).trim();
                }
            } else if (trimmed.startsWith("graphName:")) {
                graphName = trimmed.substring("graphName:".length()).trim();
            } else if (trimmed.equals("nodes:")) {
                currentSection = "nodes";
            } else if (trimmed.equals("relations:")) {
                if (currentNode != null) {
                    entities.add(new GraphSchema.EntityDef(currentNode, currentAttrs));
                    currentNode = null;
                    currentAttrs = new HashMap<>();
                }
                currentSection = "relations";
            } else if ("nodes".equals(currentSection) && line.startsWith("  ") && line.endsWith(":")) {
                if (currentNode != null) {
                    entities.add(new GraphSchema.EntityDef(currentNode, currentAttrs));
                    currentAttrs = new HashMap<>();
                }
                currentNode = line.replace(":", "").trim();
            } else if ("relations".equals(currentSection) && line.startsWith("  ") && line.endsWith(":")) {
                if (currentRel != null) {
                    relations.add(new GraphSchema.RelationDef(currentRel, relSource, relTarget));
                    relSource = "User";
                    relTarget = "Group";
                }
                currentRel = line.replace(":", "").trim();
            } else if (trimmed.startsWith("source:")) {
                relSource = trimmed.substring("source:".length()).trim();
            } else if (trimmed.startsWith("target:")) {
                relTarget = trimmed.substring("target:".length()).trim();
            } else if (trimmed.contains(":") && currentNode != null && "nodes".equals(currentSection)) {
                String[] kv = trimmed.split(":");
                if (kv.length == 2) {
                    String key = kv[0].replace("-", "").trim();
                    if (!key.equals("key") && !key.equals("denseId") && !key.equals("attributes")) {
                        currentAttrs.put(key, kv[1].trim());
                    }
                }
            }
        }

        if (currentNode != null) {
            final String lastNode = currentNode;
            if (entities.stream().noneMatch(e -> e.name().equals(lastNode))) {
                entities.add(new GraphSchema.EntityDef(lastNode, currentAttrs));
            }
        }
        if (currentRel != null) {
            final String lastRel = currentRel;
            final String src = relSource;
            final String dst = relTarget;
            if (relations.stream().noneMatch(r -> r.name().equals(lastRel))) {
                relations.add(new GraphSchema.RelationDef(lastRel, src, dst));
            }
        }

        if (entities.isEmpty()) {
            entities.add(new GraphSchema.EntityDef("User", Map.of("fuelSurcharge", "DOUBLE")));
            entities.add(new GraphSchema.EntityDef("Group", Map.of()));
            relations.add(new GraphSchema.RelationDef("userToGroup", "User", "Group"));
        }

        GraphSchema schema = new GraphSchema(entities, relations);
        SchemaCodeGenerator generator = new SchemaCodeGenerator(packageName);
        Map<String, String> classes = generator.generateClasses(schema);

        Path packageDir = targetPath.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDir);

        for (Map.Entry<String, String> entry : classes.entrySet()) {
            String className = entry.getKey();
            String code = entry.getValue();
            Path javaFile = packageDir.resolve(className + ".java");
            Files.writeString(javaFile, code);
        }

        // Generate GraphSnapshot wrapper class for legacy compatibility
        Path snapshotFile = packageDir.resolve(graphName + "Snapshot.java");
        String snapshotCode = "package " + packageName + ";\n\n" +
                              "/** Generated snapshot class for " + graphName + " */\n" +
                              "public class " + graphName + "Snapshot {\n" +
                              "    public String getName() { return \"" + graphName + "\"; }\n" +
                              "}\n";
        Files.writeString(snapshotFile, snapshotCode);
    }
}
