package com.the_qa_company.qendpoint.core.util.io;

import com.the_qa_company.qendpoint.core.compact.integer.VByte;
import com.the_qa_company.qendpoint.core.util.crc.CRC;
import com.the_qa_company.qendpoint.core.util.crc.CRCInputStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * {@link CRCInputStream} that supports stop-bit (VByte) decoding.
 * <p>
 * This stream must not read ahead beyond the bytes explicitly consumed by
 * callers. Some call sites append extra bytes after the CRC and expect them to
 * remain available on the original {@link InputStream} after
 * {@link #readCRCAndCheck()}.
 * </p>
 */
public final class CRCStopBitInputStream extends CRCInputStream implements VByte.FastInput {
	private static final int MAX_SHIFT = 56; // 9 bytes * 7 bits = 63 bits

	public CRCStopBitInputStream(InputStream in, CRC crc) {
		super(in, crc);
	}

	public CRCStopBitInputStream(InputStream in, CRC crc, int bufferSize) {
		super(in, crc);
		if (bufferSize <= 0) {
			throw new IllegalArgumentException("bufferSize must be > 0");
		}
	}

	@Override
	public long readVByteLong() throws IOException {
		long value = 0L;
		int shift = 0;
		while (true) {
			int b = in.read();
			if (b < 0) {
				throw new EOFException();
			}
			crc.update((byte) b);

			value |= (long) (b & 0x7F) << shift;
			if ((b & 0x80) != 0) {
				return value;
			}

			shift += 7;
			if (shift > MAX_SHIFT) {
				throw new IllegalArgumentException("Malformed stop-bit varint: more than 9 bytes");
			}
		}
	}
}
