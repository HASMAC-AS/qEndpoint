package com.the_qa_company.qendpoint.core.hdt.impl.diskimport;

import com.the_qa_company.qendpoint.core.dictionary.impl.CompressFourSectionDictionary;
import com.the_qa_company.qendpoint.core.util.io.CloseSuppressPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Bucketed mapping sink for disk imports.
 * <p>
 * Instead of writing tripleId-indexed mapping arrays with random writes during
 * dictionary construction, this implementation appends (offsetInBucket,
 * headerId) records into per-bucket files. After dictionary construction,
 * {@link #materializeTo(CompressTripleMapper)} can be used to populate the
 * standard {@link CompressTripleMapper} mapping files sequentially.
 */
public class BucketedTripleMapper implements CompressFourSectionDictionary.NodeConsumer, Closeable {
	private static final Logger log = LoggerFactory.getLogger(BucketedTripleMapper.class);
	private static final int RECORD_BYTES = Integer.BYTES + Long.BYTES;

	private final RoleSpooler subjects;
	private final RoleSpooler predicates;
	private final RoleSpooler objects;
	private final RoleSpooler graphs;
	private final boolean supportsGraph;

	public BucketedTripleMapper(CloseSuppressPath location, long tripleCount, boolean supportsGraph, int bucketSize,
			int bufferSize) throws IOException {
		this.supportsGraph = supportsGraph;
		location.mkdirs();
		subjects = new RoleSpooler(location.resolve("subjects"), tripleCount, bucketSize, bufferSize);
		predicates = new RoleSpooler(location.resolve("predicates"), tripleCount, bucketSize, bufferSize);
		objects = new RoleSpooler(location.resolve("objects"), tripleCount, bucketSize, bufferSize);
		if (supportsGraph) {
			graphs = new RoleSpooler(location.resolve("graphs"), tripleCount, bucketSize, bufferSize);
		} else {
			graphs = null;
		}
	}

	@Override
	public void onSubject(long preMapId, long newMapId) {
		subjects.add(preMapId, newMapId);
	}

	@Override
	public void onSubject(long[] preMapIds, long[] newMapIds, int offset, int length) {
		subjects.add(preMapIds, newMapIds, offset, length);
	}

	@Override
	public void onPredicate(long preMapId, long newMapId) {
		predicates.add(preMapId, newMapId);
	}

	@Override
	public void onPredicate(long[] preMapIds, long[] newMapIds, int offset, int length) {
		predicates.add(preMapIds, newMapIds, offset, length);
	}

	@Override
	public void onObject(long preMapId, long newMapId) {
		objects.add(preMapId, newMapId);
	}

	@Override
	public void onObject(long[] preMapIds, long[] newMapIds, int offset, int length) {
		objects.add(preMapIds, newMapIds, offset, length);
	}

	@Override
	public void onGraph(long preMapId, long newMapId) {
		if (!supportsGraph) {
			return;
		}
		graphs.add(preMapId, newMapId);
	}

	@Override
	public void onGraph(long[] preMapIds, long[] newMapIds, int offset, int length) {
		if (!supportsGraph) {
			return;
		}
		graphs.add(preMapIds, newMapIds, offset, length);
	}

	public void materializeTo(CompressTripleMapper mapper) throws IOException {
		flush();
		subjects.materialize(Role.SUBJECT, mapper);
		predicates.materialize(Role.PREDICATE, mapper);
		objects.materialize(Role.OBJECT, mapper);
		if (supportsGraph) {
			graphs.materialize(Role.GRAPH, mapper);
		}
	}

	private void flush() throws IOException {
		subjects.flush();
		predicates.flush();
		objects.flush();
		if (supportsGraph) {
			graphs.flush();
		}
	}

	@Override
	public void close() throws IOException {
		flush();
		subjects.close();
		predicates.close();
		objects.close();
		if (supportsGraph) {
			graphs.close();
		}
	}

	private enum Role {
		SUBJECT, PREDICATE, OBJECT, GRAPH
	}

	private static final class RoleSpooler implements Closeable {
		private final CloseSuppressPath root;
		private final long tripleCount;
		private final int bucketSize;
		private final int bucketCount;

		private final int[] bucketBuffer;
		private final int[] offsetBuffer;
		private final long[] headerBuffer;
		private int size;

		private final int[] bucketCounts;
		private final int[] bucketOffsets;
		private final int[] bucketWriteCursor;
		private final int[] sortedOffsets;
		private final long[] sortedHeaders;
		private final ByteBuffer ioBuffer;

		private RoleSpooler(CloseSuppressPath root, long tripleCount, int bucketSize, int bufferSize)
				throws IOException {
			if (tripleCount < 0) {
				throw new IllegalArgumentException("Negative tripleCount: " + tripleCount);
			}
			if (bucketSize <= 0) {
				throw new IllegalArgumentException("bucketSize must be > 0");
			}
			if (bufferSize <= 0) {
				throw new IllegalArgumentException("bufferSize must be > 0");
			}
			this.root = root;
			this.tripleCount = tripleCount;
			this.bucketSize = bucketSize;
			this.bucketCount = (int) Math.max(1, (tripleCount + bucketSize - 1) / bucketSize);

			root.mkdirs();

			bucketBuffer = new int[bufferSize];
			offsetBuffer = new int[bufferSize];
			headerBuffer = new long[bufferSize];

			bucketCounts = new int[bucketCount];
			bucketOffsets = new int[bucketCount + 1];
			bucketWriteCursor = new int[bucketCount];
			sortedOffsets = new int[bufferSize];
			sortedHeaders = new long[bufferSize];

			ioBuffer = ByteBuffer.allocateDirect(1024 * 1024);
		}

		private void add(long tripleId, long headerId) {
			if (tripleId <= 0) {
				throw new IllegalArgumentException("tripleId must be > 0");
			}
			long value = tripleId - 1;
			int bucket = (int) (value / bucketSize);
			int offset = (int) (value - (long) bucket * bucketSize);
			bucketBuffer[size] = bucket;
			offsetBuffer[size] = offset;
			headerBuffer[size] = headerId;
			size++;
			if (size == bucketBuffer.length) {
				try {
					flush();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}

		private void add(long[] tripleIds, long[] headerIds, int offset, int length) {
			for (int i = offset; i < offset + length; i++) {
				add(tripleIds[i], headerIds[i]);
			}
		}

		private void flush() throws IOException {
			if (size == 0) {
				return;
			}

			Arrays.fill(bucketCounts, 0);
			for (int i = 0; i < size; i++) {
				bucketCounts[bucketBuffer[i]]++;
			}

			bucketOffsets[0] = 0;
			for (int b = 0; b < bucketCount; b++) {
				bucketOffsets[b + 1] = bucketOffsets[b] + bucketCounts[b];
			}
			System.arraycopy(bucketOffsets, 0, bucketWriteCursor, 0, bucketCount);

			for (int i = 0; i < size; i++) {
				int bucket = bucketBuffer[i];
				int outIndex = bucketWriteCursor[bucket]++;
				sortedOffsets[outIndex] = offsetBuffer[i];
				sortedHeaders[outIndex] = headerBuffer[i];
			}

			for (int bucket = 0; bucket < bucketCount; bucket++) {
				int start = bucketOffsets[bucket];
				int end = bucketOffsets[bucket + 1];
				if (start == end) {
					continue;
				}
				CloseSuppressPath file = root.resolve(bucketFileName(bucket));
				try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
						StandardOpenOption.APPEND)) {
					writeRecords(channel, sortedOffsets, sortedHeaders, start, end);
				}
			}

			size = 0;
		}

		private void materialize(Role role, CompressTripleMapper mapper) throws IOException {
			for (int bucket = 0; bucket < bucketCount; bucket++) {
				long bucketStart = (long) bucket * bucketSize + 1;
				long remaining = tripleCount - (bucketStart - 1);
				if (remaining <= 0) {
					return;
				}
				int count = (int) Math.min(bucketSize, remaining);

				long[] headers = new long[count];
				CloseSuppressPath file = root.resolve(bucketFileName(bucket));
				if (!Files.exists(file)) {
					throw new IllegalStateException("Missing bucket file: " + file);
				}
				try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
					readRecords(channel, headers);
				}

				for (int i = 0; i < count; i++) {
					long tripleId = bucketStart + i;
					long headerId = headers[i];
					if (headerId == 0) {
						throw new IllegalStateException("Missing mapping for tripleId=" + tripleId + " (" + role + ")");
					}
					switch (role) {
					case SUBJECT -> mapper.onSubject(tripleId, headerId);
					case PREDICATE -> mapper.onPredicate(tripleId, headerId);
					case OBJECT -> mapper.onObject(tripleId, headerId);
					case GRAPH -> mapper.onGraph(tripleId, headerId);
					default -> throw new IllegalStateException("Unknown role: " + role);
					}
				}
			}
		}

		private void writeRecords(FileChannel channel, int[] offsets, long[] headers, int from, int to)
				throws IOException {
			ioBuffer.clear();
			for (int i = from; i < to; i++) {
				if (ioBuffer.remaining() < RECORD_BYTES) {
					ioBuffer.flip();
					while (ioBuffer.hasRemaining()) {
						channel.write(ioBuffer);
					}
					ioBuffer.clear();
				}
				ioBuffer.putInt(offsets[i]);
				ioBuffer.putLong(headers[i]);
			}
			ioBuffer.flip();
			while (ioBuffer.hasRemaining()) {
				channel.write(ioBuffer);
			}
		}

		private void readRecords(FileChannel channel, long[] headersByOffset) throws IOException {
			ByteBuffer buffer = ioBuffer;
			buffer.clear();
			int read;
			while ((read = channel.read(buffer)) >= 0) {
				if (read == 0) {
					break;
				}
				buffer.flip();
				while (buffer.remaining() >= RECORD_BYTES) {
					int offset = buffer.getInt();
					long header = buffer.getLong();
					if (offset < 0 || offset >= headersByOffset.length) {
						throw new IOException("Offset out of range: " + offset + " >= " + headersByOffset.length);
					}
					headersByOffset[offset] = header;
				}
				buffer.compact();
			}
			buffer.flip();
			if (buffer.remaining() != 0) {
				throw new EOFException("Unexpected trailing bytes in bucket file: " + buffer.remaining());
			}
		}

		private static String bucketFileName(int bucket) {
			return String.format("b%06d.bin", bucket);
		}

		@Override
		public void close() throws IOException {
			root.closeWithDeleteRecurse();
			try {
				root.close();
			} catch (IOException e) {
				log.warn("Can't delete bucketed mapping directory {}", root, e);
			}
		}
	}

}
