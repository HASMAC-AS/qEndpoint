package com.the_qa_company.qendpoint.core.iterator.utils;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class PriorityQueueMergeExceptionIteratorTest {

	@Test
	public void mergeTest() throws Exception {
		ExceptionIterator<String, RuntimeException> it1 = ExceptionIterator
				.of(Arrays.asList("1", "3", "5", "7").iterator());
		ExceptionIterator<String, RuntimeException> it2 = ExceptionIterator
				.of(Arrays.asList("2", "4", "6", "6").iterator());

		ExceptionIterator<String, RuntimeException> it = PriorityQueueMergeExceptionIterator.merge(List.of(it1, it2),
				String::compareTo);

		ExceptionIterator<String, RuntimeException> itExpected = ExceptionIterator
				.of(Arrays.asList("1", "2", "3", "4", "5", "6", "6", "7").iterator());

		while (itExpected.hasNext()) {
			assertTrue(it.hasNext());
			assertEquals(itExpected.next(), it.next());
		}
		assertFalse(it.hasNext());
	}

	@Test
	public void doesNotAdvanceBeforeReturningElement() throws Exception {
		ExceptionIterator<MutableInt, RuntimeException> it1 = new ReusingMutableIntIterator(1, 3, 5);
		ExceptionIterator<MutableInt, RuntimeException> it2 = new ReusingMutableIntIterator(2, 4, 6);

		ExceptionIterator<MutableInt, RuntimeException> merged = PriorityQueueMergeExceptionIterator
				.merge(List.of(it1, it2), (a, b) -> Integer.compare(a.value, b.value));

		List<Integer> values = new ArrayList<>();
		while (merged.hasNext()) {
			MutableInt v = merged.next();
			values.add(v.value);
		}

		assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6), values);
	}

	private static final class MutableInt {
		int value;
	}

	private static final class ReusingMutableIntIterator implements ExceptionIterator<MutableInt, RuntimeException> {
		private final int[] values;
		private int index;
		private final MutableInt mutable = new MutableInt();

		ReusingMutableIntIterator(int... values) {
			this.values = values;
		}

		@Override
		public boolean hasNext() {
			return index < values.length;
		}

		@Override
		public MutableInt next() {
			mutable.value = values[index++];
			return mutable;
		}
	}
}
