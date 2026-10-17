# Wedged sbt daemon after interrupted command

Running `sbt` uses a long-lived server daemon (`sbt-launch.jar`). Interrupting the client (Ctrl-C, killed tool call)
while a command is running can leave the server believing that command is still in flight. All subsequent `sbt`
invocations then queue behind the ghost command forever. This has happened more than once; the test itself was never at
fault.

## Symptoms

- Every `sbt ...` invocation prints `[info] waiting for: testSelected <truncated task name>...` (or `waiting for:`
  another task) and produces no further output for minutes.
- No compilation or test output appears, and no testcontainers (`testcontainers/ryuk`, test postgres) start.

## Detection

1. Find the daemon: `pgrep -f sbt-launch`.
2. Check whether it is working or wedged: `ps -o pid,%cpu,etime -p <pid>`.
   - Sustained ~0% CPU over minutes: wedged, not working. A healthy long run shows CPU usage plus compiler or test
     output.
3. Confirm nothing is actually held: no forked test JVM in `ps`, no test containers in `docker ps`.
4. Sanity-check duration against baseline: `ResourceDatabaseSpec` takes ~8s warm. No output for several minutes past
   compilation points at the server, not the test. Note the first run after source changes legitimately takes minutes
   (Scala 3 recompile of main and test sources plus container startup).

## Remedy

- `kill <sbt-server-pid>` (plain SIGTERM). The daemon is a stateless build process and restarts automatically on the
  next `sbt` invocation. `sbt shutdown` also works but requires a responsive server.
- Rerun the command against the fresh daemon. Do not keep retrying against the wedged one; clients only queue behind the
  ghost command.

## Prevention

- Run long `sbt` commands in the background with output redirected to a log file instead of foreground calls that get
  interrupted.
- Never kill the client of a running `sbt` command if it can be avoided. If an abort is unavoidable, verify server
  health afterwards with the CPU check above and kill the daemon if wedged.
- Allow generous timeouts for the first test run after source changes.
- Tried and rejected: `-Dsbt.server.autostart=false`. The sdkman `sbt` wrapper still requires a server connection and
  fails with `failed to connect to server`.
