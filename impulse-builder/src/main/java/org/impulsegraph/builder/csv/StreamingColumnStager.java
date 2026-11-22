package org.impulsegraph.builder.csv;

import org.impulsegraph.builder.api.DataType;
import org.impulsegraph.builder.api.Nullability;
import org.impulsegraph.builder.spi.AttributeChunkIterator;
import org.impulsegraph.builder.spi.AttributeDataSource;
import org.impulsegraph.builder.spi.EdgeChunkIterator;
import org.impulsegraph.builder.spi.RelationDataSource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * File-backed O(chunk) staging writers and streaming SPI data sources for
 * {@link CsvManifestSnapshotBuilder}. Zero full-file heap buffering.
 */
final class StreamingColumnStager {

	private StreamingColumnStager() {
	}

	/**
	 * Streams encoded attribute rows to a staged binary file and exposes a chunked
	 * {@link AttributeDataSource} for {@code StreamingSnapshotWriter}.
	 */
	static final class ColumnWriter implements AutoCloseable {
		private final Path file;
		private final DataType dataType;
		private final Nullability nullability;
		private final boolean isNullable;
		private final boolean isVariable;
		private final int elemBytes;
		private final DataOutputStream out;
		private final ByteBuffer scratch;
		private final byte[] zeroElem;
		private long rowCount = 0L;

