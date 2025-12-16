# CompressFourSectionDictionary, LongArrayDisk, and Why HDT Disk Imports Slow Down at Scale

This report explains:

1. **What `CompressFourSectionDictionary` (CFSD) does and how it works.**
2. **How the overall disk import pipeline works (dictionary + triples).**
3. **Why CFSD “interacts” with `LongArrayDisk` (and what that interaction really is).**
4. **Why performance often collapses (feels “exponential”) as RDF datasets grow.**
5. **Concrete redesign directions to make the architecture more scalable.**

The focus is the disk loader path used by `HDTDiskImporter` and friends in `qendpoint-core`.

---

## 0) Executive summary (what’s actually going wrong)

`CompressFourSectionDictionary` itself is essentially a **streaming merge** over sorted subject/object term streams to compute the shared (S∩O) section and emit four dictionary sections (S, P, O, SH [+ G]).

The “exponential slowdown” you observe in large RDF→HDT runs is typically **not** because CFSD’s merge algorithm is super‑linear. The collapse comes from the **architecture choice** to build a **triple‑id → dictionary‑id mapping table** while reading terms in **lexicographic order**:

- **Triple IDs** are assigned in **input order** (1..N).
- Terms are processed in **sorted term order** (lexicographic).
- Therefore, the mapping writes land at **effectively random triple positions** across a gigantic array.

In the disk loader, that mapping table is stored in `SequenceLog64BigDisk` → `LongArrayDisk` (memory‑mapped files). Random updates over a huge memory‑mapped file:

- trigger large numbers of **page faults**,
- defeat locality/caching,
- amplify disk I/O (and writeback),
- and once the working set exceeds RAM, throughput can collapse very abruptly (human‑perceived “exponential” slowdown).

Additionally, the current implementation eagerly **zero‑fills** (`clear()`) very large `LongArrayDisk` files on creation (and sometimes overshoots mapping sizes), adding a large extra write pass that scales with dataset size.

The core scalable redesign is: **stop doing random writes into giant memory‑mapped arrays keyed by triple ID while iterating in term order**.

Important nuance: the mapping layer does sort each bulk batch by tripleId (see `SequenceLog64BigDisk#set(long[], long[], int, int)`) and then writes packed 64‑bit words in ascending order, which improves locality within a batch. But batches still span the full [1..N] tripleId range, so once the mapping files exceed RAM, each batch still touches huge numbers of pages and page-cache thrash dominates.

---

## 1) Glossary / mental model

### 1.1 Terms used across the code

- **Triple ID**: an integer assigned to each parsed triple, increasing in the order triples are read from the input.
  - In `SectionCompressor.createChunk(...)`, it is `long tripleID = triples.incrementAndGet();`
  - That `tripleID` becomes the `IndexedNode.index` for S/P/O/(G) occurrences.

- **IndexedNode**: `(nodeBytes, index)` pair.
  - `node` is a `ByteString` (often a mutable `ReplazableString` reused by readers).
  - `index` is the triple ID of the occurrence.
  - File: `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/triples/IndexedNode.java`

- **Dictionary sections**:
  - **shared**: terms that occur in both subject and object positions (S∩O), IDs **1..Nshared**.
  - **subjects**: subject‑only terms (S\O), IDs **Nshared+1..Nshared+NsubjectsOnly**.
  - **objects**: object‑only terms (O\S), IDs **Nshared+1..Nshared+NobjectsOnly**.
  - **predicates**: separate section (no shared offset in saved dictionary layout).
  - **graphs** (optional): separate section for quads.

- **Header ID encoding** (critical):
  - Implemented in `CompressUtil` (`qendpoint-core/.../CompressUtil.java`).
  - Low bit is a **shared flag**:
    - `SHARED_MASK = 1`
    - `INDEX_SHIFT = 1`
  - `getHeaderId(id) = id << 1` (shared flag = 0)
  - `asShared(id) = (id << 1) | 1` (shared flag = 1)
  - `getId(header) = header >>> 1` (strip flag)
  - Later, `computeSharedNode(header, sharedCount)` converts “shared‑computable” IDs into final dictionary IDs:
    - if shared flag = 1 → `getId(header)` (already in shared section range)
    - else → `getId(header) + sharedCount` (offset into S/O sections)

This “header ID” trick exists because **sharedCount is not known** until dictionary construction completes, but we want to emit mapping values early.

---

## 2) Where CFSD sits in the disk import pipeline

The disk generation pipeline (simplified call graph):

```
HDTDiskImporter.compressDictionary(...)
  -> SectionCompressor.compressPull(...)
       - reads triples once
       - writes sorted per-role compressed section files (S/P/O[/G])
       - returns CompressionResult (iterators over sorted IndexedNode streams)
  -> new CompressTripleMapper(...)   // mapping sink
  -> new CompressFourSectionDictionary(compressionResult, mapper, ...)
  -> dictionary.loadAsync(tempDictionary, ...)
       - consumes dictionary sections from tempDictionary
       - writes dictionary (memory or disk, depending on impl)
  -> mapper.setShared(dictionary.getNshared())
  -> compressionResult.delete()      // delete temporary section files

HDTDiskImporter.compressTriples(mapper)
  -> TripleGenerator.of(tripleCount)               // triples as IDs 1..N
  -> MapCompressTripleMerger.mergePull(...)
       - maps each tripleID via mapper arrays (subject/predicate/object[/graph])
       - sorts triples by requested order and writes compressed triple file
  -> triples.load(tempTriples, ...)
```

Key files/classes:

- `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/hdt/impl/HDTDiskImporter.java`
- `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/hdt/impl/diskimport/SectionCompressor.java`
- `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/dictionary/impl/CompressFourSectionDictionary.java`
- `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/hdt/impl/diskimport/CompressTripleMapper.java`
- `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/util/disk/LongArrayDisk.java`
- `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/compact/sequence/SequenceLog64BigDisk.java`

The important conceptual choice is in `compressTriples(...)`:

- it does **not** re-read the input RDF again,
- it instead recreates triples by iterating triple IDs 1..N and looking up per-role mappings created during dictionary building.

That architecture avoids re-parsing the input but makes the **mapping table** a central performance and scalability constraint.

### 2.1 Async dictionary loading (important concurrency detail)

