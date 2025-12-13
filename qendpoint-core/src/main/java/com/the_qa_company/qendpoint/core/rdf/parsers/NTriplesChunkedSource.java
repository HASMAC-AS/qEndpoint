package com.the_qa_company.qendpoint.core.rdf.parsers;

import com.the_qa_company.qendpoint.core.enums.RDFNotation;
import com.the_qa_company.qendpoint.core.iterator.utils.SizedSupplier;
import com.the_qa_company.qendpoint.core.quad.QuadString;
import com.the_qa_company.qendpoint.core.triples.TripleString;
import com.the_qa_company.qendpoint.core.util.concurrent.ExceptionSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Chunk factory for N-Triples / N-Quads. Pull-based end-to-end: - KWay worker
 * asks for a chunk supplier (this.get()). - That chunk supplier pulls batches
 * of lines from a shared reader (under a small lock), parses them, and yields
 * {@link TripleString}/{@link QuadString} via {@link SizedSupplier#get()}.
 */
public final class NTriplesChunkedSource
		implements ExceptionSupplier<SizedSupplier<TripleString>, IOException>, Closeable {

	private static final Logger log = LoggerFactory.getLogger(NTriplesChunkedSource.class);

	private final BufferedReader reader;
	private final Object lock = new Object();

	private final boolean readQuad;
	private final long chunkBudgetBytes;

	// limits how much we buffer at once (prevents "double buffering" the whole
	// chunk in RAM)
	private final long maxBatchBytes;
	private final int maxBatchLines;

	private volatile boolean eof;

	public NTriplesChunkedSource(InputStream input, RDFNotation notation, long chunkBudgetBytes) {
		this(input, notation, chunkBudgetBytes, 8L * 1024 * 1024, 8192);
	}

	public NTriplesChunkedSource(InputStream input, RDFNotation notation, long chunkBudgetBytes, long maxBatchBytes,
			int maxBatchLines) {
		Objects.requireNonNull(input, "input");
		Objects.requireNonNull(notation, "notation");

		if (notation != RDFNotation.NTRIPLES && notation != RDFNotation.NQUAD) {
			throw new IllegalArgumentException("NTriplesChunkedSource supports only NTRIPLES/NQUAD, got " + notation);
		}
		if (chunkBudgetBytes <= 0) {
			throw new IllegalArgumentException("chunkBudgetBytes must be > 0");
		}
		if (maxBatchBytes <= 0) {
			throw new IllegalArgumentException("maxBatchBytes must be > 0");
		}
		if (maxBatchLines <= 0) {
			throw new IllegalArgumentException("maxBatchLines must be > 0");
		}

		this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
		this.readQuad = notation == RDFNotation.NQUAD;
		this.chunkBudgetBytes = chunkBudgetBytes;
		this.maxBatchBytes = maxBatchBytes;
		this.maxBatchLines = maxBatchLines;
	}

	/**
	 * @return next chunk supplier, or null when input is exhausted.
	 */
	@Override
	public SizedSupplier<TripleString> get() throws IOException {
		// Prefetch a first non-null line so we don't create empty chunks at
		// EOF.
		final String firstLine;
		synchronized (lock) {
			if (eof) {
				return null;
			}

			String line;
			do {
				line = reader.readLine();
				if (line == null) {
					eof = true;
					return null;
				}
			} while (line.isEmpty());

			firstLine = line;
		}

		return new Chunk(firstLine);
	}

	@Override
	public void close() throws IOException {
		synchronized (lock) {
			eof = true;
		}
		reader.close();
	}

	private final class Chunk implements SizedSupplier<TripleString> {
		private final ArrayList<String> lineBuffer = new ArrayList<>(1024);
		private int idx;

		private long remaining = chunkBudgetBytes;
		private long size;
		private boolean done;

		private final TripleString reusable = readQuad ? new QuadString() : new TripleString();

		private Chunk(String firstLine) {
			lineBuffer.add(firstLine);
			long est = estimateLineSize(firstLine);
			size += est;
			remaining -= est;
		}

		@Override
		public TripleString get() {
			if (done) {
				return null;
			}

			try {
				while (true) {
					// Need more lines?
					if (idx >= lineBuffer.size()) {
						lineBuffer.clear();
						idx = 0;

						// stop condition for this chunk
						if (remaining <= 0) {
							done = true;
							return null;
						}

						fillBuffer();
						if (lineBuffer.isEmpty()) {
							done = true;
							return null;
						}
					}

					String line = lineBuffer.get(idx++);
					if (parseLine(line, reusable, readQuad)) {
						return reusable;
					}
					// skip comments/blank/invalid lines, keep scanning
				}
			} catch (IOException ioe) {
				throw new RuntimeException(ioe);
			}
		}

		@Override
		public long getSize() {
			return size;
		}

		private void fillBuffer() throws IOException {
			// cap in-memory buffering
			long batchBudget = Math.min(remaining, maxBatchBytes);

			int count = 0;
			synchronized (lock) {
				while (!eof && count < maxBatchLines && batchBudget > 0) {
					String line = reader.readLine();
					if (line == null) {
						eof = true;
						break;
					}
					if (line.isEmpty()) {
						continue;
					}

					lineBuffer.add(line);

					long est = estimateLineSize(line);
					size += est;
					remaining -= est;
					batchBudget -= est;

					count++;
				}
			}
		}
	}

	private static long estimateLineSize(String line) {
		// Simple but stable: approximates bytes consumed in the NT stream.
		// (N-Triples is overwhelmingly ASCII; for stats/chunking, char-length
		// is fine.)
		return (long) line.length() + 1L; // + '\n'
	}

	/**
	 * Mirrors {@link RDFParserSimple}'s whitespace trimming and comment
	 * skipping.
	 */
	private static boolean parseLine(String line, TripleString out, boolean readQuad) {
		int start = 0;
		while (start < line.length()) {
			char c = line.charAt(start);
			if (c != ' ' && c != '\t') {
				break;
			}
			start++;
		}

		int end = line.length() - 1;
		while (end >= 0) {
			char c = line.charAt(end);
			if (c != ' ' && c != '\t') {
				break;
			}
			end--;
		}

		if (start + 1 >= end) {
			return false;
		}
		if (line.charAt(start) == '#') {
			return false;
		}

		try {
			out.read(line, start, end, readQuad);
			if (!out.hasEmpty()) {
				return true;
			}

			log.warn("Could not parse triple, ignored.\n{}", line);
			return false;
		} catch (Exception e) {
			log.warn("Could not parse triple, ignored.\n{}", line);
			return false;
		}
	}
}
