# File tracking options

Research into ready-made alternatives to the hand-rolled directory scanner (polling + partial hashing + fileKey
tracking). Conclusion up front: nothing off-the-shelf solves rename pairing — every option emits create/delete pairs and
leaves correlation to the backend. The realistic win is replacing the fixed poll interval with event hints while keeping
the content-hash matcher and the periodic full scan as authority.

## Libraries (JVM/Scala)

| Library                                             | Status                                                                 | Rename events?                                                                | macOS story                                                    | Verdict                                                                                                                                                                  |
| --------------------------------------------------- | ---------------------------------------------------------------------- | ----------------------------------------------------------------------------- | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| fs2 `Watcher` (`fs2.io.file`, already on classpath) | Active, MIT, Scala 3                                                   | No (`Created/Deleted/Modified/Overflow`), no recursion (direct children only) | Inherits JDK (polling on older/macOS JDKs)                     | Use as thin hint — zero new deps, `Resource` + `Stream` native; recursion, overflow rescan and hash matching stay ours                                                   |
| `io.methvin:directory-watcher` (core Java artifact) | Low-activity but maintained (~0.19.x), Apache-2.0, deps only slf4j+JNA | No (`CREATE/MODIFY/DELETE/OVERFLOW`), optional file-hashing to dedup/coalesce | Best pure-JVM story — JNA FSEvents impl auto-selected on macOS | Top ready-made pick — recursive + macOS-native; bridge listener via `Resource` + `Channel` (the Scala better-files bridge may lack Scala 3 artifacts, use core directly) |
| `com.swoval:file-tree-views` (sbt's watcher)        | Stale (~2021-22), Apache-2.0                                           | No, event types untrustworthy cross-platform                                  | Native via JNI                                                 | Skip — JNI weight + staleness outweigh the cached-tree model                                                                                                             |
| `sbt/io` WatchService                               | Active, Apache-2.0                                                     | No                                                                            | Native macOS since sbt 1.1                                     | Skip — build-tool-shaped API, awkward Cats Effect fit                                                                                                                    |
| better-files monitor / Commons IO monitor           | Active / active                                                        | No / no                                                                       | JDK polling / pure polling                                     | Skip — nicer pollers, no native win                                                                                                                                      |
| JNotify                                             | Stale (2012), JNI per-OS binaries                                      | Yes — only candidate with true rename callbacks                               | Native                                                         | Skip — rename events don't justify 2012-era JNI debt                                                                                                                     |

## External systems

| System                                   | Rename detection?                                                                      | Verdict                                                                               |
| ---------------------------------------- | -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| `inotifywait -m -r` (Linux sidecar)      | Yes for observed moves — kernel `MOVED_FROM`/`MOVED_TO` cookies pair into true renames | Use on Linux — simplest precise trigger; parse `--format` lines                       |
| `fswatch -r` (cross-platform sidecar)    | Partial (platform flags, no old→new pairing)                                           | Use for macOS dev / portable trigger                                                  |
| `watchexec --only-emit-events` (JSON)    | No by design (rename = create+remove hint)                                             | Consider only for structured JSON + ignore filters; otherwise leaner to use the above |
| `git status --porcelain -z` on media dir | Yes (similarity renames)                                                               | Skip — rehashes everything per scan, `.git` bloat, O(n²)-ish rename pass              |
| `rclone --track-renames`                 | Hash/modtime/size strategies, proven at scale                                          | Skip as detector; borrow the strategy matrix (hash → modtime+size → name)             |
| S3/MinIO bucket notifications            | No (rename = Copy+Delete)                                                              | Only if moving to object storage anyway                                               |
| fanotify tools / `systemd.path`          | Raw rename events but root-only / no payload at all                                    | Skip as detectors (`systemd.path` at most a "go scan" nudge on Linux)                 |

## Reference architecture: Syncthing

Syncthing's design validates the current approach and is the target shape: the watcher is only a hint (>128 events/dir →
rescan dir, >512 → full rescan), the periodic full scan is authoritative, a stat short-circuit skips rehashing unchanged
files, deletion is a positive check, and renames are matched by content hash against deleted index entries.

## Recommendation

1. Keep the matcher/DB/fileKey work — no alternative removes it.
2. Replace the fixed poll interval with event hints: `inotifywait` sidecar on Linux (cookie-paired renames come free),
   `fswatch` or methvin on macOS. With zero new binaries/deps, fs2 `Watcher` covers ~80% with manual recursion.
3. Keep the periodic full scan as the authority (missed events while down, NFS blindness, overflow).
