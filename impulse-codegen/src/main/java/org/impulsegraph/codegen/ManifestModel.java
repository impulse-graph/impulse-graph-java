package org.impulsegraph.codegen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ManifestModel {
	public String graphName;
	public String version;
	@JsonProperty("package")
	public String packageName;
	public Map<String, Object> nodes;
	public Map<String, TablespaceDef> tablespaces;
	public Map<String, DomainDef> domains;
	public Map<String, RelationDef> relations;

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class TablespaceDef {
		public String file;
		public String description;
		public String mode;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class DomainDef {
		public String tablespace;
		public Map<String, String> attributes;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class RelationDef {
		public String source;
		public String target;
		public String tablespace;
		public Map<String, String> attributes;
		public Object direction;
		public String inverseAlias;
		public String cardinality;
	}
}
