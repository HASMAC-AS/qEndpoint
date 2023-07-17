package com.the_qa_company.qendpoint.core.hdt.impl.converter;

import com.the_qa_company.qendpoint.core.exceptions.NotFoundException;
import com.the_qa_company.qendpoint.core.exceptions.ParserException;
import com.the_qa_company.qendpoint.core.hdt.HDT;
import com.the_qa_company.qendpoint.core.hdt.HDTManager;
import com.the_qa_company.qendpoint.core.hdt.HDTManagerTest;
import com.the_qa_company.qendpoint.core.hdt.Converter;
import com.the_qa_company.qendpoint.core.listener.ProgressListener;
import com.the_qa_company.qendpoint.core.options.HDTOptions;
import com.the_qa_company.qendpoint.core.options.HDTOptionsKeys;
import com.the_qa_company.qendpoint.core.util.LargeFakeDataSetStreamSupplier;
import com.the_qa_company.qendpoint.core.util.io.AbstractMapMemoryTest;
import org.apache.commons.io.file.PathUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;

public class ConverterTest extends AbstractMapMemoryTest {
	@Rule
	public TemporaryFolder tempDir = TemporaryFolder.builder().assureDeletion().build();

	private LargeFakeDataSetStreamSupplier stream() {
		return LargeFakeDataSetStreamSupplier.createSupplierWithMaxTriples(10_000, 75).withMaxElementSplit(50)
				.withMaxLiteralSize(20).withUnicode(true);
	}

	@Test
	public void fsdToMsdTest() throws IOException, ParserException, NotFoundException {
		Path root = tempDir.newFolder().toPath();
		try {
			Path hdtfsdPath = root.resolve("hdtfsd.hdt");
			Path hdtmsdPath = root.resolve("hdtmsd.hdt");

			stream().createAndSaveFakeHDT(HDTOptions.of(HDTOptionsKeys.DICTIONARY_TYPE_KEY,
					HDTOptionsKeys.DICTIONARY_TYPE_VALUE_MULTI_OBJECTS, HDTOptionsKeys.LOADER_TYPE_KEY,
					HDTOptionsKeys.LOADER_TYPE_VALUE_DISK, HDTOptionsKeys.LOADER_DISK_LOCATION_KEY,
					root.resolve("gen")), hdtmsdPath);

			stream().createAndSaveFakeHDT(
					HDTOptions.of(HDTOptionsKeys.DICTIONARY_TYPE_KEY, HDTOptionsKeys.DICTIONARY_TYPE_VALUE_FOUR_SECTION,
							HDTOptionsKeys.LOADER_TYPE_KEY, HDTOptionsKeys.LOADER_TYPE_VALUE_DISK,
							HDTOptionsKeys.LOADER_DISK_LOCATION_KEY, root.resolve("gen")),
					hdtfsdPath);

			try (HDT fsd = HDTManager.mapHDT(hdtfsdPath); HDT msd = HDTManager.mapHDT(hdtmsdPath)) {
				Converter converter = Converter.newConverter(msd, HDTOptionsKeys.DICTIONARY_TYPE_VALUE_FOUR_SECTION);
				Path mutPath = root.resolve("mut.hdt");
				converter.convertHDTFile(msd, mutPath, ProgressListener.ignore(), HDTOptions.of());

				try (HDT mut = HDTManager.mapHDT(mutPath)) {
					HDTManagerTest.HDTManagerTestBase.assertEqualsHDT(fsd, mut);
				}
			}
		} finally {
			PathUtils.deleteDirectory(root);
		}
	}

	@Test
	public void msdToFsdTest() throws IOException, ParserException, NotFoundException {
		Path root = tempDir.newFolder().toPath();
		try {
			Path hdtfsdPath = root.resolve("hdtfsd.hdt");
			Path hdtmsdPath = root.resolve("hdtmsd.hdt");

			stream().createAndSaveFakeHDT(HDTOptions.of(HDTOptionsKeys.DICTIONARY_TYPE_KEY,
					HDTOptionsKeys.DICTIONARY_TYPE_VALUE_MULTI_OBJECTS, HDTOptionsKeys.LOADER_TYPE_KEY,
					HDTOptionsKeys.LOADER_TYPE_VALUE_DISK, HDTOptionsKeys.LOADER_DISK_LOCATION_KEY,
					root.resolve("gen")), hdtmsdPath);

			stream().createAndSaveFakeHDT(
					HDTOptions.of(HDTOptionsKeys.DICTIONARY_TYPE_KEY, HDTOptionsKeys.DICTIONARY_TYPE_VALUE_FOUR_SECTION,
							HDTOptionsKeys.LOADER_TYPE_KEY, HDTOptionsKeys.LOADER_TYPE_VALUE_DISK,
							HDTOptionsKeys.LOADER_DISK_LOCATION_KEY, root.resolve("gen")),
					hdtfsdPath);

			try (HDT fsd = HDTManager.mapHDT(hdtfsdPath); HDT msd = HDTManager.mapHDT(hdtmsdPath)) {
				Converter converter = Converter.newConverter(fsd, HDTOptionsKeys.DICTIONARY_TYPE_VALUE_MULTI_OBJECTS);
				Path mutPath = root.resolve("mut.hdt");
				converter.convertHDTFile(fsd, mutPath, ProgressListener.ignore(), HDTOptions.of());

				try (HDT mut = HDTManager.mapHDT(mutPath)) {
					HDTManagerTest.HDTManagerTestBase.assertEqualsHDT(msd, mut);
				}
			}
		} finally {
			PathUtils.deleteDirectory(root);
		}
	}

