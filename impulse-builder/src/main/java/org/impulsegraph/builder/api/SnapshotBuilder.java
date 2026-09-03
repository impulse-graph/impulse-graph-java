package org.impulsegraph.builder.api;

import org.impulsegraph.builder.internal.StreamingSnapshotWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.List;

/**
 * Fluent builder entry point for streaming Impulse Binary Snapshots (.imps
 * v0.9.0) with strict single-pass S3 semantics and bounded O(chunk) heap
 * footprint.
 */
public interface SnapshotBuilder {

	static SnapshotBuilder create() {
		return new StreamingSnapshotWriter();
	}

	/**
	 * Configures a local directory for staging temporary files (such as external
	 * sort runs when computing CSC from a source-sorted stream, or spilling large
	 * string pools).
	 */
	SnapshotBuilder withStagingDirectory(Path tempDir);

	/**
	 * Configures maximum RAM limit in bytes for in-memory staging buffers before
	 * spilling to disk.
	 */
	SnapshotBuilder withStagingMemoryLimit(long bytes);

	/**
	 * Cryptographically signs the mandatory SHA-256 payload checksum using the
	 * provided private key, embedding the signature and public certificate chain
	 * into the file footer.
	 */
	SnapshotBuilder withSignature(PrivateKey privateKey, List<Certificate> certChain);

	/**
	 * Registers a node domain schema and its data sources.
	 */
	SnapshotBuilder addDomain(String domainName, DomainDefinition domain);

	/**
	 * Registers a relation schema and its edge data sources.
	 */
	SnapshotBuilder addRelation(String relationName, RelationDefinition relation);

	/**
	 * Adds a custom key-value string metadata attribute to the trailing footer.
	 */
	SnapshotBuilder addFooterMetadata(String key, String value);

	/**
	 * Adds a raw binary metadata payload to the trailing footer.
	 */
	SnapshotBuilder addFooterMetadata(String key, byte[] payload);

	/**
	 * Sequentially streams the snapshot binary in a strict single-pass to the
	 * output channel.
	 */
	void writeTo(WritableByteChannel channel) throws IOException;

	/**
	 * Streams the snapshot binary to an OutputStream.
	 */
	default void writeTo(OutputStream out) throws IOException {
		writeTo(Channels.newChannel(out));
	}

	/**
	 * Writes the snapshot binary direct to a file.
	 */
	default void writeTo(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING)) {
			writeTo(channel);
		}
	}

	/**
	 * Collects the snapshot binary directly into an in-memory byte array (useful
	 * for testing).
	 */
	default byte[] toByteArray() throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		writeTo(baos);
		return baos.toByteArray();
	}
}
