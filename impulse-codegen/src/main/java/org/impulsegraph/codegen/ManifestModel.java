package org.impulsegraph.codegen;

import java.util.Map;

public class ManifestModel {
    public String graphName;
    public String version;
    public Map<String, TablespaceDef> tablespaces;
    public Map<String, DomainDef> domains;
    public Map<String, RelationDef> relations;

    public static class TablespaceDef {
        public String file;
        public String description;
        public String mode;
    }

    public static class DomainDef {
        public String tablespace;
        public Map<String, String> attributes;
    }

    public static class RelationDef {
        public String source;
        public String target;
        public String tablespace;
        public Map<String, String> attributes;
    }
}