	@Test
	public void msdlToFsdTest() throws IOException, ParserException, NotFoundException {
		Path root = tempDir.newFolder().toPath();
		try {
			Path hdtfsdPath = root.resolve("hdtfsd.hdt");
			Path hdtmsdlPath = root.resolve("hdtmsdl.hdt");

			stream().createAndSaveFakeHDT(HDTOptions.of(HDTOptionsKeys.DICTIONARY_TYPE_KEY,
					HDTOptionsKeys.DICTIONARY_TYPE_VALUE_MULTI_OBJECTS_LANG, HDTOptionsKeys.LOADER_TYPE_KEY,
					HDTOptionsKeys.LOADER_TYPE_VALUE_DISK, HDTOptionsKeys.LOADER_DISK_LOCATION_KEY,
					root.resolve("gen")), hdtmsdlPath);

			stream().createAndSaveFakeHDT(
					HDTOptions.of(HDTOptionsKeys.DICTIONARY_TYPE_KEY, HDTOptionsKeys.DICTIONARY_TYPE_VALUE_FOUR_SECTION,
							HDTOptionsKeys.LOADER_TYPE_KEY, HDTOptionsKeys.LOADER_TYPE_VALUE_DISK,
							HDTOptionsKeys.LOADER_DISK_LOCATION_KEY, root.resolve("gen")),
					hdtfsdPath);

			try (HDT fsd = HDTManager.mapHDT(hdtfsdPath); HDT msdl = HDTManager.mapHDT(hdtmsdlPath)) {
				Converter converter = Converter.newConverter(fsd,
						HDTOptionsKeys.DICTIONARY_TYPE_VALUE_MULTI_OBJECTS_LANG);
				Path mutPath = root.resolve("mut.hdt");
				converter.convertHDTFile(fsd, mutPath, ProgressListener.ignore(), HDTOptions.of());

				try (HDT mut = HDTManager.mapHDT(mutPath)) {
					HDTManagerTest.HDTManagerTestBase.assertEqualsHDT(msdl, mut);
				}
			}
		} finally {
			PathUtils.deleteDirectory(root);
		}
	}

	@Test
	public void fsdToMsdlTest() throws IOException, ParserException, NotFoundException {
		Path root = tempDir.newFolder().toPath();
		try {
			Path hdtfsdPath = root.resolve("hdtfsd.hdt");
			Path hdtmsdlPath = root.resolve("hdtmsdl.hdt");

			stream().createAndSaveFakeHDT(HDTOptions.of(HDTOptionsKeys.DICTIONARY_TYPE_KEY,
					HDTOptionsKeys.DICTIONARY_TYPE_VALUE_MULTI_OBJECTS_LANG, HDTOptionsKeys.LOADER_TYPE_KEY,
					HDTOptionsKeys.LOADER_TYPE_VALUE_DISK, HDTOptionsKeys.LOADER_DISK_LOCATION_KEY,
					root.resolve("gen")), hdtmsdlPath);

			stream().createAndSaveFakeHDT(
					HDTOptions.of(HDTOptionsKeys.DICTIONARY_TYPE_KEY, HDTOptionsKeys.DICTIONARY_TYPE_VALUE_FOUR_SECTION,
							HDTOptionsKeys.LOADER_TYPE_KEY, HDTOptionsKeys.LOADER_TYPE_VALUE_DISK,
							HDTOptionsKeys.LOADER_DISK_LOCATION_KEY, root.resolve("gen")),
					hdtfsdPath);

			try (HDT fsd = HDTManager.mapHDT(hdtfsdPath); HDT msdl = HDTManager.mapHDT(hdtmsdlPath)) {
				Converter converter = Converter.newConverter(msdl, HDTOptionsKeys.DICTIONARY_TYPE_VALUE_FOUR_SECTION);
				Path mutPath = root.resolve("mut.hdt");
				converter.convertHDTFile(msdl, mutPath, ProgressListener.ignore(), HDTOptions.of());

				try (HDT mut = HDTManager.mapHDT(mutPath)) {
					HDTManagerTest.HDTManagerTestBase.assertEqualsHDT(fsd, mut);
				}
			}
		} finally {
			PathUtils.deleteDirectory(root);
		}
	}

