package com.the_qa_company.qendpoint.core.hdt.impl.diskimport;

import com.the_qa_company.qendpoint.core.dictionary.impl.CompressFourSectionDictionary;
import com.the_qa_company.qendpoint.core.util.io.CloseSuppressPath;
import com.the_qa_company.qendpoint.core.util.io.compress.CompressUtil;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

public class BucketedTripleMapperMaterializationTest {
	@Rule
	public TemporaryFolder tempDir = TemporaryFolder.builder().assureDeletion().build();

	@Test
	public void materializesMappingsIntoCompressTripleMapper() throws Exception {
		Class<?> bucketedMapperClass = Class
				.forName("com.the_qa_company.qendpoint.core.hdt.impl.diskimport.BucketedTripleMapper");

		try (CloseSuppressPath root = CloseSuppressPath.of(tempDir.newFolder().toPath())) {
			root.closeWithDeleteRecurse();

			long tripleCount = 4;
			try (CloseSuppressPath bucketDir = root.resolve("bucketed")) {
				bucketDir.mkdirs();

				Object bucketed = bucketedMapperClass
						.getConstructor(CloseSuppressPath.class, long.class, boolean.class, int.class, int.class)
						.newInstance(bucketDir, tripleCount, false, 2, 16);
				CompressFourSectionDictionary.NodeConsumer consumer = (CompressFourSectionDictionary.NodeConsumer) bucketed;

				for (long tripleId = 1; tripleId <= tripleCount; tripleId++) {
					long headerId = CompressUtil.getHeaderId(tripleId);
					consumer.onSubject(tripleId, headerId);
					consumer.onPredicate(tripleId, headerId);
					consumer.onObject(tripleId, headerId);
				}

				try (CloseSuppressPath mapperDir = root.resolve("mapper")) {
					mapperDir.mkdirs();

					CompressTripleMapper mapper = new CompressTripleMapper(mapperDir, tripleCount, 1024, false, 0);
					Method materializeTo = bucketedMapperClass.getMethod("materializeTo", CompressTripleMapper.class);
					materializeTo.invoke(bucketed, mapper);
					mapper.setShared(0);

					for (long tripleId = 1; tripleId <= tripleCount; tripleId++) {
						assertEquals("subject mapping", tripleId, mapper.extractSubject(tripleId));
						assertEquals("predicate mapping", tripleId, mapper.extractPredicate(tripleId));
						assertEquals("object mapping", tripleId, mapper.extractObjects(tripleId));
					}

					mapper.delete();
				}

				Method close = bucketedMapperClass.getMethod("close");
				close.invoke(bucketed);
			}
		}
	}
}
