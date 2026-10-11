# File scanning problems

Remaining flaws and inefficiencies in directory scanning (fixed items excluded).

## Identity / hashing

1. Tiny coverage — 1 KiB sampled of multi-GB files (`PartialHash.scala:58`, `ResourceBucketConfig.scala:122`).
   False-equivalence risk, especially for structured media.
2. Deterministic offsets per size (`new Random(size)`, `PartialHash.scala:32`) — same-size files probe same offsets;
   uniform regions (zeros, headers) systematically collide.
3. Size not mixed into digest, only into seed (`PartialHash.scala:57-67`). Same sampled bytes with different sizes can
   yield same hash; move matching ignores size anyway (`DirectoryScanner.scala:91`).
4. No `fileKey`/inode use — renames only detectable via content hash, forcing rehash of every moved file.
5. `mtime+size` cache race (`DirectoryScanner.scala:64`) — coarse clocks (FAT/SMB/NFS) or rapid writes can reuse a stale
   hash and miss updates.

## Matching / events

6. `FileMetaChanged` persistence is lossy (`LocalResourceSyncer.scala:148`) — only `timeLastModified` is saved, new
   `size`/`partialHash` discarded, so DB goes stale.
7. `moveWith` only updates `fs_path` (`ResourceDatabase.scala:186`) — move+touch leaves size/hash/mtime stale.
8. Duplicate content degrades (`DirectoryScanner.scala:104`) — only 1-deleted/1-added per hash bucket becomes
   `FileMoved`; N-M renames with identical content fall back to `Delete+Add` with new IDs.
9. Move+touch misclassified — rename with concurrent content change rehashes to a new bucket, yielding `Delete+Add`
   instead of `Moved`.
10. `toFileStore` crashes on legacy `None` hash (`LocalResourceSyncer.scala:78`, `.get`) and masks missing mtime with
    `getOrElse(0)`.
11. Same-path edit vs. replace ambiguity (accepted by design) — in-place edit changing sampled bytes emits `Delete+Add`
    with new ID, losing user meta/tags/collections. Noted as open design question, not scheduled for change.

## DB / atomicity

12. Per-event N+1 lookups (`LocalResourceSyncer.scala:30`) — one `getResourceByPartialHash` per event instead of
    batching/preloading; index now helps but round-trips remain.
13. Per-event transactions (`LocalResourceSyncer.scala:155`) — dir rename with N files = N txns + N topic/index updates,
    non-atomic; partial failure leaves half-renamed state.
14. Memory-first commit order (`DirectoryScanner.scala:163` + `LocalResourceSyncer.scala:142`) — `FileStore.applyEvent`
    mutates before DB commit succeeds; divergence until restart.
15. `FileAdded` race unhandled — file vanishing between listing and `newResource` (`LocalResourceSyncer.scala:42`) still
    fails the stream; only the `withRequireResource` miss path was hardened.

## Efficiency / polling

16. ~~Sequential hashing on the critical path (`DirectoryScanner.scala:61`, `evalMap`) + blocking IO in `IO{}`
    (`PartialHash.scala:59`) + eager blocking walk (`RecursiveFileVisitor.scala:36`, collected `Seq` in memory).~~
    Fixed: hashing runs via `parEvalMapUnordered(parallelism)`, file IO runs on the blocking pool (`IO.blocking`, walk
    deferred via `Stream.eval`). The walk still collects a `Seq` in memory — deliberately left for later.
17. ~~No hash reuse on rename (`DirectoryScanner.scala:63`, `getByPath(newPath)` only) — renamed dir forces N full
    rehashes.~~ Fixed: `InMemoryFileStore` keeps a `fileKey` index; `populateStore` falls back to it when the path
    lookup misses, reusing the hash on `(fileKey, size, modifiedTime)` match. Files without a key (Windows, some network
    FS) hash as before.
18. No stable-file detection — in-progress upload/copy observed mid-write becomes `Added` with wrong hash/size, then
    churns.
19. Filter/config change causes mass `Delete` — newly excluded files vanish from `current` and read as deleted; no
    distinction between exclusion and real deletion.
20. `InMemoryFileStore.scala:37` torn reads — dual maps updated under `synchronized`, reads lock-free;
    `getAllByPartialHash:51` snapshots via `toSeq`.
21. `visitFileFailed` silently skips (`RecursiveFileVisitor.scala:24`) — transient IO errors cause add/delete flapping
    next poll.
22. `DirectoryWatcher.scala:16` dead code, not swappable as-is — `path.toString` as hash, `ENTRY_MODIFY` ignored, 100 ms
    delete heuristic, no `OVERFLOW` handling, raw `Thread` + no cancellation.