	@Test
	public void msdToMsdlTest() throws IOException, ParserException, NotFoundException {
		Path root = tempDir.newFolder().toPath();
		try {
			Path hdtmsdPath = root.resolve("hdtmsd.hdt");
			Path hdtmsdlPath = root.resolve("hdtmsdl.hdt");

			stream().createAndSaveFakeHDT(HDTOptions.of(HDTOptionsKeys.DICTIONARY_TYPE_KEY,
					HDTOptionsKeys.DICTIONARY_TYPE_VALUE_MULTI_OBJECTS_LANG, HDTOptionsKeys.LOADER_TYPE_KEY,
					HDTOptionsKeys.LOADER_TYPE_VALUE_DISK, HDTOptionsKeys.LOADER_DISK_LOCATION_KEY,
					root.resolve("gen")), hdtmsdlPath);

			stream().createAndSaveFakeHDT(HDTOptions.of(HDTOptionsKeys.DICTIONARY_TYPE_KEY,
					HDTOptionsKeys.DICTIONARY_TYPE_VALUE_MULTI_OBJECTS, HDTOptionsKeys.LOADER_TYPE_KEY,
					HDTOptionsKeys.LOADER_TYPE_VALUE_DISK, HDTOptionsKeys.LOADER_DISK_LOCATION_KEY,
					root.resolve("gen")), hdtmsdPath);

			try (HDT msd = HDTManager.mapHDT(hdtmsdPath); HDT msdl = HDTManager.mapHDT(hdtmsdlPath)) {
				Converter converter = Converter.newConverter(msdl, HDTOptionsKeys.DICTIONARY_TYPE_VALUE_MULTI_OBJECTS);
				Path mutPath = root.resolve("mut.hdt");
				converter.convertHDTFile(msdl, mutPath, ProgressListener.ignore(), HDTOptions.of());

				try (HDT mut = HDTManager.mapHDT(mutPath)) {
					HDTManagerTest.HDTManagerTestBase.assertEqualsHDT(msd, mut);
				}
			}
		} finally {
			PathUtils.deleteDirectory(root);
		}
	}

	@Test
	public void msdlToMsdTest() throws IOException, ParserException, NotFoundException {
		Path root = tempDir.newFolder().toPath();
		try {
			Path hdtmsdPath = root.resolve("hdtmsd.hdt");
			Path hdtmsdlPath = root.resolve("hdtmsdl.hdt");

			stream().createAndSaveFakeHDT(HDTOptions.of(HDTOptionsKeys.DICTIONARY_TYPE_KEY,
					HDTOptionsKeys.DICTIONARY_TYPE_VALUE_MULTI_OBJECTS_LANG, HDTOptionsKeys.LOADER_TYPE_KEY,
					HDTOptionsKeys.LOADER_TYPE_VALUE_DISK, HDTOptionsKeys.LOADER_DISK_LOCATION_KEY,
					root.resolve("gen")), hdtmsdlPath);

			stream().createAndSaveFakeHDT(HDTOptions.of(HDTOptionsKeys.DICTIONARY_TYPE_KEY,
					HDTOptionsKeys.DICTIONARY_TYPE_VALUE_MULTI_OBJECTS, HDTOptionsKeys.LOADER_TYPE_KEY,
					HDTOptionsKeys.LOADER_TYPE_VALUE_DISK, HDTOptionsKeys.LOADER_DISK_LOCATION_KEY,
					root.resolve("gen")), hdtmsdPath);

			try (HDT msd = HDTManager.mapHDT(hdtmsdPath); HDT msdl = HDTManager.mapHDT(hdtmsdlPath)) {
				Converter converter = Converter.newConverter(msd,
						HDTOptionsKeys.DICTIONARY_TYPE_VALUE_MULTI_OBJECTS_LANG);
				Path mutPath = root.resolve("mut.hdt");
				converter.convertHDTFile(msd, mutPath, ProgressListener.ignore(), HDTOptions.of());

				try (HDT mut = HDTManager.mapHDT(mutPath)) {
					HDTManagerTest.HDTManagerTestBase.assertEqualsHDT(msdl, mut);
				}
			}
		} finally {
			PathUtils.deleteDirectory(root);
		}
	}

}