`HDTDiskImporter.compressDictionary(...)` calls `dictionary.loadAsync(tempDictionary, ...)`.

For disk-backed dictionaries (e.g. `WriteFourSectionDictionary`), `loadAsync` loads multiple sections **concurrently** (separate threads), for example:

- `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/dictionary/impl/WriteFourSectionDictionary.java`

This matters for performance because, during dictionary build, you end up doing multiple disk-heavy activities at once:

- CFSD pipe-builder thread reading sorted S/O section files and emitting mapping updates,
- predicate (and graph) loader threads iterating their section streams and emitting mapping updates,
- section writer threads (`WriteDictionarySection.load(...)`) writing dictionary text and block pointers.

Even though the mapping arrays are per-role (subjects/predicates/objects[/graph]) and therefore not contended at the Java object level, they are separate large memory-mapped files on the same storage device. Concurrent random writes across multiple large mmaps tends to worsen OS cache thrash and writeback pressure.

Short-term mitigation idea (if you can accept less parallelism): reduce dictionary section ingestion concurrency so fewer big writers are active simultaneously. Long-term, the scalable fix is still to avoid random-write mapping structures.

### 2.2 Temporary files and disk footprint (what the disk loader creates)

Understanding what files exist (and how big they get) is important when diagnosing scaling behavior and when designing a more scalable pipeline.

At a high level, `HDTDiskImporter` uses a working directory (`basePath`) that contains:

- `sectionCompression/`  
  Output of `SectionCompressor`: sorted compressed section runs (S/P/O[/G]) and their merged results. These files are “one entry per triple occurrence per role”, so their size is typically proportional to the raw RDF size.

- `map_subjects`, `map_predicates`, `map_objects`, `map_graph` (optional)  
  Output of `CompressTripleMapper`: disk-backed packed mapping arrays (`SequenceLog64BigDisk` → `LongArrayDisk`). These are the large memory-mapped files that receive random writes during dictionary construction.

- `tripleMapper/`  
  Output of `MapCompressTripleMerger`: mapped triple chunks, then merged triple files (complete mode) or a set of chunk files (partial mode).

If you are generating a mapped HDT (`loader.disk.futureHDTLocation` set), `WriteHDTImpl` writes components under:

- `maphdt/section*` (dictionary sections) and their `*_temp` / `*_tempblock` files during load
  - created by `WriteFourSectionDictionary` and `WriteDictionarySection`.
- `maphdt/tripleBitmap*` (triples bitmap structures), created by `WriteBitmapTriples`.

Why this matters:

- Peak disk usage during a run can be much larger than the final HDT size (multiple temporary representations exist simultaneously).
- If the mapping arrays are very large, random-write behavior can dominate overall runtime even if the external sort stages are “working as designed”.

---

## 3) How the `SectionCompressor` produces CFSD’s input

### 3.1 Chunk creation (`SectionCompressor.createChunk`)

In `SectionCompressor.createChunk(...)`:

- Triples are read sequentially from the input stream (or chunk supplier).
- Each triple is assigned a monotonically increasing **global** `tripleID`.
- For each triple:
  - an `IndexedNode(subjectString, tripleID)` is appended to the subject list,
  - similarly for predicate, object, (graph).
- At the end of the chunk:
  - each list is **sorted by node lexicographically**,
  - `CompressUtil.writeCompressedSection(...)` writes them to disk.

Important consequence:

- In the compressed section files, **nodes are ordered by node string**,
- but their `index` values (triple IDs) are now **in arbitrary order** relative to the file stream.

This is the root cause of “random triple-id order” when later reconstructing mappings.

### 3.2 Merge to final section files (complete mode)

In complete mode (default `loader.disk.compressMode = compressionComplete`):

- chunk files are merged by `KWayMerger/KWayMergerChunked`
- `SectionCompressor.TripleFile.computeSection(...)` does a k‑way merge of sorted streams and writes one merged file per section.

This produces one large sorted stream per section.

### 3.3 How `CompressNodeReader` behaves (mutable node objects)

`CompressNodeReader` (`qendpoint-core/.../CompressNodeReader.java`) reads a compressed section file and yields `IndexedNode` values.

Crucially:

- it reuses a single mutable `ReplazableString` buffer for decoding strings,
- and reuses a single `IndexedNode last` object with `last.setIndex(index)`.

Therefore, **callers must copy/freeze the string** if they need to hold onto it beyond the next iterator step.

CFSD does this via `new CompactString(node.getNode())` when sending nodes through pipes.

### 3.4 Compressed section file format (why sorting is feasible on disk)

The temporary section files produced by `SectionCompressor` are written by `CompressNodeWriter` and read by `CompressNodeReader`:

- Writer: `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/util/io/compress/CompressNodeWriter.java`
- Reader: `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/util/io/compress/CompressNodeReader.java`

Each section file is effectively a **PFC-like (plain front coding) stream**:

1. Header: a VByte-encoded `size` (number of entries) with a CRC8 check.
2. Body: for each entry:
   - `delta` = longest common prefix length vs previous string (VByte),
   - suffix bytes (raw), terminated by a `0` byte,
   - `index` = the tripleID occurrence for this entry (VByte).
3. Footer: CRC32 over the body.

Why this matters: the section sort/merge pipeline is moving around *compressed runs* of `(term, tripleId)` pairs that are delta-coded by term order. That makes external sorting viable (I/O smaller than raw strings), but also explains why later stages see triple IDs in “random” order once the stream is sorted by term.

---

## 4) What `CompressFourSectionDictionary` does in detail

File: `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/dictionary/impl/CompressFourSectionDictionary.java`

### 4.1 Inputs and outputs

Inputs:

- `CompressionResult` supplying sorted iterators for:
  - subjects: `ExceptionIterator<IndexedNode>`
  - predicates
  - objects
  - graphs (optional)
- `NodeConsumer` callback sink for **tripleID→dictionaryID mapping**.
- `ProgressListener`, `debugOrder`, `quad`.

Outputs:

- Implements `TempDictionary`.
- Exposes `TempDictionarySection`s for:
  - subjects, predicates, objects, shared, graphs (optional).
- Each section is backed by a one‑shot iterator (`OneReadDictionarySection`).

### 4.2 Two key operations happen concurrently

CFSD interleaves:

