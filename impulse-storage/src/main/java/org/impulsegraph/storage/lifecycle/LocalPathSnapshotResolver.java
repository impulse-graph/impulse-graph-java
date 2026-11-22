package org.impulsegraph.storage.lifecycle;

import org.impulsegraph.api.lifecycle.CandidateSnapshotRef;
import org.impulsegraph.api.lifecycle.ConsumerSchemaContext;
import org.impulsegraph.api.lifecycle.SnapshotResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Lightweight local filesystem {@link SnapshotResolver} that detects snapshot
 * updates using either file metadata ({@code lastModifiedTime + size}) or full
 * SHA-256 content hashing.
 */
public final class LocalPathSnapshotResolver implements SnapshotResolver {

	public enum RevisionMode {
		METADATA, SHA256
	}

	private final Path snapshotPath;
	private final RevisionMode revisionMode;
	private final boolean deleteOnRejectOrClose;

	public LocalPathSnapshotResolver(Path snapshotPath) {
		this(snapshotPath, RevisionMode.METADATA, false);
	}

	public LocalPathSnapshotResolver(Path snapshotPath, RevisionMode revisionMode) {
		this(snapshotPath, revisionMode, false);
	}

	public LocalPathSnapshotResolver(Path snapshotPath, RevisionMode revisionMode, boolean deleteOnRejectOrClose) {
		this.snapshotPath = Objects.requireNonNull(snapshotPath, "snapshotPath must not be null");
		this.revisionMode = Objects.requireNonNull(revisionMode, "revisionMode must not be null");
		this.deleteOnRejectOrClose = deleteOnRejectOrClose;
	}

	@Override
	public Optional<CandidateSnapshotRef> resolveNext(ConsumerSchemaContext context) throws IOException {
		Objects.requireNonNull(context, "context must not be null");
		if (!Files.exists(snapshotPath) || !Files.isRegularFile(snapshotPath)) {
			return Optional.empty();
		}

		String revisionId = computeRevision(snapshotPath);
		if (context.activeSnapshotRevision().isPresent() && context.activeSnapshotRevision().get().equals(revisionId)) {
			return Optional.empty();
		}

		return Optional.of(new CandidateSnapshotRef(revisionId, snapshotPath, deleteOnRejectOrClose));
	}

	private String computeRevision(Path path) throws IOException {
		if (revisionMode == RevisionMode.SHA256) {
			return "sha256:" + computeSha256(path);
		}
		BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
		return "mtime:" + attrs.lastModifiedTime().toMillis() + ":size:" + attrs.size();
	}

	private static String computeSha256(Path path) throws IOException {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];
			try (InputStream in = Files.newInputStream(path)) {
				int read;
				while ((read = in.read(buffer)) != -1) {
					md.update(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(md.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
		}
	}
}
