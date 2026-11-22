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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

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
			System.Logger log = System.getLogger(ExternalSortStaging.class.getName());
			try {
				Files.deleteIfExists(rowOffsetsFile);
			} catch (IOException e) {
				log.log(System.Logger.Level.DEBUG, "Failed to delete staged rowOffsetsFile " + rowOffsetsFile, e);
			}
			try {
				Files.deleteIfExists(columnTargetsFile);
			} catch (IOException e) {
				log.log(System.Logger.Level.DEBUG, "Failed to delete staged columnTargetsFile " + columnTargetsFile, e);
			}
		}
	}

	/**
	 * Computes CSR topology from a source-sorted edge stream. Writes rowOffsets and
	 * columnTargets to staged files and returns their paths.
	 */
	public TopologyFiles buildCsr(int srcNodeCount, long edgeCount, int tgtIdWidth, int edgeIndexWidth,
			Path outputPrefix, EdgeStreamReader reader) throws IOException {
		Path rowOffPath = Files.createTempFile(stagingDir, outputPrefix.getFileName().toString() + "_csr_row_", ".bin");
		Path colIdxPath = Files.createTempFile(stagingDir, outputPrefix.getFileName().toString() + "_csr_col_", ".bin");

		int[] rowOffsets = new int[srcNodeCount + 1];

		try (FileChannel colChannel = FileChannel.open(colIdxPath, StandardOpenOption.WRITE)) {
			ByteBuffer colBuf = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.LITTLE_ENDIAN);
			long edgesRead = 0;

			while (reader.hasNext()) {
				int count = reader.readNextChunk();
				reader.accumulateRowOffsets(rowOffsets, count, srcNodeCount);

				ByteBuffer tgtBuf = reader.currentTgtBuffer(count, tgtIdWidth);
				if (tgtBuf != null) {
					while (tgtBuf.hasRemaining()) {
						colChannel.write(tgtBuf);
					}
				} else {
					for (int i = 0; i < count; i++) {
						long v = reader.currentTgt(i);
						if (!colBuf.hasRemaining()) {
							colBuf.flip();
							writeFully(colChannel, colBuf);
							colBuf.clear();
						}
						if (tgtIdWidth == 2) {
							colBuf.putShort((short) v);
						} else if (tgtIdWidth == 8) {
							colBuf.putLong(v);
						} else {
							colBuf.putInt((int) v);
						}
					}
				}
				edgesRead += count;
			}

			if (colBuf.position() > 0) {
				colBuf.flip();
				writeFully(colChannel, colBuf);
			}
		}

		// Prefix sum for CSR row offsets
		for (int i = 0; i < srcNodeCount; i++) {
			rowOffsets[i + 1] += rowOffsets[i];
		}

		long rowOffBytes;
		try (FileChannel rowChannel = FileChannel.open(rowOffPath, StandardOpenOption.WRITE)) {
			ByteBuffer rowBuf = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.LITTLE_ENDIAN);
			for (int offset : rowOffsets) {
				if (!rowBuf.hasRemaining()) {
					rowBuf.flip();
					writeFully(rowChannel, rowBuf);
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
				writeFully(rowChannel, rowBuf);
			}
			rowOffBytes = (long) (srcNodeCount + 1) * edgeIndexWidth;
		}

		long colIdxBytes = edgeCount * tgtIdWidth;
		return new TopologyFiles(rowOffPath, colIdxPath, rowOffBytes, colIdxBytes);
	}

	/**
	 * Computes CSC topology from an in-memory or staged CSR representation.
	 */
	public TopologyFiles buildCscFromCsr(int tgtNodeCount, long edgeCount, int srcIdWidth, int tgtIdWidth,
			int edgeIndexWidth, Path outputPrefix, Path csrRowOffPath, Path csrColIdxPath, int srcNodeCount)
			throws IOException {
		Path cscRowOffPath = Files.createTempFile(stagingDir, outputPrefix.getFileName().toString() + "_csc_row_",
				".bin");
		Path cscColIdxPath = Files.createTempFile(stagingDir, outputPrefix.getFileName().toString() + "_csc_col_",
				".bin");

		long[] inDegrees = new long[tgtNodeCount];
		long[] cscRowOffsets = new long[tgtNodeCount + 1];

		// Pass 1: Count in-degrees
		try (FileChannel colCh = FileChannel.open(csrColIdxPath, StandardOpenOption.READ)) {
			ByteBuffer buf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
			while (colCh.read(buf) > 0) {
				buf.flip();
				while (buf.hasRemaining()) {
					long target = (tgtIdWidth == 2)
							? Short.toUnsignedInt(buf.getShort())
							: (tgtIdWidth == 8) ? buf.getLong() : Integer.toUnsignedLong(buf.getInt());
					if (target >= 0 && target < tgtNodeCount) {
						inDegrees[(int) target]++;
					}
				}
				buf.clear();
			}
		}

		// Prefix sum
		long accum = 0;
		for (int n = 0; n < tgtNodeCount; n++) {
			cscRowOffsets[n] = accum;
			accum += inDegrees[n];
		}
		cscRowOffsets[tgtNodeCount] = accum;

		long[] currentOffsets = inDegrees; // reuse array
		System.arraycopy(cscRowOffsets, 0, currentOffsets, 0, tgtNodeCount);

		// Pass 2: Invert edges
		// For moderate edge counts, allocate CSC column index array off-heap
		long colBytesTotal = edgeCount * srcIdWidth;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment cscColSeg = arena.allocate(colBytesTotal, 128);

			// Read CSR row offsets
			long[] csrRowOff = new long[srcNodeCount + 1];
			try (FileChannel rowCh = FileChannel.open(csrRowOffPath, StandardOpenOption.READ)) {
				ByteBuffer rBuf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
				int idx = 0;
				while (rowCh.read(rBuf) > 0) {
					rBuf.flip();
					while (rBuf.hasRemaining() && idx <= srcNodeCount) {
						csrRowOff[idx++] = (edgeIndexWidth == 8)
								? rBuf.getLong()
								: Integer.toUnsignedLong(rBuf.getInt());
					}
					rBuf.clear();
				}
			}

			// Stream CSR columns and populate CSC
			try (FileChannel colCh = FileChannel.open(csrColIdxPath, StandardOpenOption.READ)) {
				ByteBuffer cBuf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
				int currentSource = 0;
				long edgeCounter = 0;

				while (colCh.read(cBuf) > 0) {
					cBuf.flip();
					while (cBuf.hasRemaining()) {
						while (currentSource < srcNodeCount && edgeCounter >= csrRowOff[currentSource + 1]) {
							currentSource++;
						}

						long v = (tgtIdWidth == 2)
								? Short.toUnsignedInt(cBuf.getShort())
								: (tgtIdWidth == 8) ? cBuf.getLong() : Integer.toUnsignedLong(cBuf.getInt());

						if (v >= 0 && v < tgtNodeCount) {
							long insertPos = currentOffsets[(int) v]++;
							if (srcIdWidth == 2) {
								cscColSeg.setAtIndex(
										ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), insertPos,
										(short) currentSource);
							} else if (srcIdWidth == 8) {
								cscColSeg.setAtIndex(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
										insertPos, currentSource);
							} else {
								cscColSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
										insertPos, currentSource);
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
			for (long offset : cscRowOffsets) {
				if (!rBuf.hasRemaining()) {
					rBuf.flip();
					writeFully(outCh, rBuf);
					rBuf.clear();
				}
				if (edgeIndexWidth == 8) {
					rBuf.putLong(offset);
				} else {
					rBuf.putInt(Math.toIntExact(offset));
				}
			}
			if (rBuf.position() > 0) {
				rBuf.flip();
				writeFully(outCh, rBuf);
			}
			rowOffBytes = (long) (tgtNodeCount + 1) * edgeIndexWidth;
		}

		return new TopologyFiles(cscRowOffPath, cscColIdxPath, rowOffBytes, colBytesTotal);
	}

	private static void writeFully(FileChannel ch, ByteBuffer buf) throws IOException {
		while (buf.hasRemaining()) {
			ch.write(buf);
		}
	}

	public interface EdgeStreamReader {
		boolean hasNext();
		int readNextChunk();
		long currentSrc(int index);
		long currentTgt(int index);

		default ByteBuffer currentTgtBuffer(int count, int tgtIdWidth) {
			return null;
		}

		default void accumulateRowOffsets(int[] rowOffsets, int count, int srcNodeCount) {
			for (int i = 0; i < count; i++) {
				long u = currentSrc(i);
				if (u >= 0 && u < srcNodeCount) {
					rowOffsets[(int) u + 1]++;
				}
			}
		}
	}

	/**
	 * Creates a disk-spilling external merge sorter for unsorted edge records with
	 * optional raw attribute strings, bounded by {@code memoryLimitBytes}.
	 */
	public EdgeRecordRunSorter newEdgeRecordSorter(String prefix, int attrCount) {
		return new EdgeRecordRunSorter(stagingDir, memoryLimitBytes, prefix, attrCount);
	}

	public record EdgeRecord(long srcId, long tgtId, long rowIdx,
			String[] attrValues) implements Comparable<EdgeRecord> {
		@Override
		public int compareTo(EdgeRecord o) {
			int cmp = Long.compare(this.srcId, o.srcId);
			if (cmp != 0) {
				return cmp;
			}
			cmp = Long.compare(this.tgtId, o.tgtId);
			if (cmp != 0) {
				return cmp;
			}
			return Long.compare(this.rowIdx, o.rowIdx);
		}
	}

	@FunctionalInterface
	public interface EdgeRecordConsumer {
		void accept(long srcId, long tgtId, String[] attrValues) throws IOException;
	}

	/**
	 * Disk-spilling external sort for edge records + edge attributes. Sorts
	 * incoming records into runs bounded by {@code memoryLimitBytes} and merges
	 * them in CSR order {@code (srcId, tgtId, rowIdx)}.
	 */
	public static final class EdgeRecordRunSorter implements AutoCloseable {
		private final Path stagingDir;
		private final long memoryLimitBytes;
		private final String prefix;
		private final int attrCount;
		private final List<EdgeRecord> buffer = new ArrayList<>();
		private final List<Path> runFiles = new ArrayList<>();
		private long estimatedBytes = 0L;
		private long totalEdges = 0L;

		EdgeRecordRunSorter(Path stagingDir, long memoryLimitBytes, String prefix, int attrCount) {
			this.stagingDir = stagingDir;
			this.memoryLimitBytes = Math.max(64 * 1024L, memoryLimitBytes);
			this.prefix = prefix;
			this.attrCount = attrCount;
		}

		public void add(long srcId, long tgtId, long rowIdx, String[] attrValues) throws IOException {
			buffer.add(new EdgeRecord(srcId, tgtId, rowIdx, attrValues));
			totalEdges++;
			long rowBytes = 64L + ((long) attrCount * 16L);
			if (attrValues != null) {
				for (String s : attrValues) {
					if (s != null) {
						rowBytes += 40L + (s.length() * 2L);
					}
				}
			}
			estimatedBytes += rowBytes;
			if (estimatedBytes >= memoryLimitBytes) {
				spillRun();
			}
		}

		private void spillRun() throws IOException {
			if (buffer.isEmpty()) {
				return;
			}
			buffer.sort(Comparator.naturalOrder());
			Path runPath = Files.createTempFile(stagingDir, prefix + "_run_", ".bin");
			runFiles.add(runPath);
			try (DataOutputStream out = new DataOutputStream(
					new BufferedOutputStream(Files.newOutputStream(runPath), 64 * 1024))) {
				for (EdgeRecord rec : buffer) {
					writeEdgeRecord(out, rec, attrCount);
				}
			}
			buffer.clear();
			estimatedBytes = 0L;
		}

		public long edgeCount() {
			return totalEdges;
		}

		/**
		 * Streams all added edges in sorted {@code (srcId, tgtId, rowIdx)} order to the
		 * given consumer.
		 */
		public void drainSorted(EdgeRecordConsumer consumer) throws IOException {
			if (runFiles.isEmpty()) {
				buffer.sort(Comparator.naturalOrder());
				for (EdgeRecord rec : buffer) {
					consumer.accept(rec.srcId(), rec.tgtId(), rec.attrValues());
				}
				buffer.clear();
				estimatedBytes = 0L;
				return;
			}

			if (!buffer.isEmpty()) {
				spillRun();
			}

			record RunCursor(EdgeRecord current, DataInputStream in, int runIdx) implements Comparable<RunCursor> {
				@Override
				public int compareTo(RunCursor o) {
					return this.current.compareTo(o.current);
				}
			}

			List<DataInputStream> openStreams = new ArrayList<>(runFiles.size());
			PriorityQueue<RunCursor> pq = new PriorityQueue<>(runFiles.size());
			try {
				for (int i = 0; i < runFiles.size(); i++) {
					DataInputStream in = new DataInputStream(
							new BufferedInputStream(Files.newInputStream(runFiles.get(i)), 64 * 1024));
					openStreams.add(in);
					EdgeRecord first = readEdgeRecord(in, attrCount);
					if (first != null) {
						pq.add(new RunCursor(first, in, i));
					}
				}
				while (!pq.isEmpty()) {
					RunCursor top = pq.poll();
					EdgeRecord rec = top.current();
					consumer.accept(rec.srcId(), rec.tgtId(), rec.attrValues());
					EdgeRecord next = readEdgeRecord(top.in(), attrCount);
					if (next != null) {
						pq.add(new RunCursor(next, top.in(), top.runIdx()));
					}
				}
			} finally {
				for (DataInputStream in : openStreams) {
					try {
						in.close();
					} catch (IOException ignored) {
					}
				}
			}
		}

		@Override
		public void close() {
			buffer.clear();
			System.Logger log = System.getLogger(ExternalSortStaging.class.getName());
			for (Path runFile : runFiles) {
				try {
					Files.deleteIfExists(runFile);
				} catch (IOException e) {
					log.log(System.Logger.Level.DEBUG, "Failed to delete sort run file " + runFile, e);
				}
			}
			runFiles.clear();
		}

		private static void writeEdgeRecord(DataOutputStream out, EdgeRecord rec, int attrCount) throws IOException {
			out.writeLong(rec.srcId());
			out.writeLong(rec.tgtId());
			out.writeLong(rec.rowIdx());
			String[] vals = rec.attrValues();
			for (int i = 0; i < attrCount; i++) {
				String s = (vals != null && i < vals.length) ? vals[i] : null;
				if (s == null) {
					out.writeInt(-1);
				} else {
					byte[] b = s.getBytes(StandardCharsets.UTF_8);
					out.writeInt(b.length);
					if (b.length > 0) {
						out.write(b);
					}
				}
			}
		}

		private static EdgeRecord readEdgeRecord(DataInputStream in, int attrCount) throws IOException {
			long srcId;
			try {
				srcId = in.readLong();
			} catch (EOFException eof) {
				return null;
			}
			long tgtId = in.readLong();
			long rowIdx = in.readLong();
			String[] vals = attrCount > 0 ? new String[attrCount] : null;
			for (int i = 0; i < attrCount; i++) {
				int len = in.readInt();
				if (len < 0) {
					vals[i] = null;
				} else if (len == 0) {
					vals[i] = "";
				} else {
					byte[] b = in.readNBytes(len);
					vals[i] = new String(b, StandardCharsets.UTF_8);
				}
			}
			return new EdgeRecord(srcId, tgtId, rowIdx, vals);
		}
	}
}