1. **Producing dictionary section entries** (strings) for consumption by `DictionaryPrivate.loadAsync(...)`.
2. **Producing mapping updates** (tripleID → “headerId”) for later triple mapping.

It uses different strategies per section:

- **Subjects/Objects/Shared**: produced by a dedicated “pipe builder” thread into `PipedCopyIterator`s.
- **Predicates/Graphs**: produced directly by iterating their streams during section consumption (no pipe thread), with a wrapper that also emits mapping updates.

### 4.3 Deduplication is performed lazily via `CompressUtil.DuplicatedIterator`

CFSD wraps each sorted section iterator with:

```java
CompressUtil.asNoDupeCharSequenceIterator(iterator, duplicatedCallback)
```

This creates `DuplicatedIterator`, which:

- keeps the previous node value (`ReplazableString prev`)
- compares each incoming node to `prev`
- if equal → it is a duplicate occurrence; it calls `duplicatedCallback(originalIndex, duplicateIndex, lastHeader)`
- if different → it exposes the new node as the “next unique node”

The trick: `lastHeader` is set externally by CFSD at the moment the first occurrence is assigned a header ID. Later duplicates inherit that header.

### 4.4 How shared is computed (S∩O merge)

The pipe builder thread reads **unique** subject nodes and **unique** object nodes (both deduped by `DuplicatedIterator`) and merges them like a standard merge join:

Pseudocode equivalent:

```
subjectId = 1
objectId  = 1
sharedId  = 1

while subjectUnique.hasNext && objectUnique.hasNext:
  s = next subjectUnique
  o = next objectUnique
  if s.node < o.node:
     assign header = headerId(subjectId++)
     emit mapping for s.index -> header
     emit s.node to subject section
     advance s only (pull more subjects until >= o)
  else if s.node > o.node:
     assign header = headerId(objectId++)
     emit mapping for o.index -> header
     emit o.node to object section
     advance o only
  else:
     header = asShared(sharedId++)
     emit mapping for s.index -> header
     emit mapping for o.index -> header
     emit node once to shared section
     advance both

after loop:
  drain remaining subject uniques into subject section with headerId(...)
  drain remaining object uniques into object section with headerId(...)
```

All of the above occurs in **term-lexicographic order**, not tripleID order.

### 4.5 How mapping updates are emitted efficiently (bulk buffers)

CFSD defines bulk buffers of size:

- `PIPE_BULK_BUFFER_SIZE = 128 * 1024` (131,072)

There are two buffer types:

1. `BulkNodeBuffer`: used when emitting new (non-duplicate) nodes through a pipe.
   - Collects `(tripleID, headerId, nodeStringCopy)` triples.
   - On flush:
     - calls `NodeConsumer.bulkMethod(tripleIDs[], headerIds[], ...)`
     - then enqueues node strings into the pipe.

2. `BulkMappingBuffer`: used for duplicates and for sections without pipes.
   - Collects `(tripleID, headerId)` pairs.
   - On flush:
     - calls the `NodeConsumer` bulk method.

Because `CompressTripleMapper` overrides the bulk methods, in the disk pipeline these bulk updates do *not* devolve into per-element callbacks.

### 4.6 Pipes and async consumption (`PipedCopyIterator`)

For subjects/objects/shared, CFSD uses `PipedCopyIterator<CharSequence>`:

- Producer thread: CFSDPipeBuilder calls `pipe.addElement(...)` repeatedly.
- Consumer threads: dictionary section builders (e.g. `WriteDictionarySection.load`) read batches via `takeBatch()` if the iterator is a `PipedCopyIterator`.

This is meant to:

- overlap CPU work (merge/comparison) with I/O work (writing dictionary sections),
- and reduce per-element overhead by batching.

### 4.7 Predicates and graphs are “inline mapped”

Predicate and graph sections are built without the pipe builder thread:

- CFSD wraps `sortedPredicate` (deduped iterator) in a `MapIterator` that:
  - assigns header = `getHeaderId(index+1)`
  - calls `predicateDuplicateBuffer.add(node.getIndex(), header)`
  - returns a `new CompactString(node.getNode())` for the dictionary section
- On iterator completion, `flushOnFinish(..., predicateDuplicateBuffer::flush)` ensures the remaining mapping buffer is flushed.

This means predicate mapping updates happen on the thread consuming the predicate section, not on CFSDPipeBuilder.

### 4.8 Lifecycle / close()

CFSD starts the pipe builder thread immediately in the constructor.

- `close()` interrupts and joins that thread (`cfsdThread.joinAndCrashIfRequired()`).

The temp dictionary sections are one-shot (`OneReadDictionarySection`), so consumption order and single consumption are assumed.

---

## 5) Why CFSD “interacts with LongArrayDisk”

CFSD itself does **not** reference `LongArrayDisk`.

The interaction happens through `NodeConsumer`, which in the disk pipeline is `CompressTripleMapper`.

### 5.1 `CompressTripleMapper` is the bridge

File: `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/hdt/impl/diskimport/CompressTripleMapper.java`

`CompressTripleMapper` implements `CompressFourSectionDictionary.NodeConsumer`.

It builds 3 (or 4) disk-backed mapping arrays:

- `map_subjects`
- `map_predicates`
- `map_objects`
- `map_graph` (optional)

Each mapping array is conceptually:

```
mappingByTripleId[tripleId] = headerId(termLocalId, sharedFlag)
```

### 5.2 The storage stack: WriteLongArrayBuffer → SequenceLog64BigDisk → LongArrayDisk

Storage chain:

1. `WriteLongArrayBuffer`
   - supposed to buffer random `set` operations, but currently `DISABLE_BUFFER = true`.
   - still provides a fast bulk path when the underlying array is `SequenceLog64BigDisk`.
   - File: `qendpoint-core/.../WriteLongArrayBuffer.java`

2. `SequenceLog64BigDisk`
   - a packed bit-array sequence stored on disk.
   - stores fixed-width fields (`numbits`) densely packed into 64-bit words.
   - backed by a `LongArray` implementation (here `LongArrayDisk`).
   - File: `qendpoint-core/.../SequenceLog64BigDisk.java`

