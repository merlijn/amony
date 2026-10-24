# Module systems and architecture direction

This note records the options considered after the backend was split into sbt modules. It is about direction, not a
committed refactor.

## Current position

The build now has coarse sbt modules:

- `app` — composition root and tests
- `platform` — shared infrastructure (`platform`, `subprocess`, `messagebus`, `database`)
- `modules` — `auth`, `resources`, `search`

The package names match the module paths (`nl.amony.platform.*`, `nl.amony.modules.*`). `dependsOn` gives compile-time
direction, but at runtime everything is still on one flat classpath. A package that is "internal" by convention is
therefore accessible to every other module. Build modules do not provide runtime encapsulation.

## JVM module systems

### JPMS (Project Jigsaw)

The JVM has a built-in module system since Java 9. A `module-info.java` can declare dependencies and control which
packages are exported:

```java
module nl.amony.resources {
    requires nl.amony.platform;
    exports nl.amony.modules.resources.api;
    exports nl.amony.modules.resources.http to nl.amony.app;
    opens nl.amony.modules.resources.dal to skunk;
}
```

- `exports` is the equivalent of OSGi's exported packages.
- `exports ... to ...` is a qualified export to specific modules.
- `requires` expresses module dependencies.
- `opens` allows reflective access, which many libraries need.
- On the module path, non-exported packages are inaccessible at compile time and runtime.

It is not a good first step for this codebase:

- Scala 3 does not generate `module-info`; it would require Java descriptors mixed into the Scala build.
- Many dependencies would be automatic modules, which export everything, so enforcement would be partial.
- Reflection-heavy libraries (cats-effect, http4s, circe, skunk, Liquibase, Solr) usually need `opens` or `--add-opens`.
- The app test sources live in packages such as `nl.amony.platform.*`, which would split packages with the `platform`
  module and require `--patch-module` handling.
- JPMS gives static module resolution, not OSGi's dynamic lifecycle, versioned bundles, or installable plugins.

### OSGi

OSGi still exists (Felix, Equinox) and provides versioned bundles, dynamic lifecycle, and a service registry. It is
mostly useful for plugin platforms or dynamically updated applications. For a single deployable server it adds a lot of
machinery for little benefit.

### Practical enforcement

The pragmatic approach is to keep the sbt module graph and enforce architectural rules with ArchUnit:

- `platform` must not depend on `modules.*`.
- `modules.*` must not depend on `app`.
- Feature modules must not depend on each other except through explicit `*.api` packages.
- `nl.amony.modules.(*)` slices must be free of cycles.
- `api` packages must not depend on `http`, `dal`, `local`, or `solr` adapters.

ArchUnit reads bytecode and runs as a normal test, so it works with Scala and sbt without JPMS. Start with
`FreezingArchRule` so existing violations do not block the build, then burn the baseline down. `jdeps` is useful for
one-off analysis but does not enforce rules in CI.

Scala-level tools help as well: `private[platform]` and `private[resources]` restrict access within a package tree, and
`export` can define a narrow public façade. These are compile-time only; they do not survive the flat runtime classpath.

## Hexagonal architecture

Hexagonal architecture (ports and adapters) is not an alternative to modules. It is a rule about dependency direction:

- The domain/application core defines ports (interfaces) in its own terms.
- Adapters implement those ports.
- The composition root wires adapters to the core.
- Dependencies point inward: adapters depend on ports, not the other way around.

A consumer-owned port is the key idea. If the `resources` module needs search behaviour, `resources` defines the
interface it needs; `search` implements it. `resources` does not import the search implementation.

### The media example

`LocalResourceOperations` currently constructs `FFMpeg` and `ImageMagick` directly and maps domain `VideoContainer` to
the ffmpeg-specific `ContainerFormat`. The domain therefore knows about an adapter detail.

A port can invert that:

```scala
trait MediaOperations[F[_]]:
  def probe(path: Path): F[MediaInfo]
  def thumbnail(path: Path, spec: ThumbnailSpec): F[Path]
  def transcode(path: Path, spec: TranscodeSpec): F[Path]
  def makeStreamable(path: Path): F[Path]
```

The adapter maps `VideoContainer` and `ThumbnailSpec` to `-f mp4`, `-movflags +faststart`, and the other ffmpeg details.
`resources` only sees the port.

There are two valid shapes:

**A. A neutral media API module**

```text
app
 ├── subprocess ──→ media-api (ports)
 └── resources ─→ media-api
```

`subprocess` depends on `media-api`, not on `resources`. This keeps the ffmpeg adapter reusable and keeps the resources
feature out of it.

**B. A thin adapter inside the feature**

```text
resources.api       # MediaOperations port, in domain terms
resources.local     # FfmpegMediaOperations adapter, uses subprocess
subprocess          # low-level ffmpeg/ImageMagick wrappers, no domain knowledge
```

Here `resources.local` implements the port using `subprocess`; `subprocess` stays a generic toolchain. For Amony this is
probably the cleaner option, because `subprocess` really is a technical library rather than a resource-specific adapter.

The important rule in both cases: the port uses domain language, not ffmpeg language. `ContainerFormat` is an adapter
detail and should not leak into `resources.api`.

### What to invert and what not to

Invert volatile or external boundaries:

- media processing (ffmpeg, ImageMagick)
- search (Solr)
- storage (local filesystem)
- identity provider calls
- possibly the resource repository/database

Do not invert pure utilities such as hashing, filesystem helpers, or concurrency primitives. A port in front of `Base32`
or `Mutex` adds indirection without removing a real dependency.

The benefits are testability (fake adapters instead of ffmpeg or Solr), swappability, and a domain that is independent
of delivery mechanisms. The cost is more interfaces and wiring, so apply it selectively.

### Hexagonal and modules

The current layout is a modular monolith grouped by technical role (`platform`, `modules`, `app`). Hexagonal
architecture is complementary: keep the feature modules and, inside each, separate:

```text
modules/resources/
  api/        domain model + ports
  core/       use cases
  adapters/   local, dal, http
```

`app` remains the composition root. Avoid a single global adapter module for every feature; that tends to become a hub
that depends on everything.

## Suggested sequence

1. Add ArchUnit rules for the boundaries that already exist.
2. Introduce a media port at the `resources`/`subprocess` seam.
3. Review search, storage, and persistence for the same pattern where the coupling is real.
4. Keep delivery concerns (HTTP DTOs, serialization, OpenAPI schemas) out of `api` and `core`.
