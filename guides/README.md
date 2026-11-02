# Getting-Started Guides

This directory holds short, executable tutorials for the CockroachDB Spring Boot Starter. Each guide teaches one feature by having the reader build a small, working Spring Boot application, and each one is built and tested by CI on every change to the starter.

This page covers the whole process: how to follow a guide, how guides are built and tested, and how to write, review and maintain one. The rules every guide must meet are defined in [docs/architecture.md](../docs/architecture.md#getting-started-guides); this page restates them where they matter and explains how to apply them.

## Available guides

| Guide | You'll learn to | Starter feature |
|-------|-----------------|-----------------|
| [Retrying serialization conflicts with `@CockroachDB`](serialization-retry/README.md) | Keep money transfers succeeding when concurrent transactions conflict (SQLSTATE `40001`) | `@CockroachDB` |

Planned guides are listed in the [candidate backlog](../docs/architecture.md#candidate-guides).

## Following a guide

You need:

- JDK 17 or newer
- Docker or Podman, running
- A text editor or IDE

You don't need to install CockroachDB or Gradle. Every guide starts CockroachDB in a container through Testcontainers and ships its own Gradle wrapper.

Clone the repository, open the guide's `README.md`, and work in its `initial/` directory:

```bash
git clone https://github.com/cockroachlabs/cockroachdb-spring-boot-starter.git
cd cockroachdb-spring-boot-starter/guides/<slug>/initial
./gradlew build
```

The finished code is in `../complete/` if you get stuck. Both directories are standalone projects: you can copy either one anywhere and build it without the rest of this repository.

Two commands come up in every guide:

- `./gradlew bootTestRun` runs the application against a CockroachDB container.
- `./gradlew test` runs the tests against a CockroachDB container.

> **Using Podman?** Testcontainers looks for a Docker socket. Point it at Podman first:
>
> ```bash
> export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
> export TESTCONTAINERS_RYUK_DISABLED=true
> ```

## Principles

Every guide must meet all five. A guide that breaks one is not ready to merge.

1. **Executable.** Real code backs every guide, and CI builds and tests it. A starter change that breaks a guide fails the build.
2. **Self-contained.** A reader finishes the guide without opening another guide, the starter README, or external docs. Setup steps are repeated in every guide on purpose. Links elsewhere appear only under "Where to go next".
3. **Same Java versions as the starter.** Guides compile to Java 17 and run on JDK 17, 21 and 25. No language features or APIs newer than Java 17, and no GraalVM unless GraalVM is the topic.
4. **One clear focus.** The goal fits in one sentence. If a draft needs a second goal, split it into two guides.
5. **Lunch-break sized.** A developer new to the starter, but comfortable with Java and Spring Boot, finishes in 30 to 60 minutes, setup included. If a draft runs long, cut scope.

## How a guide is laid out

```
guides/<slug>/
├── README.md        The tutorial text
├── initial/         Starting point: a compiling project with dependencies and
│                    database wiring in place, and none of the code the reader writes
└── complete/        The finished application, including tests
```

`initial/` and `complete/` each contain:

- `settings.gradle`, with `rootProject.name` set to `<slug>-initial` or `<slug>-complete`
- `build.gradle`, depending on the starter by its published coordinates (`com.cockroachlabs:cockroachdb-spring-boot-starter:<version>`) from the GitHub Pages Maven repository, exactly as the starter README tells users to, and compiling with `options.release = 17`
- the Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`), on the same Gradle version as the root build
- a `TestcontainersConfiguration` class in the test source set that starts CockroachDB with `@ServiceConnection`
- a `Test<Name>Application` class in the test source set that runs the app against that container, so `./gradlew bootTestRun` works
- a context-loads test, so `./gradlew build` proves the project and the container work before the reader writes any code

No Lombok, and no other tool that needs an IDE plugin.

A guide that needs a multi-node or multi-region cluster provides that setup inside its own directory, not by pointing at `docker/` at the repository root.

## How guides are built and tested

The root build includes every guide as a [Gradle composite build](https://docs.gradle.org/current/userguide/composite_builds.html):

- `settings.gradle` finds every `guides/<slug>/` directory and includes its `initial/` and `complete/` projects as builds named `<slug>-initial` and `<slug>-complete`. A new guide is picked up automatically; there is nothing to register.
- `settings.gradle` also calls `includeBuild('.')`, so the guides' dependency on `com.cockroachlabs:cockroachdb-spring-boot-starter` resolves to **this checkout**, not the published release. A starter change that breaks a guide fails before it ships, while the guide's build files keep the public coordinates readers use.
- The `guides` task in `build.gradle` builds and tests every included guide, and `build` depends on it.

From the repository root:

```bash
./gradlew build                                # starter + every guide
./gradlew guides                               # only the guides
./gradlew :serialization-retry-complete:build  # one guide project
```

From a guide directory, the build runs against the **published** release, exactly as a reader runs it:

```bash
cd guides/serialization-retry/complete
./gradlew build
```

Guides have no Java toolchain of their own, so their tests run on the JVM that runs Gradle. To try a guide on another JDK, set `JAVA_HOME` before running Gradle. CI ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) makes the matrix JDK (17, 21, 25) the one Gradle runs on, so the starter and the guides are tested on the same JVM in each job.

## Writing a new guide

### 1. Pick the goal

Start from the [candidate backlog](../docs/architecture.md#candidate-guides), or propose a new entry there. Write the goal as one sentence naming the starter feature, for example "keep money transfers succeeding when concurrent transactions conflict, using `@CockroachDB`". If you can't, the guide's scope isn't settled yet.

[spring-projects/spring-data-examples](https://github.com/spring-projects/spring-data-examples) is a good source of small domains, but its projects are reference code, not tutorials. Rewrite rather than port.

### 2. Build `complete/` first

Create `guides/<slug>/complete/` as a standalone Spring Boot project following the layout above. The easiest start is to copy an existing guide's `complete/`, rename the package, `rootProject.name` and the application classes, and remove its feature code.

Write the finished application and its tests. The tests must prove the feature works, not just that the app starts. A good test fails if the starter feature is removed. For example, the `serialization-retry` test asserts that retries actually happened, so it can't pass just because no conflicts occurred.

Run `./gradlew :<slug>-complete:build` from the root until it passes.

### 3. Derive `initial/` from `complete/`

Copy `complete/` to `initial/`, set `rootProject.name` to `<slug>-initial`, and delete every file and every line the reader will write. What remains must still compile and pass its context-loads test. Keep files the reader only edits, such as `application.properties` or `Test<Name>Application`, in their starting state.

`initial/` must not already contain any file the guide tells the reader to create. Run `./gradlew :<slug>-initial:build`.

### 4. Write the tutorial

Write `guides/<slug>/README.md` in this order:

1. **What you'll build**: the one-sentence goal and a short description of the finished app.
2. **What you need**: JDK 17+, a container runtime, about N minutes. Include the Podman note.
3. **Get the starting project**: clone, `cd` into `initial/`, and show every file already there that matters to the guide.
4. **Steps**: numbered, each ending in something the reader can run or observe.
5. **Run it**: `./gradlew bootTestRun` and what the reader should see.
6. **Test it**: `./gradlew test` and what the test proves.
7. **Summary**: what the reader learned, in a few bullets.
8. **Where to go next** (optional): links to related guides or reference docs.

Steps 5 and 6 can fold into the numbered steps when the guide runs the app or tests more than once, as `serialization-retry` does.

Rules for the text:

- Show the whole file the first time it appears, so a reader can paste it.
- A step may show an intermediate version of a file, but the **last** version of each file the guide shows must match `complete/` exactly, character for character, including indentation and imports.
- When you show program output, say which parts vary from run to run.
- Explain why each step matters, not only what to type. The reader should come away understanding the database behavior behind the feature.

### 5. Walk through it as a reader

Before opening a PR, follow the guide from a clean clone, outside your working copy, typing (or pasting) every step into `initial/`. Time yourself. This catches missing steps, files that don't match, and output that has drifted.

Throw the walkthrough's changes away afterwards. Don't commit a filled-in `initial/`.

### 6. Link it and open a PR

- Add the guide to the table at the top of this page.
- Add it to the "Getting-started guides" list in the root [README.md](../README.md).
- Remove or update its row in the [candidate backlog](../docs/architecture.md#candidate-guides).

Run `./gradlew build` from the root, then open a PR.

## Review checklist

- [ ] The goal fits in one sentence and the guide stays on it.
- [ ] A reader can finish in 30 to 60 minutes, setup included.
- [ ] Nothing assumes knowledge from another guide or external docs.
- [ ] `initial/` and `complete/` each build on their own with `./gradlew build`.
- [ ] `initial/` compiles, passes its context-loads test, and contains none of the files the reader is told to create.
- [ ] `complete/` has a test that fails if the starter feature is removed.
- [ ] The last version of every file shown in the text matches `complete/` exactly.
- [ ] No Java newer than 17, no Lombok, no GraalVM unless that is the topic.
- [ ] The starter version in both `build.gradle` files is the current release.
- [ ] The guide is listed here and in the root README.

## Maintaining guides

- **Starter changes.** Because the root build substitutes this checkout for the published starter, a change that breaks a guide fails `./gradlew build`. Fix the guide's code **and** its README text in the same commit as the starter change.
- **Releases.** When a release is cut, bump the starter version in every guide's `initial/build.gradle` and `complete/build.gradle`, and in every README that shows a `build.gradle`, in the same change.
- **Spring Boot, Gradle and CockroachDB upgrades.** Guides pin their own Spring Boot plugin version, Gradle wrapper and CockroachDB container image. Keep them in step with the root build, and update the versions shown in the README text too.
- **Behavior changes.** If a starter change alters what a guide's reader sees, such as different output, exception types or defaults, update the explanation as well as the code, even when the build still passes.