3. `LongArrayDisk`
   - implements an array of 64-bit words backed by a memory-mapped file.
   - maps the file in 1 GiB slices (`MAPPING_SIZE = 1<<30`).
   - provides `get(long)` / `set(long, long)` / `set(start, long[], ...)` (bulk contiguous).
   - File: `qendpoint-core/.../LongArrayDisk.java`

So: CFSD emits mapping updates → `CompressTripleMapper` stores them → `SequenceLog64BigDisk` writes packed fields into a `LongArrayDisk` mmapped file.

---

## 6) How this scales (and where it breaks)

### 6.1 Algorithmic complexity vs. real-world I/O

If you look only at pure algorithmic complexity:

- CFSD shared merge: **O(U_s + U_o)** comparisons (unique subjects/objects), plus scanning duplicates as they occur.
- Mapping emission: **O(N)** mapping updates per role (subject/predicate/object/graph) because every triple occurrence must be mapped.
- Section compression (external sort): typical external sort cost **O(N log N)** in terms of comparisons, but implemented as chunk sorts + merges (I/O-bound).

None of this is exponential.

The scale failure is because one stage has **pathological access patterns** for the chosen storage structure.

### 6.2 The fundamental mismatch: term order vs tripleId order

CFSD processes terms in **lexicographic order**.

The mapping storage is indexed by **triple ID** (input order).

Within each lexicographic run (e.g., “all occurrences of term X”), the triple IDs are essentially random across [1..N] because:

- triple IDs were assigned in input order,
- then those `(term, tripleId)` pairs were globally sorted by `term`.

This is the key: **writing to `mappingByTripleId[tripleId]` while reading in `term` order is a random-write workload**.

### 6.3 Why memory-mapped random writes collapse at size

Memory-mapped files (`LongArrayDisk`) operate at **page granularity** (typically 4 KiB pages).

When you write one entry at a random position:

- the OS must bring the containing page into memory (page fault),
- you modify a few bytes in that page,
- the page becomes dirty and must be written back eventually.

If your mapping file is larger than RAM, and your write pattern touches pages with little locality, the OS constantly evicts/loads pages:

- page cache thrashing
- high writeback pressure
- very low useful bytes per IO

That can look “exponential” because throughput drops sharply once the working set crosses RAM and the OS switches from caching to paging behavior.

#### A rough mental model

Let:

- N = number of triples
- each role mapping stores ~ `numbits ≈ log2(N)+1` bits per triple
- so bytes per mapping file is roughly `N * (log2(N)+1) / 8`
  - N=1e9 → ~ 1e9 * 31 / 8 ≈ 3.9 GiB per role
  - 3 roles → ~ 12 GiB of mapping files

Now consider a CFSD bulk flush of B=131,072 mapping updates.

Those B triple IDs are (after sorting by ID) still distributed across the entire [1..N] range.

Even with sorted IDs, the updates hit a large number of distinct pages:

- each page contains on the order of ~ `pageBytes / bytesPerEntry` entries
- but entries are dense by index, and your indices are random,
- so collisions per page are low when the index space is huge.

Result: a single bulk flush can touch **tens of thousands of pages**.

When the mapping file fits in RAM, those pages are mostly resident and the update is fast.
When it does not fit, each flush requires pulling those pages from disk and later writing them back.
The effective IO multiplies dramatically.

### 6.4 Extra cost: eager zero-fill of huge LongArrayDisk files

`LongArrayDisk`’s constructor defaults to `overwrite=true` in many call sites (notably `SequenceLog64BigDisk`), which calls:

```java
if (overwrite) {
  clear();   // set0(0, length())
}
```

`clear()` writes zeros across the entire mapped file and forces mappings periodically.

In the disk dictionary path, you often create multiple large `LongArrayDisk` files:

- subject mapping
- predicate mapping
- object mapping
- graph mapping (quads)
- plus block pointer sequences for `WriteDictionarySection` temp files

Even if the file is brand new and logically already “all zeros”, this eager zero-fill:

- does an extra full-file write pass,
- forces physical allocation/writeback,
- and converts what could be a sparse file into fully allocated blocks early.

This increases the cost of later random writes because pages now tend to require read‑modify‑write against on-disk zeros, rather than “allocate-on-write”.

### 6.5 Hidden multiplier: overestimated section counts inflate disk work

The disk loader frequently passes **“counts” that are actually just `tripleCount`** through the dictionary pipeline because it “doesn’t know” unique term counts yet:

- `CompressionResultPartial` explicitly returns `triplesCount` for `getSubjectsCount/getPredicatesCount/getObjectsCount/getSharedCount/getGraphCount`.
- `CompressionResultFile` returns `subjects.getSize()` / `predicates.getSize()` / `objects.getSize()` which, for section files, is still “one entry per triple occurrence”, i.e. approximately `tripleCount`. It also returns `getSharedCount() = tripleCount`.

Those counts are fed into CFSD and then into `OneReadDictionarySection(..., count)`. For disk-backed dictionary sections (`WriteDictionarySection`), `count` is used to preallocate the `blocks` sequence:

- `WriteDictionarySection.load(..., count, ...)` creates `SequenceLog64BigDisk(..., capacity = count / blockSize)`.
- `SequenceLog64BigDisk` defaults to `overwrite = true` and therefore triggers `LongArrayDisk.clear()` on creation.

If `count` is wildly larger than the real number of dictionary entries (unique terms), the preallocated blocks file can be *hundreds of MB or more* and gets fully zero-filled even though it will be trimmed later (`blocks.aggressiveTrimToSize()`).

This is usually a secondary cost compared to the tripleId→dictId mapping arrays, but at scale it becomes noticeable and it compounds with the overall “extra full-file write pass” problem.

Practical mitigation directions:

- Prefer append-only growth (start `SequenceLog64BigDisk` at small capacity and let it grow), or
- Pass a better estimate of unique term counts (compute while merging), or
- Keep preallocation but **don’t clear** when the temp files are newly created (delete/truncate first, then `overwrite=false`).

### 6.6 Extra cost: allocations and re-encoding work

CFSD currently:

- decodes term strings from compressed section files (delta-coded),
- copies them into `CompactString` objects (alloc + byte copy),
- then dictionary sections re-encode them into PFC-like encoding again.

This is not the primary “exponential” failure mode, but it is substantial CPU+GC work:

- large numbers of `CompactString` allocations
- byte array copying proportional to total dictionary bytes
- backpressure through the pipes can cause producer threads to spin-wait

