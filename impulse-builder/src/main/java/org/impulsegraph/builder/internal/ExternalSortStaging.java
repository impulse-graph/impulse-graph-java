package org.impulsegraph.builder.internal;

import java.io.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages off-heap and on-disk staging for external sorting and topology
 * inversion (CSR -> CSC).
 */
public final class ExternalSortStaging {

	private final Path stagingDir;
	private final long memoryLimitBytes;

	public ExternalSortStaging(Path stagingDir, long memoryLimitBytes) {
		this.stagingDir = stagingDir;
		this.memoryLimitBytes = memoryLimitBytes > 0 ? memoryLimitBytes : 64 * 1024 * 1024L;
	}

	public record TopologyFiles(Path rowOffsetsFile, Path columnTargetsFile, long rowOffsetsBytes,
			long columnTargetsBytes) implements AutoCloseable {
		@Override
		public void close() {
			try {
				Files.deleteIfExists(rowOffsetsFile);
			} catch (IOException ignored) {
			}
			try {
				Files.deleteIfExists(columnTargetsFile);
			} catch (IOException ignored) {
			}
		}
	}

	/**
	 * Computes CSR topology from a source-sorted edge stream. Writes rowOffsets and
	 * columnTargets to staged files and returns their paths.
	 */
	public TopologyFiles buildCsr(int srcNodeCount, long edgeCount, int srcIdWidth, int edgeIndexWidth,
			Path outputPrefix, EdgeStreamReader reader) throws IOException {
		Path rowOffPath = Files.createTempFile(stagingDir, outputPrefix.getFileName().toString() + "_csr_row_", ".bin");
		Path colIdxPath = Files.createTempFile(stagingDir, outputPrefix.getFileName().toString() + "_csr_col_", ".bin");

		int[] rowOffsets = new int[srcNodeCount + 1];

		try (FileChannel colChannel = FileChannel.open(colIdxPath, StandardOpenOption.WRITE)) {
			ByteBuffer colBuf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
			long edgesRead = 0;

			while (reader.hasNext()) {
				int count = reader.readNextChunk();
				for (int i = 0; i < count; i++) {
					long u = reader.currentSrc(i);
					long v = reader.currentTgt(i);

					if (u >= 0 && u < srcNodeCount) {
						rowOffsets[(int) u + 1]++;
					}

					if (!colBuf.hasRemaining()) {
						colBuf.flip();
						colChannel.write(colBuf);
						colBuf.clear();
					}
					if (srcIdWidth == 2) {
						colBuf.putShort((short) v);
					} else if (srcIdWidth == 8) {
						colBuf.putLong(v);
					} else {
						colBuf.putInt((int) v);
					}
					edgesRead++;
				}
			}

			if (colBuf.position() > 0) {
				colBuf.flip();
				colChannel.write(colBuf);
			}
		}

		// Prefix sum for CSR row offsets
		for (int i = 0; i < srcNodeCount; i++) {
			rowOffsets[i + 1] += rowOffsets[i];
		}

		long rowOffBytes;
		try (FileChannel rowChannel = FileChannel.open(rowOffPath, StandardOpenOption.WRITE)) {
			ByteBuffer rowBuf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
			for (int offset : rowOffsets) {
				if (!rowBuf.hasRemaining()) {
					rowBuf.flip();
					rowChannel.write(rowBuf);
					rowBuf.clear();
				}
				if (edgeIndexWidth == 8) {
					rowBuf.putLong(offset);
				} else {
					rowBuf.putInt(offset);
				}
			}
			if (rowBuf.position() > 0) {
				rowBuf.flip();
				rowChannel.write(rowBuf);
			}
			rowOffBytes = (long) (srcNodeCount + 1) * edgeIndexWidth;
		}

		long colIdxBytes = edgeCount * srcIdWidth;
		return new TopologyFiles(rowOffPath, colIdxPath, rowOffBytes, colIdxBytes);
	}

