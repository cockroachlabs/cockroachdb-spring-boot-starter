# Using the CockroachDB Spring Boot Starter: notes for coding agents

This file is for coding agents (and the people working with them) in an **application that depends on** `com.cockroachlabs:cockroachdb-spring-boot-starter`. It says what the starter already does, which API to reach for, and what not to write by hand. The [README](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md) is the full reference; follow its links for details.

To use it, copy this file into your repository or reference it from your own `AGENTS.md` (or `CLAUDE.md`, `.cursorrules`, and so on). A copy that matches your version of the starter is inside its jar, at `META-INF/cockroachdb-spring-boot-starter/AGENTS.md`. The latest version, which follows the `main` branch, is published at <https://cockroachlabs.github.io/cockroachdb-spring-boot-starter/agents/AGENTS.md>. If the two disagree, trust the jar's copy for the version you have.

## Ground rules

- **The starter extends Spring; it doesn't replace it.** Write ordinary Spring and Spring Boot code (`@Transactional`, `TransactionTemplate`-style code, Spring Data repositories, `application.yml`). The starter adds what CockroachDB needs around it. If you find yourself writing infrastructure that Spring or the starter already has, stop and look it up in the README.
- **Java 17+, Spring Boot 4.x.**
- **The API is null-marked with JSpecify, the same mechanism Spring Framework 7 uses.** Every package is `@NullMarked`, so parameters and return values are non-null unless annotated `@Nullable`, and tools that understand Spring's nullness (IDEs, NullAway, Kotlin) understand the starter's too ([Null Safety](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#null-safety)).
- **The starter never changes the schema.** Manage it the way the application already does, for example with a migration tool such as Flyway or Liquibase. Where this guide says "migration", read "however your application changes its schema".

## Already handled: don't rebuild it

| You might be tempted to write | The starter already does it | Details |
|---|---|---|
| A retry loop for SQLSTATE `40001`, Spring Retry `@Retryable` around a service, or `catch` blocks that look for serialization failures | Every `@Transactional` method is retried on `40001` in a fresh transaction, with exponential backoff and jitter, at the outermost transactional call. No code changes needed. | [Transaction retries](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#1-transaction-retries-transactional-and-cockroachdbtransactional) |
| Programmatic transactions with Spring's plain `TransactionTemplate` or `TransactionOperations`, which don't retry | `CockroachDBTransactionTemplate` and `CockroachDBTransactionOperations`, the same API with retries (see [Switching existing code](#switching-existing-code)) | [`CockroachDBTransactionTemplate`](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#2-cockroachdbtransactiontemplate) |
| Code that works out which region a new row belongs to and fills its region column | The region is resolved when each transaction begins, and `RegionalByRowListener` fills the empty `@CrdbRegion` field of each `@RegionalByRow` entity persisted in it. | [Region resolution priority](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#region-resolution-priority), [How a row gets its region](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#how-a-row-gets-its-region) |
| A servlet filter that reads a region header into a `ThreadLocal` | `cockroachdb.region.resolver: header` (header name in `cockroachdb.region.header-name`) | [Auto-detect region from HTTP headers](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#auto-detect-region-from-http-headers) |
| `SET TRANSACTION AS OF SYSTEM TIME` / `follower_read_timestamp()` SQL | `@FollowerRead` | [Follower Reads](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#8-follower-reads) |
| A REST controller that receives changefeed webhook payloads | `@CockroachDBCDC(endpoint = "...")` on a bean method | [Define a handler](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#define-a-handler) |
| `CREATE CHANGEFEED` / `PAUSE JOB` / `SHOW CHANGEFEED JOBS` strings | `CockroachDBCDCFactory` with `ChangefeedSpec.forTable(...)` | [Enable CDC](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#enable-cdc), [`CockroachDBCDCFactory` Javadoc](https://cockroachlabs.github.io/cockroachdb-spring-boot-starter/apidocs/com/cockroachlabs/data/cdc/factory/CockroachDBCDCFactory.html) |
| `SET application_name` / `SET default_transaction_quality_of_service` | `@VirtualCluster` and `@Workload` | [vCluster — workload isolation](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#vcluster--workload-isolation) |
| Your own Micrometer counters for transactions, retries, CDC or vector search | Registered automatically when Actuator is on the classpath | [Metrics](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#5-metrics) |
| A `VectorStore` implementation or hand-written pgvector SQL | `CockroachDBVectorStore`, `@VectorSearch`, `@SemanticSearch`, `VectorSearchTemplate` | [Vector Search](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#9-vector-search-ai--rag), [Spring AI VectorStore](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#10-spring-ai-vectorstore-integration) |
| GraalVM reflection hints for the starter's types | Registered by the starter; add hints only for your own entities and DTOs | [GraalVM Native Image](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#graalvm-native-image) |

## Which API for which job

### Transactions

- **Default: plain `@Transactional`.** It already retries `40001`.
- **`@CockroachDBTransactional`** (`com.cockroachlabs.data.annotation`) when a method needs its own retry settings (`maxRetries`, `delay`, `multiplier`, `maxDelay`) or a fixed `region`. It is a composed `@Transactional`, so every `@Transactional` attribute works; its `isolation` defaults to SERIALIZABLE. Put one or the other on a method, never both.
- **`CockroachDBTransactionTemplate`** (`com.cockroachlabs.data.template`) for programmatic transactions. It extends Spring's `TransactionTemplate`. Inject `CockroachDBTransactionOperations` where code only runs transactions. For different settings, copy the shared bean (`new CockroachDBTransactionTemplate(shared)`) and configure the copy; don't change the shared bean. ([Template](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#2-cockroachdbtransactiontemplate))

Rules that follow from how retries work:

- **Every attempt reruns the whole method.** Keep non-database side effects (email, HTTP calls, messages) out of transactional methods, or make them idempotent.
- **Only the outermost transactional call retries.** A nested transactional call runs once and lets the `40001` propagate. Don't add retries to inner methods.
- **Don't catch and swallow exceptions inside a transactional method** to "handle" `40001`; the retry needs to see them.
- **Retries are the safety net, not the fix for contention.** Keep transactions short and touching few rows; CockroachDB's [Reduce transaction contention](https://docs.cockroachlabs.com/docs/stable/performance-best-practices-overview.md#reduce-transaction-contention) checklist says how.

#### Switching existing code

Spring's own `TransactionTemplate` runs transactions but doesn't retry `40001`; only the starter's template does. When you find programmatic transactions in the application, offer to switch them, using dependency injection:

- **Code that uses `TransactionTemplate`:** inject `CockroachDBTransactionTemplate` instead. It extends `TransactionTemplate`, so `execute(...)`, `executeWithoutResult(...)` and the setters work unchanged.
- **Code that uses `TransactionOperations`:** inject `CockroachDBTransactionOperations` instead. It extends `TransactionOperations`.
- **Code that builds its own, such as `new TransactionTemplate(transactionManager)` with settings:** inject the starter's `CockroachDBTransactionTemplate` and make a configured copy once, with `new CockroachDBTransactionTemplate(shared)` and the same setters. Don't construct one from scratch, and don't change the shared bean.
- **Isolation:** the starter's template defaults to SERIALIZABLE, where `TransactionTemplate` defaults to the database's default. On CockroachDB that default is SERIALIZABLE unless the cluster or session was set otherwise, so behavior usually doesn't change; keep any `setIsolationLevel(...)` the code already has.

```java
// Before
public OrderService(TransactionTemplate tx, OrderRepository orders) { ... }

// After: same calls, now retried on 40001
public OrderService(CockroachDBTransactionTemplate tx, OrderRepository orders) { ... }
```

### Multi-region

- **Which region a transaction uses** ([Multi-Region Support](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#3-multi-region-support)), highest priority first: `@CockroachDBTransactional(region = ...)` or the template's `inRegion(...)` / `setRegion(...)`; `RegionContext.set(...)` or `try (var scope = RegionContext.scoped(...))`; a `RegionResolver` bean; `cockroachdb.region.default-region`; otherwise the gateway node's region.
  - `RegionContext`, `RegionResolver` and `RegionalByRowListener` are in `com.cockroachlabs.data.multiregion`.
  - A custom `RegionResolver` bean needs `cockroachdb.region.resolver: bean`.
  - Prefer `RegionContext.scoped(...)` in try-with-resources over `set(...)`, so the thread-local is cleared.
  - The region only fills `@CrdbRegion` fields of `@RegionalByRow` entities. It doesn't route queries, and rows written with `JdbcTemplate` or plain JDBC get whatever your SQL puts in the region column (or its default, the gateway node's region); read `RegionContext.current()` and write it yourself there.
  - Region names must match `[a-z][a-z0-9-]*`; with `cockroachdb.region.validate: true`, they must also be in `cockroachdb.region.allowed`. Otherwise the call fails with `IllegalArgumentException` before the transaction begins.
- **Where an entity's rows live** ([JPA Entity Annotations](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#4-jpa-entity-annotations)), annotations in `com.cockroachlabs.data.annotation`:
  - `@GlobalTable` on the entity class for a `LOCALITY GLOBAL` table.
  - `@RegionalByRow` on the entity class for a `LOCALITY REGIONAL BY ROW` table, plus `@EntityListeners(RegionalByRowListener.class)`, plus a `String` field annotated `@CrdbRegion` mapped to the region column (`crdb_region`, or your column for `REGIONAL BY ROW AS <column>`). Without the listener, nothing fills the field.
  - Put these on the entity class itself; the listener doesn't read superclasses.
  - Create the localities in a migration (`ALTER DATABASE ... SET PRIMARY REGION`, `ADD REGION`, `CREATE TABLE ... LOCALITY ...`). The annotations don't change the schema.
- **The startup check** (`cockroachdb.multiregion.mode`: `none`, `warn` by default, `validate`) compares entities and tables in both directions and prints SQL that fixes each gap. Put that SQL in a migration; don't set `none` to silence it. A *contradiction* (annotation and table disagree) fails startup even in `warn`. Use `validate` in CI. ([Checking tables against the annotations](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#checking-tables-against-the-annotations))

### Follower reads

`@FollowerRead` (`com.cockroachlabs.data.annotation`) runs the method in its own read-only transaction at `follower_read_timestamp()`, or further back with `hoursAgo` / `minutesAgo` / `secondsAgo`. Use it for reads that tolerate a few seconds of staleness (catalogs, dashboards), never for checkout, payments, auth or read-your-writes. It needs no `@Transactional`; called inside a transaction it suspends that transaction and uses a second connection. ([Follower Reads](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#8-follower-reads))

### Change data capture (CDC)

Off until `cockroachdb.cdc.enabled: true`. ([CDC Webhook Handlers](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#7-cdc-webhook-handlers))

- **Consume a changefeed:** `@CockroachDBCDC(endpoint = "/orders")` on a bean method taking `CDCEvent<T>`, `List<CDCEvent<T>>`, `CDCEnvelope` or `T`. It is served at `cockroachdb.cdc.base-path` + endpoint (`/cdc/orders` by default). Set `cockroachdb.cdc.auth-token` so the endpoint isn't open.
- **Create and manage changefeeds from code:** inject `CockroachDBCDCFactory` (`create(ChangefeedSpec)`, `pause`, `resume`, `cancel`, `list`, `getJob`), building the spec with `ChangefeedSpec.forTable("orders").webhookUrl(...).withDiff()...build()`. Registered when `spring-jdbc` is present and `cockroachdb.cdc.factory.enabled` is true (the default).
- **Test handlers** with `CDCTestHarness` and `CDCEventBuilder` (see [Testing](#testing)).

### Vector search and Spring AI

Off until `cockroachdb.ai.enabled: true`. Use `CockroachDBVectorStore` as Spring AI's `VectorStore` (with `ChatClient` and `QuestionAnswerAdvisor`), `@VectorSearch` / `@SemanticSearch` for declarative similarity queries, or `VectorSearchTemplate` for programmatic ones. Create vector tables and indexes in migrations. ([Vector Search](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#9-vector-search-ai--rag), [Spring AI VectorStore](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#10-spring-ai-vectorstore-integration))

### Cockroach Continuum (vCluster, Aegis, MCP)

([Cockroach Continuum](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#cockroach-continuum))

- **Workload isolation:** `@VirtualCluster("analytics")` with `@Workload(Workload.Priority.LOW)` on a method, or `VirtualClusterContext.scoped(...)` around template calls. This tags the transaction; it doesn't route to another cluster.
- **Database operations as agent tools:** inject `CrdbAgent` (`listTools()`, `invoke(...)`), and mark your own methods `@AgentTool("name")`. Destructive tools stay hidden unless `cockroachdb.continuum.agent.allow-destructive=true`; leave it off unless a person decided otherwise.
- **MCP client:** `ContinuumMcpFactory.create(new McpClient.Config(...))`.
- `AegisClient` and `CloudClient` are **simulated** until `cockroachdb.continuum.aegis.endpoint` / `cockroachdb.continuum.cloud.api-key` are set. Don't treat simulated results as real cluster data.

## Configuration that matters

All under `cockroachdb.*`; defaults are sensible, so set only what you need. Full list: [Configuration](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#configuration).

| Property | Default | When to change it |
|---|---|---|
| `cockroachdb.retry.max-retries`, `delay`, `multiplier`, `max-delay`, `jitter` | `3`, `100`, `2.0`, `5000`, `50` | Tuning retries app-wide; per method, use `@CockroachDBTransactional` attributes instead |
| `cockroachdb.retry.apply-to-transactional-methods` | `true` | `false` only to retry `@CockroachDBTransactional` methods alone |
| `cockroachdb.region.resolver` | `context` | `header` for a request header, `static` for `default-region`, `bean` for your own `RegionResolver` |
| `cockroachdb.region.default-region` | empty (gateway region) | A fallback region for rows and transactions |
| `cockroachdb.region.validate`, `allowed` | `false`, `[]` | Reject region names that aren't in a known list |
| `cockroachdb.multiregion.mode` | `warn` | `validate` in CI |
| `cockroachdb.cdc.enabled` | `false` | To use CDC handlers, the factory or the test harness |
| `cockroachdb.ai.enabled` | `false` | To use vector search or `CockroachDBVectorStore` |

## Anti-patterns

- Writing a `40001` retry loop, or adding Spring Retry, around transactional code.
- Injecting Spring's plain `TransactionTemplate` or `TransactionOperations`, or building one with `new`, where `CockroachDBTransactionTemplate` or `CockroachDBTransactionOperations` would retry.
- Calling non-idempotent external services from inside a `@Transactional` method.
- Putting both `@Transactional` and `@CockroachDBTransactional` on one method.
- Changing settings on the shared `CockroachDBTransactionTemplate` bean at runtime.
- Filling `@CrdbRegion` fields by hand, or running `SET application_name` or `AS OF SYSTEM TIME` yourself.
- `RegionContext.set(...)` without clearing it, on pooled threads.
- Using `@FollowerRead` where the caller must see its own recent writes.
- Letting Hibernate (`ddl-auto`) or the application create localities, or setting `cockroachdb.multiregion.mode: none` to make warnings go away.
- `@RegionalByRow` without `@EntityListeners(RegionalByRowListener.class)`.
- Hand-written webhook controllers or `CREATE CHANGEFEED` strings.
- Testing CockroachDB behavior against H2 or PostgreSQL: serialization conflicts and localities won't show up, and the multi-region check skips non-CockroachDB databases.

## Testing

- **Run tests against real CockroachDB with Testcontainers.** Add `org.springframework.boot:spring-boot-testcontainers`, `org.testcontainers:testcontainers-cockroachdb` and `org.testcontainers:testcontainers-junit-jupiter` as test dependencies, and declare the container as a `@ServiceConnection` bean:

  ```java
  @TestConfiguration(proxyBeanMethods = false)
  class TestcontainersConfiguration {

      @Bean
      @ServiceConnection
      CockroachContainer cockroach() {
          return new CockroachContainer("cockroachdb/cockroach:v26.3.3");
      }
  }
  ```

  Then `@SpringBootTest` plus `@Import(TestcontainersConfiguration.class)`. The [serialization-retry guide](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/guides/serialization-retry/README.md) shows this end to end.
- **Exercise retries under contention** with `CockroachDBConcurrencyRunner.run(threads, iterations, task)` (`com.cockroachlabs.data.test`). ([Concurrency Testing](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/blob/main/README.md#6-concurrency-testing))
- **Test CDC handlers without a changefeed:** inject `CDCTestHarness` (`simulateInsert`, `simulateUpdate`, `simulateDelete`, `send`) and build events with `CDCEventBuilder` (both in `com.cockroachlabs.data.cdc.test`). Needs Jackson on the classpath and `cockroachdb.cdc.enabled: true` in the test configuration.
- **Run the multi-region check in CI** with `cockroachdb.multiregion.mode: validate` against a multi-region cluster (for example `cockroach demo --global --nodes 9`).

## Learn CockroachDB fundamentals

The starter handles the Spring side; these CockroachDB docs explain the database underneath. The links below go to the Markdown version of each page, which is easier for an agent to read; drop the `.md` for the HTML page. Every docs page has one, and <https://docs.cockroachlabs.com/llms.txt> indexes the whole documentation site.

**Start here**

- [Developer Basics](https://docs.cockroachlabs.com/docs/stable/developer-basics.md): what's different about building on CockroachDB.
- [PostgreSQL Compatibility](https://docs.cockroachlabs.com/docs/stable/postgresql-compatibility.md): CockroachDB speaks the PostgreSQL wire protocol (use the PostgreSQL JDBC driver), with some differences.
- [Connection Pooling](https://docs.cockroachlabs.com/docs/stable/connection-pooling.md): sizing HikariCP, Spring Boot's default pool.
- [Common Errors and Solutions](https://docs.cockroachlabs.com/docs/stable/common-errors.md)

**Transactions and contention**

- [Transactions](https://docs.cockroachlabs.com/docs/stable/transactions.md): SERIALIZABLE by default, and why clients see retry errors.
- [Transaction Retry Error Reference](https://docs.cockroachlabs.com/docs/stable/transaction-retry-error-reference.md): what each `40001` (`RETRY_SERIALIZABLE`, `RETRY_WRITE_TOO_OLD`, ...) means and how to make it rarer. The starter retries them; this tells you why they happen.
- [Reduce transaction contention](https://docs.cockroachlabs.com/docs/stable/performance-best-practices-overview.md#reduce-transaction-contention): the checklist. Keep transactions small, limit the rows they touch, use `SELECT ... FOR UPDATE` for read-then-write, `UPSERT` whole rows, use historical reads where staleness is fine.
- [Understand Hotspots](https://docs.cockroachlabs.com/docs/stable/understand-hotspots.md): ranges that take far more traffic than others, and how to spread it.
- [Performance Tuning Recipes](https://docs.cockroachlabs.com/docs/stable/performance-recipes.md): symptoms and fixes, contention included.
- [Monitor and Analyze Transaction Contention](https://docs.cockroachlabs.com/docs/stable/monitor-and-analyze-transaction-contention.md)
- [`SELECT FOR UPDATE`](https://docs.cockroachlabs.com/docs/stable/select-for-update.md)
- [Read Committed Transactions](https://docs.cockroachlabs.com/docs/stable/read-committed.md): an alternative isolation level, with trade-offs. Don't switch to it just to avoid retries.

**Schema design**

- [Database Schemas](https://docs.cockroachlabs.com/docs/stable/schema-design-overview.md): how to design and change schemas, including why to change them with a migration tool rather than through the ORM.
- [Unique ID best practices](https://docs.cockroachlabs.com/docs/stable/performance-best-practices-overview.md#unique-id-best-practices): prefer `UUID` keys (`gen_random_uuid()`) over sequences or auto-increment, which create hotspots.
- [`UUID`](https://docs.cockroachlabs.com/docs/stable/uuid.md) and [Hash-sharded Indexes](https://docs.cockroachlabs.com/docs/stable/hash-sharded-indexes.md), for when an index must be on a sequential column.
- [Online Schema Changes](https://docs.cockroachlabs.com/docs/stable/online-schema-changes.md): schema changes run in the background; what that means for migrations.

**Multi-region**

- [Multi-Region Capabilities Overview](https://docs.cockroachlabs.com/docs/stable/multiregion-overview.md)
- [Table Localities](https://docs.cockroachlabs.com/docs/stable/table-localities.md): `REGIONAL BY TABLE`, `REGIONAL BY ROW` and `GLOBAL`, which `@RegionalByRow` and `@GlobalTable` map to.
- [How to Choose a Multi-Region Configuration](https://docs.cockroachlabs.com/docs/stable/choosing-a-multi-region-configuration.md)
- [Follower Reads](https://docs.cockroachlabs.com/docs/stable/follower-reads.md) and [`AS OF SYSTEM TIME`](https://docs.cockroachlabs.com/docs/stable/as-of-system-time.md), behind `@FollowerRead`.

**Change data capture**

- [`CREATE CHANGEFEED`](https://docs.cockroachlabs.com/docs/stable/create-changefeed.md) and [Changefeed Sinks](https://docs.cockroachlabs.com/docs/stable/changefeed-sinks.md) (the webhook sink is what `@CockroachDBCDC` receives).

**Vector search**

- [`VECTOR`](https://docs.cockroachlabs.com/docs/stable/vector.md) and [Vector Indexes](https://docs.cockroachlabs.com/docs/stable/vector-indexes.md)