### 6.7 Secondary scaling pressure: external merge cost

`SectionCompressor` is basically an external sort pipeline:

- per chunk: sort in memory (parallel sort), write compressed run
- merge: k-way merge runs into larger runs

Total IO grows with number of merge passes:

- ~ datasetSize * (1 + numberOfPasses)
- passes ~ log_k(numberOfChunks)

This is expected for external sort. It is usually not the “sudden cliff” you describe (that cliff is far more characteristic of paging/caching collapse).

---

## 7) What to change if the goal is “scalable”

The main lever: **stop producing a dense random-access tripleID→termID table by random writes into memory-mapped arrays.**

Below are concrete redesign strategies (in increasing ambition).

---

## 8) Recommendations (short-term mitigations)

These can often give large wins without rewriting the whole pipeline.

### 8.1 Ensure bulk mapping paths are actually used

CFSD calls bulk overloads (`NodeConsumer.onSubject(long[],...)`, etc). If a `NodeConsumer` implementation does not override those, the default methods loop and call the single-element callback.

That is catastrophic for disk-backed mapping because it:

- destroys batching,
- forces per-element bitfield updates,
- increases method call overhead dramatically.

In the disk importer, `CompressTripleMapper` already overrides them, so this is mostly a guardrail:

- If you have other `NodeConsumer` implementations, override bulk methods too.

### 8.2 Remove / gate hard-coded progress file writes in CFSD

`CompressFourSectionDictionary` currently writes timing/progress files to:

```
/Users/havardottestad/Documents/Programming/qEndpoint3/indexing/cfds_*.txt
```

This is not portable and adds repeated file open/append operations.

If you are benchmarking, it also perturbs IO behavior.

Recommendation:

- move to `ProgressListener`/SLF4J logging only, or
- gate behind a debug flag and write to a configurable path, or
- remove for production performance work.

### 8.3 Avoid eager zero-fill for brand-new disk arrays (big win candidate)

If mapping files (and block pointer files) are created in fresh working directories, you can typically avoid `LongArrayDisk.clear()` and rely on the filesystem’s zero-fill semantics for new/sparse files.

This can reduce:

- one full-file write pass per array,
- and can reduce read amplification during random writes.

Concrete direction:

- Make `SequenceLog64BigDisk` and/or `LongArrayDisk` default to **not clearing** when the file is newly created or explicitly truncated.
- Ensure correctness by creating/truncating the file before mapping (so it cannot contain stale data).

This is a “surgical” change with strong payoff potential.

### 8.4 Consider fixed-width mapping arrays (trade space for speed)

Packed bitfields in `SequenceLog64BigDisk` require read-modify-write of 64-bit words, and complicate write batching.

A simpler disk structure:

- `IntBuffer` or `LongBuffer` file with one fixed-width slot per tripleID.

Pros:

- simpler writes (no bit packing math),
- simpler to build sequentially,
- may reduce CPU overhead.

Cons:

- more disk space (but mapping files are already huge; the extra may be acceptable if it avoids paging cliffs).

This does **not** solve the “random page” problem by itself, but it removes a layer of CPU work and can make other approaches easier.

### 8.5 Remove forced `System.gc()` in the mapping writer (often a free win)

`CompressTripleMapper.setShared(...)` calls `subjects.free(); predicates.free(); objects.free(); [graph.free();]`.

`WriteLongArrayBuffer.free()` ends with `System.gc()`. With buffering disabled (`DISABLE_BUFFER = true`), this degenerates into “flush + `System.gc()`”, and it is called 3–4 times back-to-back. On large imports, that can introduce large stop-the-world pauses, and it does not reliably address the paging problem (the memory-mapped mapping files are still referenced and still needed for the triples phase).

Recommendation: remove the `System.gc()` call (or guard it behind an explicit debug flag), and make `free()` only clear optional heap buffers. If the intent was to force-unmap mmaps, do that explicitly via `close()` when the mapping files are no longer needed.

### 8.6 Tuning knobs you can use today (mitigations, not fixes)

The disk loader exposes several options (see `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/options/HDTOptionsKeys.java`) that can move the knee where performance collapses:

- `loader.disk.chunkSize`: Larger chunks reduce the number of chunk runs and merge passes in `SectionCompressor`, but increase peak heap usage (more `IndexedNode` + term bytes in memory).
- `loader.disk.mergeConcurrency`: Limits concurrent merge tasks. Reducing it can reduce I/O thrash when the merge stage reads many runs in parallel (especially on slower SSDs/network storage).
- `loader.disk.kway` and `loader.disk.maxFileOpen`: Control the fan-in of k-way merges. Larger fan-in reduces number of merge passes (good) but increases number of simultaneously open files/buffers (can be bad for OS cache and file descriptor limits).
- `loader.disk.fileBufferSize`: Changes buffering for reading/writing run files. Too small increases syscalls; too large can inflate RAM pressure and reduce OS cache effectiveness.
- `disk.compression`: Compressing temporary run files can reduce I/O volume at the cost of CPU. This can help when I/O dominates (but does not fix random-write mapping).

These knobs can buy time, but they don’t remove the root mismatch (term-order processing vs tripleId-indexed random-write mapping tables).

---

## 9) Recommendations (architectural redesign options)

### Option A — Two-pass pipeline: build dictionary first, then map triples sequentially

Core idea:

1. First pass: build dictionary sections (S/P/O/SH[/G]) from term streams (external sort).
2. Second pass: re-read the input RDF (or a spooled representation) in original triple order, and map each term via dictionary lookup (`stringToId`), writing mapped triples sequentially.

Why this scales:

- mapping writes become sequential (tripleID order)
- no need for random-access mapping arrays at all
- avoids giant random-write mmapped tables

Tradeoffs:

- requires input to be re-readable (file path, or you must spool during first pass)
- second parse/scan cost, but it’s sequential IO and usually far cheaper than paging‑thrash random IO at scale

Implementation sketch:

- For file inputs (N-Triples/N-Quads), disk loader can reopen and parse again.
- For stream inputs, add an optional **spool file** (possibly compressed) that stores triples in a compact binary form.

This is the most common scalable pattern for HDT builders historically: “build dictionary pass, then build triples pass”.

#### The catch: two-pass requires fast term→ID lookups

