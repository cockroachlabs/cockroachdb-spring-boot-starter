# Concurrent Ideas Board

A standalone Spring Boot demo for the **CockroachDB Spring Boot Starter**. It shows
the starter's hero feature — the `@CockroachDB` annotation, which automatically
retries `40001` serialization conflicts with exponential backoff — proven under
real contention.

## What it shows

Fifty threads race to upvote the *same* idea. Each upvote is a read-modify-write
(`findById` → `votes + 1` → `save`) under CockroachDB's default `SERIALIZABLE`
isolation, so the threads genuinely conflict.

- `upvoteWithoutRetry(...)` is annotated only `@Transactional`. Conflicting
  transactions abort with `40001` and the updates are lost — the final count is
  **less than 50**, with failures reported.
- `upvote(...)` is annotated `@CockroachDB`. Aborted transactions are retried
  automatically, so the final count is **exactly 50** with **zero failures**.

The app runs both, back to back, and prints a `CockroachDBConcurrencyRunner.Result`
plus the final vote count for each so the contrast is obvious.

## Prerequisites

- JDK 17+
- A local single-node CockroachDB cluster and an `ideas` database:

  ```sh
  cockroach start-single-node --insecure --listen-addr=localhost:26257
  cockroach sql --insecure -e 'CREATE DATABASE IF NOT EXISTS ideas;'
  ```

  The table is created on startup from `src/main/resources/schema.sql`.

## How to run

```sh
./gradlew bootRun    # or: gradle bootRun
```

The example compiles against the published starter `0.1.2`, resolved from the
anonymous GitHub Pages Maven feed
(`https://cockroachlabs.github.io/cockroachdb-spring-boot-starter/maven/`) — no
credentials needed. It needs a running cluster to execute; without one, startup
fails at the datasource connection.
