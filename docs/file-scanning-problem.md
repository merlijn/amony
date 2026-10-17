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
    FS) hash as before. The path shortcut additionally requires compatible keys: mismatch with both keys present forces
    a rehash, while a missing key on either side still takes the shortcut.
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

## Convergence / eventual consistency

Snapshot diffing converges by construction on quiescence: every scan rebuilds `current` fresh from disk, so detection
never accumulates error (a pure event stream could not heal a missed event). Eventual consistency holds iff scans
complete, writes eventually succeed, and identity evidence is unambiguous. Four holes remain:

23. Mid-scan IO failure aborts the whole scan — `populateStore` has no per-file error handling, so one file deleted
    between walk and hash fails the entire stream. Hot directories may never complete a scan and hence never converge
    while churning. Needs per-file `attempt` (treat vanished as absent).
24. Swallowed outcomes plus long-lived memory diverge silently until restart — a dropped event applies to the in-memory
    store but never reaches the DB, and later scans go quiet. Heals via fresh snapshots (each manual scan rebuilds state
    from the DB, re-emitting the event), so this only bites long-lived poller state.
25. Ambiguous identity merges converge to a consistent-but-wrong linkage, silently forever — disk, memory and DB agree,
    so nothing re-fires. Bytes and paths always converge; only the linkage is best-effort. Needs stronger identity
    (full-hash verification) to close.
26. Non-atomic multi-event batches (dir rename = N transactions, memory updated ahead of the DB) heal through fresh
    snapshots, since unchanged hashes re-emit the pending moves. The structural fix is batch application: compute the
    full event set, apply it to a clone of the store, swap the reference, commit one DB transaction — which would also
    remove the need for the circular-rename special-casing in `InMemoryFileStore`.

Convergent by design and worth keeping: files changing between scans collapse to their latest state (history loss, not
divergence), and API-side deletes reconcile through the drop path (memory and DB both end up without the file).

## Windows: fileKey support

`BasicFileAttributes.fileKey()` is unconditionally `null` on Windows, so all key-driven behaviour degrades there:
in-place edits and moves-with-modification report as delete+add, and renames are still detected via partial hash but
always rehashed. This is pinned by tests (key-less fallback cases in `DirectoryScannerSpec`). macOS and Linux are
unaffected, including temp dirs and APFS.

This is a JVM gap, not a filesystem one: NTFS/ReFS provide stable identity (64-bit file index + volume serial via
`GetFileInformationByHandle`, 128-bit persistent ID via `GetFileInformationByHandleEx` + `FileIdInfo`; FAT/exFAT
genuinely have none). The JDK reads these values internally for `Files.isSameFile` but its `fileKey()` override returns
`null` on every version to date.

No dedicated library packages this for Java (OSHI reports on file stores, not per-file IDs). The recipe if Windows ever
becomes a target: add `net.java.dev.jna:jna-platform` (JNA core likely already present via the watcher) and wrap
open/query/close in ~30 lines — `CreateFile` with `FILE_FLAG_BACKUP_SEMANTICS` (required for directories), prefer
`FileIdInfo` over the 64-bit index (documented as stable only while open), format as `(volSerial, indexHigh, indexLow)`.
No action while Windows is not targeted.