The current disk loader largely avoids building/holding a huge term→ID map by using tripleID→headerID mapping tables instead.

If you switch to a true two-pass approach, you have to map *every subject/predicate/object* to its dictionary ID in pass 2. If you use the current `Dictionary.stringToId(...)` implementation naïvely, lookups typically do:

- binary search over blocks (`locateBlock`) + scan within a small block (`locateInBlock`) in `PFCDictionarySectionMap`,
- which implies **O(log blocks)** comparisons and (for disk-backed dictionaries) **random reads** into the dictionary text file and its block pointer array.

For very large dictionaries that exceed RAM, that can reintroduce a page-cache problem (random reads instead of random writes). It may still be “better than random writes” on many systems, but it is not automatically fast.

If you want Option A to be robust at huge scale, you usually need an additional lookup structure, e.g.:

- an on-disk minimal perfect hash (MPH) or succinct trie for term→ID,
- a disk-friendly key/value store for term→ID (with large sequential build and cached lookups),
- or a hybrid: keep a bounded in-memory hash cache + fall back to dictionary locate for cold terms (works best if term reuse is high).

This is one of the main reasons Option C (bucketed mapping) is often the “least invasive scalable” redesign in your current architecture: it keeps the existing “no term→ID lookup” property while fixing the random-write failure mode.

### Option B — Keep single-pass input, but replace random mapping writes with external sorting of mapping pairs

Core idea:

- During dictionary construction, instead of doing `mapping[tripleId]=value`, you **append mapping pairs** `(tripleId, headerId)` to disk.
- After dictionary building:
  - external-sort those pairs by `tripleId`
  - stream them to produce either:
    - a sequential mapping file, or
    - directly produce mapped triples by joining subject/predicate/object streams on tripleId.

Why this scales:

- turns random writes into append-only sequential writes + sequential external merge sort.
- external sort is IO-heavy but predictable and scalable.

Tradeoffs:

- requires extra temporary disk equal to the mapping pair logs.
- you must choose encoding carefully (don’t store 16 bytes/pair naïvely for huge N without compression).

Practical optimizations:

- delta-encode sorted `tripleId`s within each run
- store values in compact width (e.g., varint or fixed 32-bit)
- partition into buckets (Option C) to avoid a full global sort

### Option C — Partitioned mapping (“bucket by tripleId range”), then sort within buckets

Core idea:

- Choose K buckets based on tripleId high bits or ranges (e.g., bucket size = 1–4 million triples).
- While CFSD emits mapping pairs, route each `(tripleId, headerId)` to its bucket file (append-only).
- After dictionary build, process each bucket independently:
  - load bucket into memory, sort by tripleId, write sequential mapping/triple outputs.

Why this scales:

- reduces random IO to sequential appends to a small number of files
- allows controlling memory usage per bucket
- can parallelize across buckets
- avoids global external sort (or makes it much smaller)

Tradeoffs:

- requires routing overhead during construction
- needs careful file handle management and buffering (don’t keep thousands of bucket files open)

This is often the best compromise: predictable IO, controllable memory, easy parallelism.

### Option D — Per-chunk local dictionaries + translation tables (classic external dictionary construction)

Core idea:

- For each chunk, build a local term dictionary and represent triples using **local term IDs**.
- Merge local dictionaries into a global dictionary, producing translation tables localId→globalId.
- Rewrite or merge triple representations using translation tables.

Why this scales:

- chunk work stays memory-local
- avoids random updates into global tables during lexicographic processing

Tradeoffs:

- higher implementation complexity
- requires careful design of translation storage and triple rewriting

This resembles how search engines build global dictionaries/postings from per-segment dictionaries.

### Option E — Change the whole pipeline: sort triples by strings, then assign IDs directly

Core idea:

- Instead of sorting S/P/O separately and reconstructing triples via mapping, write chunks of *triples as strings* and externally sort triples by (S,P,O[,G]).
- Then build dictionary once from the sorted triple stream (a single lexicographic traversal).

Why this scales:

- avoids the “join back on tripleId” problem entirely
- aligns with the common “sorted triples” HDT construction approach

Tradeoffs:

- string-based triple sort is heavier (more bytes moved, more comparisons)
- but can be competitive on large datasets if implemented as external sort with compression and careful I/O.

---

## 10) Practical “what I would do next” (performance engineering plan)

If the goal is “make large RDF→HDT predictable and scalable”, a pragmatic staged approach:

### Stage 1 — Confirm the bottleneck is mapping random writes

Instrument timings around:

- mapping array creation/clear time
- CFSD runtime
- mapping update throughput (triples/s) during CFSD
- OS page fault and writeback metrics (JFR + OS tools)

Useful code points:

- `CompressFourSectionDictionary` pipe builder thread loop
- `CompressTripleMapper.onSubject/onObject/...` bulk calls
- `LongArrayDisk.clear()` time

Expected signature if this is the core issue:

- CFSD merge CPU looks fine on small data
- once mapping files exceed RAM, throughput drops sharply and disk activity spikes

### Stage 2 — Prototype the “skip clear / sparse file” change

This is the smallest architectural shift likely to yield meaningful wins:

- avoid full-file zero fill on mapping array creation for fresh files
- keep correctness by truncating/deleting first

Measure again.

### Stage 3 — Implement bucketed mapping (Option C) or two-pass (Option A)

Pick based on product constraints:

- If inputs are always files → **Option A** is simplest and very robust.
- If inputs can be streams and you must remain one-pass → **Option C** (bucketed mapping) is usually best.

### Stage 4 — Simplify mapping storage (optional)

Once mapping is sequential/bucketed, decide on representation:

- packed bits (smaller, more CPU)
- fixed-width ints/longs (bigger, simpler)

In practice, fixed-width often wins when IO dominates.

---

## 11) Appendix: Key files and “what to read” order

If you want to re-architect, this is a good reading path:

1. Disk import orchestration:
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/hdt/impl/HDTDiskImporter.java`

2. External sort / section compression:
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/hdt/impl/diskimport/SectionCompressor.java`
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/util/concurrent/KWayMerger.java`
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/iterator/utils/PriorityQueueMergeExceptionIterator.java`

3. Compressed node format:
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/util/io/compress/CompressNodeWriter.java`
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/util/io/compress/CompressNodeReader.java`