		ColumnWriter(Path file, DataType dataType, Nullability nullability) throws IOException {
			this.file = file;
			this.dataType = dataType;
			this.nullability = nullability;
			this.isNullable = (nullability == Nullability.NULLABLE);
			this.isVariable = dataType.isVariableLength();
			this.elemBytes = isVariable ? 0 : Math.max(1, dataType.totalValueBytes());
			this.out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 64 * 1024));
			this.scratch = isVariable ? null : ByteBuffer.allocate(elemBytes).order(ByteOrder.LITTLE_ENDIAN);
			this.zeroElem = isVariable ? null : new byte[elemBytes];
		}

		void writeValue(Object rawVal) throws IOException {
			rowCount++;
			if (isVariable) {
				if (rawVal == null) {
					if (isNullable) {
						out.writeInt(-1);
					} else {
						out.writeInt(0);
					}
					return;
				}
				String s = (rawVal instanceof String str) ? str : String.valueOf(rawVal);
				if (isNullable && s.isEmpty()) {
					out.writeInt(-1);
					return;
				}
				byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
				out.writeInt(utf8.length);
				if (utf8.length > 0) {
					out.write(utf8);
				}
				return;
			}

			// Fixed-width scalar or fixed-size vector
			boolean isNullValue = isNullFixedValue(rawVal);
			if (isNullable) {
				out.writeByte(isNullValue ? 0 : 1);
			}
			if (isNullValue) {
				out.write(zeroElem);
				return;
			}

			scratch.clear();
			int dim = dataType.dimension();
			byte base = dataType.baseCode();
			if (dim > 1) {
				writeVectorElements(scratch, base, dim, rawVal);
			} else {
				writeScalarElement(scratch, base, rawVal);
			}
			out.write(scratch.array(), 0, elemBytes);
		}

		private static boolean isNullFixedValue(Object rawVal) {
			if (rawVal == null) {
				return true;
			}
			if (rawVal instanceof String s) {
				return s.isBlank();
			}
			return false;
		}

		private static void writeVectorElements(ByteBuffer buf, byte baseCode, int dim, Object rawVal) {
			if (rawVal instanceof List<?> list) {
				if (list.size() != dim) {
					throw new IllegalArgumentException(
							"Vector dimension mismatch: expected " + dim + ", got " + list.size());
				}
				for (int i = 0; i < dim; i++) {
					writeScalarElement(buf, baseCode, list.get(i));
				}
				return;
			}
			String text = String.valueOf(rawVal).trim();
			if (text.startsWith("[") && text.endsWith("]")) {
				text = text.substring(1, text.length() - 1).trim();
			}
			String[] tokens = text.isEmpty() ? new String[0] : text.split("[,;\\s]+");
			if (tokens.length != dim) {
				throw new IllegalArgumentException("Vector dimension mismatch: expected " + dim + ", got "
						+ tokens.length + " in '" + rawVal + "'");
			}
			for (int i = 0; i < dim; i++) {
				writeScalarElement(buf, baseCode, tokens[i]);
			}
		}

		private static void writeScalarElement(ByteBuffer buf, byte baseCode, Object rawVal) {
			if (rawVal == null) {
				putZeroScalar(buf, baseCode);
				return;
			}
			switch (baseCode) {
				case 0x01 -> { // INT8 / BOOL
					if (rawVal instanceof Boolean b) {
						buf.put((byte) (b ? 1 : 0));
					} else if (rawVal instanceof Number n) {
						buf.put(n.byteValue());
					} else {
						String s = rawVal.toString().trim();
						if ("true".equalsIgnoreCase(s)) {
							buf.put((byte) 1);
						} else if ("false".equalsIgnoreCase(s)) {
							buf.put((byte) 0);
						} else {
							buf.put(Byte.parseByte(s));
						}
					}
				}
				case 0x02 -> { // INT16
					if (rawVal instanceof Number n) {
						buf.putShort(n.shortValue());
					} else {
						buf.putShort(Short.parseShort(rawVal.toString().trim()));
					}
				}
				case 0x03 -> { // INT32
					if (rawVal instanceof Number n) {
						buf.putInt(n.intValue());
					} else {
						buf.putInt(Integer.parseInt(rawVal.toString().trim()));
					}
				}
				case 0x04, 0x08, 0x09 -> { // INT64 / TIMESTAMP_MS / TIMESTAMP_NS
					if (rawVal instanceof Number n) {
						buf.putLong(n.longValue());
					} else {
						buf.putLong(Long.parseLong(rawVal.toString().trim()));
					}
				}
				case 0x05 -> { // FLOAT16
					float f = (rawVal instanceof Number n)
							? n.floatValue()
							: Float.parseFloat(rawVal.toString().trim());
					buf.putShort(Float.floatToFloat16(f));
				}
				case 0x06 -> { // FLOAT32
					if (rawVal instanceof Number n) {
						buf.putFloat(n.floatValue());
					} else {
						buf.putFloat(Float.parseFloat(rawVal.toString().trim()));
					}
				}
				case 0x07 -> { // FLOAT64
					if (rawVal instanceof Number n) {
						buf.putDouble(n.doubleValue());
					} else {
						buf.putDouble(Double.parseDouble(rawVal.toString().trim()));
					}
				}
				default -> throw new IllegalArgumentException(
						"Unsupported fixed attribute baseCode: 0x" + Integer.toHexString(Byte.toUnsignedInt(baseCode)));
			}
		}

		private static void putZeroScalar(ByteBuffer buf, byte baseCode) {
			switch (baseCode) {
				case 0x01 -> buf.put((byte) 0);
				case 0x02, 0x05 -> buf.putShort((short) 0);
				case 0x03, 0x06 -> buf.putInt(0);
				case 0x04, 0x07, 0x08, 0x09 -> buf.putLong(0L);
				default -> buf.putInt(0);
			}
		}

		long rowCount() {
			return rowCount;
		}

		@Override
		public void close() throws IOException {
			out.flush();
			out.close();
		}

		AttributeDataSource toAttributeDataSource() {
			return new FileBackedAttributeSource(file, dataType, nullability, rowCount);
		}
	}

	private static final class FileBackedAttributeSource implements AttributeDataSource {
		private final Path file;
		private final DataType dataType;
		private final Nullability nullability;
		private final long totalRows;

		FileBackedAttributeSource(Path file, DataType dataType, Nullability nullability, long totalRows) {
			this.file = file;
			this.dataType = dataType;
			this.nullability = nullability;
			this.totalRows = totalRows;
		}

		@Override
		public DataType dataType() {
			return dataType;
		}

		@Override
		public Nullability nullability() {
			return nullability;
		}

		@Override
		public AttributeChunkIterator iterator() {
			try {
				if (dataType.isVariableLength()) {
					return new VariableColumnIterator(file, totalRows);
				}
				return new FixedColumnIterator(file, Math.max(1, dataType.totalValueBytes()),
						nullability == Nullability.NULLABLE, totalRows);
			} catch (IOException e) {
				throw new RuntimeException("Failed to open staged attribute file: " + file, e);
			}
		}
	}

	private static final class VariableColumnIterator implements AttributeChunkIterator {
		private final DataInputStream in;
		private final long totalRows;
		private long readRows = 0L;

		VariableColumnIterator(Path file, long totalRows) throws IOException {
			this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 64 * 1024));
			this.totalRows = totalRows;
		}

		@Override
		public boolean hasNext() {
			return readRows < totalRows;
		}

		@Override
		public int nextChunk(MemorySegment destination, int limit) {
			throw new UnsupportedOperationException("Use nextVariableChunk for variable-length attributes");
		}

		@Override
		public int nextVariableChunk(List<byte[]> destination, int limit) {
			int count = 0;
			try {
				while (readRows < totalRows && count < limit) {
					int len = in.readInt();
					if (len < 0) {
						destination.add(null);
					} else if (len == 0) {
						destination.add(new byte[0]);
					} else {
						byte[] bytes = in.readNBytes(len);
						if (bytes.length != len) {
							throw new EOFException("Truncated staged string payload");
						}
						destination.add(bytes);
					}
					readRows++;
					count++;
				}
			} catch (IOException e) {
				throw new RuntimeException("Error reading staged variable attribute", e);
			}
			return count;
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}

	private static final class FixedColumnIterator implements AttributeChunkIterator {
		private final DataInputStream in;
		private final int elemBytes;
		private final boolean isNullable;
		private final long totalRows;
		private final byte[] rowBuf;
		private long readRows = 0L;

		FixedColumnIterator(Path file, int elemBytes, boolean isNullable, long totalRows) throws IOException {
			this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 64 * 1024));
			this.elemBytes = elemBytes;
			this.isNullable = isNullable;
			this.totalRows = totalRows;
			this.rowBuf = new byte[elemBytes];
		}

		@Override
		public boolean hasNext() {
			return readRows < totalRows;
		}

		@Override
		public int nextChunk(MemorySegment destination, int limit) {
			return nextChunk(destination, null, limit);
		}

		@Override
		public int nextChunk(MemorySegment destination, byte[] validityBits, int limit) {
			int count = 0;
			try {
				while (readRows < totalRows && count < limit) {
					if (isNullable) {
						byte valid = in.readByte();
						if (valid != 0 && validityBits != null) {
							validityBits[count >>> 3] |= (byte) (1 << (count & 7));
						}
					} else if (validityBits != null) {
						validityBits[count >>> 3] |= (byte) (1 << (count & 7));
					}
					in.readFully(rowBuf);
					MemorySegment.copy(rowBuf, 0, destination, ValueLayout.JAVA_BYTE, (long) count * elemBytes,
							elemBytes);
					readRows++;
					count++;
				}
			} catch (IOException e) {
				throw new RuntimeException("Error reading staged fixed attribute", e);
			}
			return count;
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}

	/**
	 * Streams sorted {@code (srcId, tgtId)} edge pairs to a staged binary file and
	 * exposes a chunked {@link RelationDataSource}.
	 */
	static final class EdgeFileWriter implements AutoCloseable {
		private final Path file;
		private final int srcIdWidth;
		private final int tgtIdWidth;
		private final FileChannel ch;
		private final ByteBuffer outBuf;
		private long edgeCount = 0L;

		EdgeFileWriter(Path file, int srcIdWidth, int tgtIdWidth) throws IOException {
			this.file = file;
			this.srcIdWidth = srcIdWidth;
			this.tgtIdWidth = tgtIdWidth;
			this.ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING);
			this.outBuf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
		}

		void writeEdge(long srcId, long tgtId) throws IOException {
			if (outBuf.remaining() < srcIdWidth + tgtIdWidth) {
				outBuf.flip();
				while (outBuf.hasRemaining()) {
					ch.write(outBuf);
				}
				outBuf.clear();
			}
			putId(outBuf, srcId, srcIdWidth);
			putId(outBuf, tgtId, tgtIdWidth);
			edgeCount++;
		}

		private static void putId(ByteBuffer buf, long id, int width) {
			if (width == 2) {
				buf.putShort((short) id);
			} else if (width == 8) {
				buf.putLong(id);
			} else {
				buf.putInt((int) id);
			}
		}

		long edgeCount() {
			return edgeCount;
		}

		@Override
		public void close() throws IOException {
			if (outBuf.position() > 0) {
				outBuf.flip();
				while (outBuf.hasRemaining()) {
					ch.write(outBuf);
				}
				outBuf.clear();
			}
			ch.close();
		}

		RelationDataSource toRelationDataSource() {
			return new FileBackedRelationDataSource(file, srcIdWidth, tgtIdWidth, edgeCount);
		}
	}

	private static final class FileBackedRelationDataSource implements RelationDataSource {
		private final Path file;
		private final int srcIdWidth;
		private final int tgtIdWidth;
		private final long edgeCount;

		FileBackedRelationDataSource(Path file, int srcIdWidth, int tgtIdWidth, long edgeCount) {
			this.file = file;
			this.srcIdWidth = srcIdWidth;
			this.tgtIdWidth = tgtIdWidth;
			this.edgeCount = edgeCount;
		}

		@Override
		public long getEdgeCount() {
			return edgeCount;
		}

		@Override
		public EdgeChunkIterator getEdges() {
			try {
				return new FileEdgeChunkIterator(file, srcIdWidth, tgtIdWidth, edgeCount);
			} catch (IOException e) {
				throw new RuntimeException("Failed to open staged edge file: " + file, e);
			}
		}
	}

	private static final class FileEdgeChunkIterator implements EdgeChunkIterator {
		private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED
				.withOrder(ByteOrder.LITTLE_ENDIAN);
		private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED
				.withOrder(ByteOrder.LITTLE_ENDIAN);
		private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG_UNALIGNED
				.withOrder(ByteOrder.LITTLE_ENDIAN);

		private final FileChannel ch;
		private final int srcIdWidth;
		private final int tgtIdWidth;
		private final int pairBytes;
		private final long totalEdges;
		private final ByteBuffer inBuf;
		private long readEdges = 0L;

		FileEdgeChunkIterator(Path file, int srcIdWidth, int tgtIdWidth, long totalEdges) throws IOException {
			this.ch = FileChannel.open(file, StandardOpenOption.READ);
			this.srcIdWidth = srcIdWidth;
			this.tgtIdWidth = tgtIdWidth;
			this.pairBytes = srcIdWidth + tgtIdWidth;
			this.totalEdges = totalEdges;
			int bufSize = (64 * 1024 / pairBytes) * pairBytes;
			this.inBuf = ByteBuffer.allocate(Math.max(pairBytes, bufSize)).order(ByteOrder.LITTLE_ENDIAN);
			this.inBuf.flip();
		}

		@Override
		public boolean hasNext() {
			return readEdges < totalEdges;
		}

		@Override
		public int nextChunk(MemorySegment srcIds, MemorySegment tgtIds, int limit) {
			int count = 0;
			try {
				while (readEdges < totalEdges && count < limit) {
					if (inBuf.remaining() < pairBytes) {
						inBuf.compact();
						int n = ch.read(inBuf);
						inBuf.flip();
						if (n < 0 && inBuf.remaining() < pairBytes) {
							break;
						}
					}
					if (srcIdWidth == 2) {
						srcIds.setAtIndex(LE_SHORT, count, inBuf.getShort());
					} else if (srcIdWidth == 8) {
						srcIds.setAtIndex(LE_LONG, count, inBuf.getLong());
					} else {
						srcIds.setAtIndex(LE_INT, count, inBuf.getInt());
					}
					if (tgtIdWidth == 2) {
						tgtIds.setAtIndex(LE_SHORT, count, inBuf.getShort());
					} else if (tgtIdWidth == 8) {
						tgtIds.setAtIndex(LE_LONG, count, inBuf.getLong());
					} else {
						tgtIds.setAtIndex(LE_INT, count, inBuf.getInt());
					}
					readEdges++;
					count++;
				}
			} catch (IOException e) {
				throw new RuntimeException("Failed reading staged edge file", e);
			}
			return count;
		}

		@Override
		public void close() throws IOException {
			ch.close();
		}
	}
}
