# Getting-Started Guides: Rules and Structure

The `guides/` directory holds a set of short, executable tutorials. Each one teaches a single feature of the starter by having the reader build a small, working Spring Boot application. The starter is the product; the guides exist to teach someone how to use it.

This document defines the rules and structure every guide must follow: the principles, the directory layout, how CockroachDB runs, the outline of the tutorial text, how the root build tests guides, and the backlog of guides to write. [guides/README.md](../guides/README.md) is the companion how-to: the list of available guides, how to follow one, the step-by-step process for writing a new guide, the review checklist, and maintenance tasks. When the two differ, this document wins; change both in the same commit.

Guides also follow the project's design philosophy in [architecture.md](architecture.md#design-philosophy): they teach the Spring way, with the starter's CockroachDB support on top.

## Principles

Every guide must satisfy all of the following. A guide that breaks one of them is not ready to merge.

1. **Executable.** The guide is backed by real code that CI builds and tests on every change. If the starter's API changes in a way that breaks a guide, the build fails. A guide is never just prose.
2. **Self-contained.** A reader can start at the top of any guide and finish it without stopping to read another guide, the README, or external docs. Everything needed, from setting up the project to starting CockroachDB, is in the guide itself. Repeating setup steps across guides is deliberate: duplication is cheaper than forcing a reader to jump between documents. Links to other material are allowed only as optional "where to go next" pointers at the end.
3. **Same Java versions as the starter.** Guides compile to Java 17 and run on every JVM the starter supports (currently 17, 21 and 25). They must not use language features or APIs newer than Java 17, and they must not require GraalVM unless GraalVM is the topic of the guide.
4. **One clear focus.** Each guide has a single goal that fits in one sentence, for example "keep money transfers succeeding when concurrent transactions conflict, using the CockroachDB starter's transaction retries". Features outside that goal appear only where the goal needs them. If a guide starts needing a second goal, split it into two guides.
5. **Lunch-break sized.** A developer who is new to the starter, but comfortable with Java and Spring Boot, can finish the guide in **30 to 60 minutes**, including setup. If a draft runs longer, cut scope rather than speeding through steps.

## Guide layout

`guides/README.md` indexes the guides and describes the authoring process. Each guide is a standalone directory next to it:

```
guides/<slug>/
├── README.md        The tutorial text
├── initial/         Starting point: a compiling Spring Boot project with
│                    dependencies and database wiring in place, no feature code
└── complete/        The finished application, including tests
```

- `<slug>` is short, lowercase and hyphenated, and names the feature, for example `serialization-retry` or `follower-reads`.
- `initial/` and `complete/` are each a standalone Gradle build with their own `settings.gradle`, `build.gradle` and Gradle wrapper. A reader can copy either directory anywhere and run `./gradlew build` with nothing else from this repository.
- Guides depend on the starter through its **published coordinates** (`com.cockroachlabs:cockroachdb-spring-boot-starter:<version>`) and the GitHub Pages Maven repository, exactly as the README tells users to. Readers see the same setup they would use in their own projects.
- `initial/` must not already contain any file the tutorial tells the reader to create. It compiles and passes a context-loads test as is.
- No Lombok, and no other tool that needs an IDE plugin. What the reader sees in the guide is the code that compiles.
- Every guide is listed in [guides/README.md](../guides/README.md) and in the root [README.md](../README.md).

## Running CockroachDB inside a guide

The only prerequisites a guide may assume are a JDK (17 or newer), a container runtime (Docker or Podman), and a text editor or IDE.

Guides start CockroachDB through Testcontainers with Spring Boot's `@ServiceConnection`:

- Tests use the container automatically.
- The application is run with `./gradlew bootTestRun`, which starts the same container through a `TestcontainersConfiguration` class in the test source set. The reader runs one command and never configures a JDBC URL.

A guide that needs a multi-node or multi-region cluster provides that setup itself, inside the guide directory, rather than pointing at `docker/` at the repository root.

## Writing the tutorial text

Each guide's `README.md` follows the same outline so readers know what to expect:

1. **What you'll build**: the one-sentence goal and a short description of the finished app.
2. **What you need**: JDK 17+, a container runtime, about N minutes.
3. **Start from `initial/`**: or create the project from scratch, with every file shown.
4. **Steps**: numbered, each one ending in something the reader can run or observe.
5. **Run it**: `./gradlew bootTestRun` and what the reader should see.
6. **Test it**: `./gradlew test` and what the test proves.
7. **Summary**: what the reader learned, in a few bullets.
8. **Where to go next** (optional): links to related guides or reference docs.