4. CFSD:
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/dictionary/impl/CompressFourSectionDictionary.java`
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/util/io/compress/CompressUtil.java`
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/iterator/utils/PipedCopyIterator.java`

5. Mapping storage (the likely scale bottleneck):
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/hdt/impl/diskimport/CompressTripleMapper.java`
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/util/io/compress/WriteLongArrayBuffer.java`
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/compact/sequence/SequenceLog64BigDisk.java`
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/util/disk/LongArrayDisk.java`

6. Triple reconstruction + sorting:
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/util/io/compress/MapCompressTripleMerger.java`
   - `qendpoint-core/src/main/java/com/the_qa_company/qendpoint/core/util/io/compress/TripleGenerator.java`

---

## 12) Appendix: A concrete “bucketed mapping” architecture sketch (Option C)

This section is intentionally specific: it’s meant to be implementable.

### 12.1 Goal

Replace:

- random writes into `mappingByTripleId[]` during dictionary build

With:

- append-only writes of `(tripleId, headerId)` pairs into a small number of bucket files
- later, sequential construction of mapping arrays or direct triple reconstruction.

### 12.2 Bucket routing

Let:

- `bucketSizeTriples = 4_000_000` (example; tune so bucket fits in memory)
- `bucket = (tripleId - 1) / bucketSizeTriples`
- `offset = (tripleId - 1) % bucketSizeTriples`

For each role (S/P/O[/G]):

- have `K = ceil(N / bucketSizeTriples)` bucket files
- append records `(offset, headerId)` (offset fits in 32 bits, headerId fits in 32 bits for most N)

Use buffered streams; keep only a small LRU of open file handles.

### 12.3 Bucket finalization

After dictionary build (and sharedCount known), for each bucket:

1. Load bucket records (or stream-sort them if too large).
2. Sort by `offset`.
3. Produce:
   - either a dense mapping array file for that bucket range (sequential write), or
   - directly emit mapped triples by joining S/P/O bucket streams on `offset`.

Then the triple construction stage can iterate buckets in order (which corresponds to tripleId order), producing triples sequentially (or feeding into the existing triple compressor).

### 12.4 Concrete record format + join procedure (practical details)

To make this implementable and fast, treat each bucket file as an append-only log of fixed-size records.

Suggested record encoding (per role, per bucket):

- `offset` (32-bit unsigned; `0..bucketSizeTriples-1`)
- `headerId` (64-bit or 32-bit depending on expected range; `headerId` is what CFSD emits today)

This is intentionally not varint-based: fixed-size records make sorting and scanning faster and reduce CPU overhead. If disk space becomes an issue, compress bucket logs after writing (or delta-encode sorted offsets during finalization).

Finalization join (per bucket):

1. Read each role’s bucket log into memory (or external-sort it if too large), sort by `offset`.
2. Iterate `offset = 0..bucketTripleCount-1`:
   - For each role (S/P/O[/G]), consume the next record and assert `record.offset == offset`.
   - Convert `headerId` → final dictionary ID using `CompressUtil.computeSharedNode(headerId, sharedCount)` (and apply predicate/graph offsets if your dictionary layout expects that).
   - Emit mapped triples into the existing triple-chunk buffer (the structure currently filled in `MapCompressTripleMerger.createChunk`), then sort by the target order and write as today.

Correctness checks you want (cheap and very useful):

- Each role produces exactly one mapping per triple (per bucket): no missing offsets, no duplicates.
- After finalization, mapped triple IDs are all positive and within expected ranges (`TripleID.isValid()` already exists).
- Optional: keep a fast “first seen” bitmap per bucket during sorting to detect duplicates early.

### 12.5 Why this matches your existing pipeline nicely

Your pipeline already has:

- external sort infrastructure (`KWayMerger`, `PriorityQueueMergeExceptionIterator`)
- chunking strategies and configurable IO buffers

Bucketed mapping can reuse those patterns and avoids invasive changes to CFSD’s merge logic (CFSD can still emit mapping updates; only the sink changes).

### 12.6 Integration plan (minimal invasive path)

If you want to implement Option C with minimal disruption to the rest of the disk loader, a practical “surgical” approach is:

1. **Introduce a new `NodeConsumer` implementation** (parallel to `CompressTripleMapper`) that writes bucket logs instead of updating a random-access array.
   - Example name: `BucketedTripleMapper` (or `BucketedMappingSink`).
   - It implements the same bulk callbacks CFSD already uses (`onSubject(long[],...)`, `onPredicate(long[],...)`, …).
   - Its constructor chooses `bucketSizeTriples` and creates/cleans a `bucket/` directory under `basePath` (or under `tripleMapper/`).

2. **Change `HDTDiskImporter.compressTriples(...)` to have a second implementation path**:
   - Current path: `MapCompressTripleMerger` + random-access `CompressTripleMapper`.
   - New path: “bucket finalization + mapped-triple chunk writing” (sequential/bucketed).

3. **Reuse existing triple chunk sorting/writing logic** where possible:
   - The code inside `MapCompressTripleMerger.createChunk(...)` already does the right thing once it has *mapped* `TripleID` objects:
     - buffer → sort by requested `TripleComponentOrder` → write `CompressTripleWriter` output.
   - In the new bucketed path, you can keep the same “buffer/sort/write” pattern; the only change is that `mappedTriple` comes from bucket join instead of `mapper.extract*`.

4. **Keep shared-ID resolution exactly as today**:
   - Bucket logs store **headerIds** (what CFSD emits).
   - During bucket finalization, compute final dictionary IDs via `CompressUtil.computeSharedNode(headerId, sharedCount)`.
   - Apply predicate/graph offset rules the same way `CompressTripleMapper.extractPredicate/extractGraph` do today (i.e., subtract sharedCount when appropriate).

5. **Add strong validation while developing (then relax in production)**:
   - Per bucket: assert offsets are complete and unique for each role.
   - Globally: count triples written equals `tripleCount` (or equals the distinct triple count if de-duplication happens later).

This path lets you keep:

- CFSD’s dictionary merge logic,
- section compression/external sort pipeline,
- the overall “chunk → sort → write → merge” triple building approach,

…while removing the single worst scalability killer: random writes into enormous mmapped arrays indexed by tripleId.

