package org.impulsegraph.storage.csr;

import org.impulsegraph.api.schema.GraphManifest;
import org.impulsegraph.api.schema.SchemaYamlCodec;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Zero-dependency YAML parser for Impulse Graph manifest schemas. Delegates to
 * the unified {@link SchemaYamlCodec}.
 */
public class ManifestYamlParser {

	public static GraphManifest parse(Path yamlPath) throws IOException {
		return SchemaYamlCodec.parse(yamlPath);
	}
}
