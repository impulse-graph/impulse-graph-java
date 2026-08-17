package org.impulsegraph.storage.csr;

import org.impulsegraph.api.schema.GraphManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Zero-dependency YAML parser specifically for the Impulse Graph manifest.yaml schema.
 * Operates purely on indentation state and regex to avoid Jackson in the core kernel.
 */
public class ManifestYamlParser {

    public static GraphManifest parse(Path yamlPath) throws IOException {
        List<String> lines = Files.readAllLines(yamlPath);
        
        String graphName = "ImpulseGraph";
        String version = "1.0";
        Map<String, GraphManifest.TablespaceDef> tablespaces = new LinkedHashMap<>();
        Map<String, GraphManifest.DomainDef> domains = new LinkedHashMap<>();
        Map<String, GraphManifest.RelationDef> relations = new LinkedHashMap<>();
        Map<String, GraphManifest.VirtualRelationDef> virtualRelations = new LinkedHashMap<>();
        
        String currentSection = null;
        String currentItem = null;
        String currentSubSection = null;
        
        Map<String, String> currentItemProps = new HashMap<>();
        Map<String, String> currentAttributes = new HashMap<>();
        List<String> currentComponents = new ArrayList<>();

        for (String line : lines) {
            if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;

            int indent = getIndent(line);
            String trimmed = line.trim();

            if (indent == 0) {
                if (trimmed.startsWith("graphName:")) {
                    graphName = extractValue(trimmed);
                } else if (trimmed.startsWith("version:")) {
                    version = extractValue(trimmed);
                } else if (trimmed.equals("tablespaces:")) {
                    saveCurrentItem(currentSection, currentItem, currentItemProps, currentAttributes, currentComponents, tablespaces, domains, relations, virtualRelations);
                    currentSection = "tablespaces";
                    currentItem = null;
                } else if (trimmed.equals("domains:")) {
                    saveCurrentItem(currentSection, currentItem, currentItemProps, currentAttributes, currentComponents, tablespaces, domains, relations, virtualRelations);
                    currentSection = "domains";
                    currentItem = null;
                } else if (trimmed.equals("relations:")) {
                    saveCurrentItem(currentSection, currentItem, currentItemProps, currentAttributes, currentComponents, tablespaces, domains, relations, virtualRelations);
                    currentSection = "relations";
                    currentItem = null;
                } else if (trimmed.equals("virtual_relations:")) {
                    saveCurrentItem(currentSection, currentItem, currentItemProps, currentAttributes, currentComponents, tablespaces, domains, relations, virtualRelations);
                    currentSection = "virtual_relations";
                    currentItem = null;
                }
            } else if (indent == 2) {
                if (trimmed.endsWith(":")) {
                    saveCurrentItem(currentSection, currentItem, currentItemProps, currentAttributes, currentComponents, tablespaces, domains, relations, virtualRelations);
                    currentItem = trimmed.substring(0, trimmed.length() - 1);
                    currentItemProps.clear();
                    currentAttributes.clear();
                    currentComponents.clear();
                    currentSubSection = null;
                }
            } else if (indent == 4) {
                if (trimmed.equals("attributes:")) {
                    currentSubSection = "attributes";
                } else if (trimmed.equals("components:")) {
                    currentSubSection = "components";
                } else if (trimmed.contains(":")) {
                    String[] parts = splitKv(trimmed);
                    currentItemProps.put(parts[0], parts[1]);
                }
            } else if (indent >= 6) {
                if ("attributes".equals(currentSubSection) && trimmed.contains(":")) {
                    String[] parts = splitKv(trimmed);
                    currentAttributes.put(parts[0], parts[1]);
                } else if ("components".equals(currentSubSection) && trimmed.startsWith("-")) {
                    currentComponents.add(trimmed.substring(1).trim().replace("\"", ""));
                }
            }
        }
        saveCurrentItem(currentSection, currentItem, currentItemProps, currentAttributes, currentComponents, tablespaces, domains, relations, virtualRelations);

        return new GraphManifest(graphName, version, tablespaces, domains, relations, virtualRelations);
    }

    private static void saveCurrentItem(String section, String item, Map<String, String> props, Map<String, String> attrs, List<String> comps,
                                        Map<String, GraphManifest.TablespaceDef> t,
                                        Map<String, GraphManifest.DomainDef> d,
                                        Map<String, GraphManifest.RelationDef> r,
                                        Map<String, GraphManifest.VirtualRelationDef> vr) {
        if (item == null || section == null) return;
        
        if ("tablespaces".equals(section)) {
            t.put(item, new GraphManifest.TablespaceDef(
                props.getOrDefault("file", item + ".imps"),
                props.get("description"),
                props.getOrDefault("mode", "read-write")
            ));
        } else if ("domains".equals(section)) {
            d.put(item, new GraphManifest.DomainDef(
                props.get("tablespace"),
                new HashMap<>(attrs)
            ));
        } else if ("relations".equals(section)) {
            r.put(item, new GraphManifest.RelationDef(
                props.get("source"),
                props.get("target"),
                props.get("tablespace"),
                new HashMap<>(attrs)
            ));
        } else if ("virtual_relations".equals(section)) {
            vr.put(item, new GraphManifest.VirtualRelationDef(
                new ArrayList<>(comps)
            ));
        }
    }

    private static int getIndent(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == ' ') count++;
            else break;
        }
        return count;
    }

    private static String extractValue(String line) {
        return line.substring(line.indexOf(':') + 1).replace("\"", "").trim();
    }

    private static String[] splitKv(String line) {
        int idx = line.indexOf(':');
        return new String[]{
            line.substring(0, idx).trim(),
            line.substring(idx + 1).replace("\"", "").trim()
        };
    }
}