---

## 13) Appendix: A note on “counts” and over-allocation

CFSD constructs `OneReadDictionarySection(subjectPipe, subjectsCount)` where `subjectsCount` comes from `CompressionResult.getSubjectsCount()`.

In file mode, that count equals the number of occurrences (≈ tripleCount), not unique terms.

Many dictionary section builders use this count for preallocation/progress.

For disk-backed sections (`WriteDictionarySection`), overestimating counts mostly affects:

- temporary block pointer structure capacity
- progress math

It is less dangerous than in-memory dictionary sections, but it still inflates disk work if it triggers large eager clears.

A more scalable design would:

- avoid needing accurate counts up front (append-only structures),
- or compute unique counts in a cheap prepass (if you accept a second scan).

---

## 14) Diagnostics: how to prove where time goes (before rewriting)

If you want to turn this report into an actionable performance plan, the first goal is to separate:

1. External sort costs (expected, roughly linearithmic, mostly sequential I/O), from
2. Mapping-table costs (pathological, random I/O + page-cache thrash), from
3. JVM-level overhead (GC pauses, allocation rate, thread contention).

### 14.1 Add timing around the real suspects

The most informative timers (high signal, low noise):

- Mapping file creation/initialization:
  - `CompressTripleMapper` constructor (`new SequenceLog64BigDisk(..., overwrite=true)` → `LongArrayDisk.clear()`).
- Mapping update throughput:
  - `CompressTripleMapper.onSubject/onPredicate/onObject` bulk overloads (how many mappings/s as files grow).
- Dictionary section write throughput:
  - `WriteDictionarySection.load(...)` (time spent writing term streams vs blocks appends).
- Triples stage mapping throughput:
  - `MapCompressTripleMerger.createChunk(...)` (time in `mapper.extract*` and how it scales with mapping file size).

### 14.2 Record a JFR during a “cliff” run

On Java 11+, use JFR to capture:

- CPU hotspots (where time is spent in Java),
- GC pauses and allocation rate,
- file I/O events (if enabled),
- thread states (spin-wait vs blocked).

You want a recording that straddles the “throughput collapse” moment.

### 14.3 OS-level confirmation (page faults + writeback)

The hallmark of the mapping-table bottleneck is:

- rising major page faults,
- high disk writeback,
- sustained random write I/O.

Exact commands differ by OS, but the principle is the same: correlate the import time window with page-fault and disk I/O counters. If you can, sample both “small dataset” and “large dataset” runs and compare.

Examples (pick what applies to your environment):

- macOS:
  - Memory pressure/pageouts: `vm_stat 1`
  - Disk throughput: `iostat -w 1`
  - Per-process I/O sampling (heavier): `fs_usage -w -f filesys <pid>` (may require elevated privileges)
- Linux:
  - Per-process disk I/O: `pidstat -d 1 -p <pid>`
  - Page faults (process): `perf stat -e minor-faults,major-faults -p <pid>`
  - System-wide paging: `vmstat 1`

### 14.4 Minimal proof-of-bottleneck experiment (conceptual)

If you need a quick sanity check without a full RDF import:

- Create a `LongArrayDisk`/`SequenceLog64BigDisk` sized like your real mapping files.
- Perform repeated random `set(position,value)` updates (or bulk `set(long[],...)` updates with random positions).
- Measure throughput as the file size crosses RAM.

If throughput collapses in the same shape as the RDF→HDT import, you’ve validated that the observed “exponential” slowdown is primarily the storage access pattern, not the dictionary merge algorithm.

---

## 15) Implementation roadmap (how to turn this into scalable code changes)

This is a pragmatic sequence of changes that progressively remove the biggest scaling cliffs while keeping risk controlled.

### 15.1 PR-sized steps (recommended order)

1. **Remove forced GC + hard-coded debug file I/O (low risk, quick win)**
   - Remove/guard `System.gc()` in `WriteLongArrayBuffer.free()` (currently called 3–4 times during disk import).
   - Remove/guard hard-coded `Files.writeString(Path.of(\"/Users/.../cfds_*.txt\"))` in `CompressFourSectionDictionary`.
   - Expected outcome: less stop-the-world variance, less incidental I/O noise.

2. **Stop eager zero-fill on new disk arrays (medium risk, big win on large N)**
   - Goal: avoid `LongArrayDisk.clear()` for brand-new/truncated files.
   - Requirements:
     - ensure file length is set/truncated to the expected size before mapping,
     - ensure reads of unwritten slots return 0 (sparse-file semantics),
     - preserve safety when reusing an existing path (explicit delete/truncate).
   - Expected outcome: remove multi-GB “write zeros” passes during import startup and reduce early writeback pressure.

3. **Introduce bucketed mapping (architectural win; still localized)**
   - Add a bucket-log `NodeConsumer` and a bucket-finalization triple builder (see §12.6).
   - Keep CFSD and section compression unchanged; only swap the mapping sink and triples stage implementation.
   - Expected outcome: replace random mmapped writes with sequential appends + per-bucket sort, eliminating the page-cache cliff.

4. **Optional: simplify mapping representation (only after (3))**
   - Once mapping writes are sequential (bucketed), consider dropping packed bitfields in favor of fixed-width ints/longs if it improves CPU simplicity and speeds up per-bucket sorting/joining.

### 15.2 Invariants you must preserve

No matter which redesign you choose, preserve these behavioral contracts:

- **ID semantics**:
  - shared IDs are the same for subject/object,
  - subject/object-only IDs are offset by `sharedCount`,
  - predicate (and graph) IDs are independent (do not include shared offset in the final HDT dictionary encoding).
- **Triple correctness**:
  - all mapped `TripleID`s must be valid and consistent with dictionary sizes,
  - duplicate triple elimination must match current behavior (today: chunk sort + `prev.match(triple)` de-dupe during writing).
- **Quads support**:
  - graph role is optional; keep the path symmetric with subject/object/predicate mapping.

### 15.3 How to validate performance improvements

For each step, measure at least:

- wall-clock time for dictionary stage vs triple stage,
- throughput (triples/s) before and after the “cliff” point,
- OS-level paging/writeback counters during the slow phase.

Avoid judging changes on tiny datasets; the failure mode is a working-set crossover problem that only appears once mapping/data structures exceed RAM.
