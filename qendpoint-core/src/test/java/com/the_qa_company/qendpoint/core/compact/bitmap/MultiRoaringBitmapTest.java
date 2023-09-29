package com.the_qa_company.qendpoint.core.compact.bitmap;

import com.the_qa_company.qendpoint.core.listener.ProgressListener;
import com.the_qa_company.qendpoint.core.util.io.AbstractMapMemoryTest;
import org.apache.commons.io.file.PathUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MultiRoaringBitmapTest extends AbstractMapMemoryTest {
	@Rule
	public TemporaryFolder tempDir = TemporaryFolder.builder().assureDeletion().build();

	int oldDefaultChunk;

	@Before
	public void saveChunkSize() {
		oldDefaultChunk = MultiRoaringBitmap.defaultChunkSize;
	}

	@After
	public void resetChunkSize() {
		MultiRoaringBitmap.defaultChunkSize = oldDefaultChunk;
	}

	@Test
	public void serialSyncTest() throws IOException {
		Path root = tempDir.newFolder().toPath();

		try {
			Path output = root.resolve("tmp.bin");
			MultiRoaringBitmap.defaultChunkSize = 9;
			try (MultiRoaringBitmap map = MultiRoaringBitmap.memoryStream(100, output)) {
				assertEquals(9, map.chunkSize);
				assertEquals(12, map.maps.size());
				map.set(0, true);
				map.set(42, true);
				map.set(80, true);
				map.set(90, true);
			}

			try (MultiRoaringBitmap map = MultiRoaringBitmap.mapped(output)) {
				for (int i = 0; i < 100; i++) {
					switch (i) {
					case 0, 42, 80, 90 -> assertTrue(map.access(i));
					default -> assertFalse(map.access(i));
					}
				}
			}

			try (BufferedInputStream stream = new BufferedInputStream(Files.newInputStream(output));
					MultiRoaringBitmap map = MultiRoaringBitmap.load(stream)) {
				for (int i = 0; i < 100; i++) {
					switch (i) {
					case 0, 42, 80, 90 -> assertTrue(map.access(i));
					default -> assertFalse(map.access(i));
					}
				}
			}

		} finally {
			PathUtils.deleteDirectory(root);
		}
	}

	@Test
	public void largeSerialSyncTest() throws IOException {
		final int seed = 684;
		final int size = 10_000;

		Random rnd = new Random(seed);
		Path root = tempDir.newFolder().toPath();

		try {
			Path output = root.resolve("tmp.bin");

			MultiRoaringBitmap.defaultChunkSize = size / 9;

			try (MultiRoaringBitmap map = MultiRoaringBitmap.memory(size)) {
				assertEquals(MultiRoaringBitmap.defaultChunkSize, map.chunkSize);
				assertEquals((size - 1) / map.chunkSize + 1, map.maps.size());

				for (int i = 0; i < size / 50; i++) {
					int position = rnd.nextInt(size);
					map.set(position, true);
				}

				try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(output))) {
					map.save(out, ProgressListener.ignore());
				}
			}

			rnd = new Random(seed);

			try (MultiRoaringBitmap map = MultiRoaringBitmap.mapped(output)) {
				for (int i = 0; i < size / 50; i++) {
					assertTrue(map.access(rnd.nextInt(size)));
				}
			}

			rnd = new Random(seed);

			try (BufferedInputStream stream = new BufferedInputStream(Files.newInputStream(output));
					MultiRoaringBitmap map = MultiRoaringBitmap.load(stream)) {
				for (int i = 0; i < size / 50; i++) {
					assertTrue(map.access(rnd.nextInt(size)));
				}
			}

		} finally {
			PathUtils.deleteDirectory(root);
		}
	}

	@Test
	public void rankSelectTest() throws IOException {
		final int seed = 684;
		final int size = 10_000;

		Random rnd = new Random(seed);
		MultiRoaringBitmap.defaultChunkSize = size / 9;

		try (MultiRoaringBitmap map = MultiRoaringBitmap.memory(size);
				Bitmap375Big memmap = Bitmap375Big.memory(size)) {
			assertEquals(MultiRoaringBitmap.defaultChunkSize, map.chunkSize);
			assertEquals((size - 1) / map.chunkSize + 1, map.maps.size());

			for (int i = 0; i < size / 50; i++) {
				int position = rnd.nextInt(size);
				map.set(position, true);
				memmap.set(position, true);
			}

			memmap.updateIndex();

			long numBits = memmap.countOnes();

			assertEquals("countOnes", numBits, map.countOnes());

			for (int i = 0; i < size; i++) {
				assertEquals("access#" + i + "/" + size, memmap.access(i), map.access(i));
			}

			for (int i = 0; i < size; i++) {
				assertEquals("rank1#" + i + "/" + size, memmap.rank1(i), map.rank1(i));
			}
			for (int i = 0; i < size; i++) {
				assertEquals("rank0#" + i + "/" + size, memmap.rank0(i), map.rank0(i));
			}
			for (int i = 0; i < numBits; i++) {
				long n = i;
				long j = -1;
				while (n > 0) {
					if (memmap.access(++j)) {
						n--;
					}
				}
				assertEquals(j, memmap.select1(i));
				assertEquals("select1#" + i + "/" + numBits, memmap.select1(i), map.select1(i));
			}

			for (int i = 0; i < numBits; i++) {
				assertEquals("selectNext1#" + i + "/" + numBits, memmap.selectNext1(i), map.selectNext1(i));
			}

			for (int i = 0; i < numBits; i++) {
				assertEquals("selectPrev1#" + i + "/" + numBits, memmap.selectPrev1(i), map.selectPrev1(i));
			}
		}
	}
}