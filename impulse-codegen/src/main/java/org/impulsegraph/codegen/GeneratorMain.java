package org.impulsegraph.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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

        File schemaFile = new File(yamlPath);
        if (!schemaFile.exists()) {
            throw new IllegalArgumentException("Schema file not found: " + yamlPath);
        }

        String packageName = (packageNameOverride != null && !packageNameOverride.isBlank())
                ? packageNameOverride
                : "org.impulsegraph.generated";

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        ManifestModel model = mapper.readValue(schemaFile, ManifestModel.class);

        String graphName = (model.graphName != null) ? model.graphName : "ImpulseGraph";

        List<GraphSchema.EntityDef> entities = new ArrayList<>();
        if (model.domains != null) {
            for (Map.Entry<String, ManifestModel.DomainDef> entry : model.domains.entrySet()) {
                entities.add(new GraphSchema.EntityDef(entry.getKey(), entry.getValue().attributes));
            }
        }

        List<GraphSchema.RelationDef> relations = new ArrayList<>();
        if (model.relations != null) {
            for (Map.Entry<String, ManifestModel.RelationDef> entry : model.relations.entrySet()) {
                ManifestModel.RelationDef rel = entry.getValue();
                relations.add(new GraphSchema.RelationDef(entry.getKey(), rel.source, rel.target));
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
