# Agent Instructions

Guidance for coding agents working in this repository.

## Read first

- [docs/architecture.md](docs/architecture.md): the project's design philosophy (above all, follow Spring and Spring Boot conventions and extend the Spring way rather than replace it), repository layout, and starter internals (packages, auto-configuration, advisor ordering). Every change must fit the design philosophy.
- [docs/rules.md](docs/rules.md): how changes are scoped and approved. A project-wide change gets its own tracking issue and pull request; reformatting is never mixed with functional changes; a pull request from one maintainer is approved once the other maintainer approves it.
- [docs/guides.md](docs/guides.md): the rules for getting-started guides under `guides/`. Any new or changed guide must meet every principle listed there (executable, self-contained, Java 17+, one focus, 30 to 60 minutes).
- [guides/README.md](guides/README.md): the step-by-step process for writing, reviewing and maintaining a guide, including the review checklist.
- [README.md](README.md): the user-facing reference. Keep it in sync with API changes.

## Build and test

```bash
./gradlew build                  # starter + every guide under guides/ + every example under examples/
./gradlew build -PtestJvm=21     # starter tests on another supported JVM (17, 21, 25)
./gradlew guides                 # only the guides
./gradlew examples               # only the example apps
```

- Starter tests run on the JVM chosen by `-PtestJvm` (default 17). Guide tests run on the JVM running Gradle; set `JAVA_HOME` to test them on another version. Example apps use the toolchain their own build pins.
- Guides and examples resolve the starter from this checkout (composite build), so an API change that breaks one fails the build. Update them in the same pull request.

- The build uses GraalVM 25 and compiles with `--release 17`. Do not use Java APIs or language features newer than Java 17.
- Integration tests use Testcontainers and need a running container runtime (Docker or Podman).