	/**
	 * Computes CSC topology from an in-memory or staged CSR representation.
	 */
	public TopologyFiles buildCscFromCsr(int tgtNodeCount, long edgeCount, int tgtIdWidth, int edgeIndexWidth,
			Path outputPrefix, Path csrRowOffPath, Path csrColIdxPath, int srcNodeCount) throws IOException {
		Path cscRowOffPath = Files.createTempFile(stagingDir, outputPrefix.getFileName().toString() + "_csc_row_",
				".bin");
		Path cscColIdxPath = Files.createTempFile(stagingDir, outputPrefix.getFileName().toString() + "_csc_col_",
				".bin");

		int[] inDegrees = new int[tgtNodeCount];
		int[] cscRowOffsets = new int[tgtNodeCount + 1];

		// Pass 1: Count in-degrees
		try (FileChannel colCh = FileChannel.open(csrColIdxPath, StandardOpenOption.READ)) {
			ByteBuffer buf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
			while (colCh.read(buf) > 0) {
				buf.flip();
				while (buf.hasRemaining()) {
					long target = (tgtIdWidth == 2)
							? Short.toUnsignedInt(buf.getShort())
							: (tgtIdWidth == 8) ? buf.getLong() : buf.getInt();
					if (target >= 0 && target < tgtNodeCount) {
						inDegrees[(int) target]++;
					}
				}
				buf.clear();
			}
		}

		// Prefix sum
		int accum = 0;
		for (int n = 0; n < tgtNodeCount; n++) {
			cscRowOffsets[n] = accum;
			accum += inDegrees[n];
		}
		cscRowOffsets[tgtNodeCount] = accum;

		int[] currentOffsets = inDegrees; // reuse array
		System.arraycopy(cscRowOffsets, 0, currentOffsets, 0, tgtNodeCount);

		// Pass 2: Invert edges
		// For moderate edge counts, allocate CSC column index array off-heap
		long colBytesTotal = edgeCount * tgtIdWidth;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment cscColSeg = arena.allocate(colBytesTotal, 128);

			// Read CSR row offsets
			int[] csrRowOff = new int[srcNodeCount + 1];
			try (FileChannel rowCh = FileChannel.open(csrRowOffPath, StandardOpenOption.READ)) {
				ByteBuffer rBuf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
				int idx = 0;
				while (rowCh.read(rBuf) > 0) {
					rBuf.flip();
					while (rBuf.hasRemaining() && idx <= srcNodeCount) {
						csrRowOff[idx++] = (edgeIndexWidth == 8) ? (int) rBuf.getLong() : rBuf.getInt();
					}
					rBuf.clear();
				}
			}

			// Stream CSR columns and populate CSC
			try (FileChannel colCh = FileChannel.open(csrColIdxPath, StandardOpenOption.READ)) {
				ByteBuffer cBuf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
				int currentSource = 0;
				int edgeCounter = 0;

				while (colCh.read(cBuf) > 0) {
					cBuf.flip();
					while (cBuf.hasRemaining()) {
						while (currentSource < srcNodeCount && edgeCounter >= csrRowOff[currentSource + 1]) {
							currentSource++;
						}

						long v = (tgtIdWidth == 2)
								? Short.toUnsignedInt(cBuf.getShort())
								: (tgtIdWidth == 8) ? cBuf.getLong() : cBuf.getInt();

						if (v >= 0 && v < tgtNodeCount) {
							int insertPos = currentOffsets[(int) v]++;
							if (tgtIdWidth == 2) {
								cscColSeg.setAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, insertPos,
										(short) currentSource);
							} else if (tgtIdWidth == 8) {
								cscColSeg.setAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, insertPos, currentSource);
							} else {
								cscColSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, insertPos, currentSource);
							}
						}
						edgeCounter++;
					}
					cBuf.clear();
				}
			}

			// Write CSC col targets to file
			try (FileChannel outCh = FileChannel.open(cscColIdxPath, StandardOpenOption.WRITE)) {
				ByteBuffer outBuf = cscColSeg.asByteBuffer();
				while (outBuf.hasRemaining()) {
					outCh.write(outBuf);
				}
			}
		}

		// Write CSC row offsets to file
		long rowOffBytes;
		try (FileChannel outCh = FileChannel.open(cscRowOffPath, StandardOpenOption.WRITE)) {
			ByteBuffer rBuf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
			for (int offset : cscRowOffsets) {
				if (!rBuf.hasRemaining()) {
					rBuf.flip();
					outCh.write(rBuf);
					rBuf.clear();
				}
				if (edgeIndexWidth == 8) {
					rBuf.putLong(offset);
				} else {
					rBuf.putInt(offset);
				}
			}
			if (rBuf.position() > 0) {
				rBuf.flip();
				outCh.write(rBuf);
			}
			rowOffBytes = (long) (tgtNodeCount + 1) * edgeIndexWidth;
		}

		return new TopologyFiles(cscRowOffPath, cscColIdxPath, rowOffBytes, colBytesTotal);
	}

	public interface EdgeStreamReader {
		boolean hasNext();
		int readNextChunk();
		long currentSrc(int index);
		long currentTgt(int index);
	}
}