Code blocks in the text must match the code in `initial/` and `complete/`. A step may show an intermediate version of a file (for example, the code before the feature is added), but the last version of each file the guide shows must match `complete/` exactly. When code changes, update the text in the same commit.

## Building guides with the starter

The root Gradle build includes every guide as a [composite build](https://docs.gradle.org/current/userguide/composite_builds.html), so `./gradlew build` at the repository root builds and tests the starter **and** every guide's `initial/` and `complete/` projects. `./gradlew guides` builds only the guides.

- `settings.gradle` finds each `guides/<slug>/` directory and includes its `initial/` and `complete/` builds, named `<slug>-initial` and `<slug>-complete`. Each guide project sets the same name as `rootProject.name` in its own `settings.gradle`, so IDEs such as IntelliJ IDEA show the two apart.
- `settings.gradle` also calls `includeBuild('.')`. That makes the root project available for dependency substitution, so the guides' `com.cockroachlabs:cockroachdb-spring-boot-starter` dependency resolves to this checkout instead of the published release. A breaking change to the starter fails the build before it ships, while guide build files keep the public coordinates readers use.
- Guides have no Java toolchain of their own, so their tests run on the JVM that runs Gradle. CI (`.github/workflows/ci.yml`) makes the matrix JDK (17, 21, 25) the one Gradle runs on, so the starter and the guides are tested on the same JVM in each job.
- A guide still builds on its own from its directory (`cd guides/<slug>/complete && ./gradlew build`), against the published release, exactly as a reader runs it.

When a release is cut, the starter version in every guide's `build.gradle` (and in any tutorial text that shows it) is bumped to the new release in the same change. See [Maintaining guides](../guides/README.md#maintaining-guides) for the other upkeep tasks.

## Candidate guides

[spring-projects/spring-data-examples](https://github.com/spring-projects/spring-data-examples) is the main source of ideas: it has small, focused projects with a familiar domain for each concept. Its examples are reference projects rather than tutorials, though, so each one has to be rewritten to meet the principles above (tutorial text, self-contained setup, one focus).

The list below is a backlog, not a commitment. Order roughly reflects priority. When a guide ships, remove its row here and add it to [guides/README.md](../guides/README.md#available-guides).

| Slug | Goal | Starter feature | spring-data-examples inspiration |
|------|------|-----------------|----------------------------------|
| `programmatic-retry` | Run retryable transactions with plain JDBC | `CockroachDBTransactionTemplate` | `jdbc/basics` |
| `concurrency-testing` | Prove a service survives contention in a test | `CockroachDBConcurrencyRunner` | none |
| `transaction-metrics` | Watch retries and failures in Actuator metrics | Micrometer metrics | `mongodb/repository-metrics` |
| `follower-reads` | Serve a read-heavy endpoint from historical data | `@FollowerRead` | `jpa/example` (read-only queries) |
| `computed-columns` | Map a stored computed column in JPA | `@ComputedColumn` | none |
| `cdc-webhooks` | React to row changes with a changefeed webhook | `@CockroachDBCDC`, `CDCTestHarness` | `redis/listener` (event-handler style) |
| `vector-search` | Find similar items with a vector query | `@VectorSearch`, `VectorSearchTemplate` | `jpa/vector-search` |
| `spring-ai-rag` | Answer questions over your own documents | `CockroachDBVectorStore` | `jpa/vector-search` |
| `regional-by-row` | Pin each user's rows to their home region | `@RegionalByRow`, `RegionContext` | `jpa/multitenant` |
| `native-image` | Ship a starter-based app as a GraalVM native binary | `CockroachDBRuntimeHints` | `jdbc/graalvm-native` |

Constraints for specific candidates:

- **`spring-ai-rag` and `vector-search`** must not require a paid API key. Use deterministic, precomputed embeddings or a model that runs locally, so the guide works offline and in CI.
- **`regional-by-row`** needs a multi-region cluster. It must still fit in 60 minutes on a laptop, for example by using a single-machine demo cluster with simulated localities.
- **`native-image`** is the only guide allowed to require GraalVM. It still compiles to Java 17 and states the GraalVM requirement under "What you need".

## Build constraints guides can rely on

Guides run on whatever the starter supports. See [Build constraints](architecture.md#build-constraints) in the architecture document: the starter compiles to Java 17 bytecode, is tested on JDK 17, 21 and 25, and its integration tests, like the guides', need only a container runtime.
