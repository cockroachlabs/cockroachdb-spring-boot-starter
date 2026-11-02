# Agent Instructions

Guidance for coding agents working in this repository.

## Read first

- [docs/architecture.md](docs/architecture.md): repository layout, the rules for getting-started guides under `guides/`, and starter internals (packages, auto-configuration, aspect ordering). Any new or changed guide must meet every principle listed there (executable, self-contained, Java 17+, one focus, 30 to 60 minutes).
- [guides/README.md](guides/README.md): the step-by-step process for writing, reviewing and maintaining a guide, including the review checklist.
- [README.md](README.md): the user-facing reference. Keep it in sync with API changes.

## Build and test

```bash
./gradlew build                  # starter + every guide under guides/
./gradlew build -PtestJvm=21     # starter tests on another supported JVM (17, 21, 25)
./gradlew guides                 # only the guides
```

- Starter tests run on the JVM chosen by `-PtestJvm` (default 17). Guide tests run on the JVM running Gradle; set `JAVA_HOME` to test them on another version.

- The build uses GraalVM 25 and compiles with `--release 17`. Do not use Java APIs or language features newer than Java 17.
- Integration tests use Testcontainers and need a running container runtime (Docker or Podman).
