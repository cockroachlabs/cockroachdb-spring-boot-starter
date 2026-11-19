# Architecture

This document describes the design philosophy behind the CockroachDB Spring Boot Starter, how the repository is organized, and the internals of the starter itself (packages, auto-configuration, advisor ordering). The rules for the getting-started guides live in [guides.md](guides.md), and the rules for how changes are scoped and tracked in [rules.md](rules.md).

## Design philosophy

The starter exists to make Spring applications work well on CockroachDB. Every design decision follows from that, and from the principles below. When a change seems to need an exception to one of them, settle that first, in the issue or pull request.

### Extend the Spring way, don't replace it

The starter follows Spring Framework and Spring Boot conventions as closely as it can, so that nothing surprises a developer who already knows Spring. It doesn't invent parallel mechanisms; it enhances the Spring way just enough to support CockroachDB. When designing an API, start from the Spring counterpart and extend it:

| Need | Spring convention | What the starter does |
|------|-------------------|-----------------------|
| Declarative transactions with retries | `@Transactional` | `@CockroachDBTransactional` is a composed `@Transactional` (meta-annotation plus `@AliasFor`); plain `@Transactional` methods get retries too |
| Programmatic transactions | `TransactionTemplate` / `TransactionOperations` | `CockroachDBTransactionTemplate extends TransactionTemplate`, `CockroachDBTransactionOperations extends TransactionOperations` |
| Turning a feature on | `@EnableTransactionManagement`, applied by Boot's auto-configuration | `@EnableCockroachDBTransactionManagement`, applied by the starter's auto-configuration, which backs off when the application declares it |
| Configuration | Boot `@ConfigurationProperties` | `cockroachdb.*` properties with defaults, overridable per method or per template |
| Hooking into behavior | AOP advisors, `TransactionExecutionListener`, `BeanPostProcessor` | The retry advisor wraps Spring's transaction advisor; the session region is set by a transaction listener |

Consequences:

- **Spring keeps doing Spring's job.** Transactions are started, committed and rolled back by Spring's transaction infrastructure, with every `@Transactional` attribute meaning what it always has. The starter adds what CockroachDB needs (40001 retries, session regions) around it, rather than reimplementing it.
- **Names say what the Spring counterpart is.** `CockroachDBTransactionTemplate`, not `CockroachDBTemplate`, which reads like a `JdbcTemplate` sibling.
- **Differences from Spring are deliberate and documented.** For example, `@CockroachDBTransactional` defaults to SERIALIZABLE isolation where `@Transactional` defaults to the database default. Each one is called out where the user meets it.

### Keep visibility as narrow as possible

Default to package-private, not `public`. A class, constructor, method or field is `public` only when users need it (it's part of the starter's API) or when another package in the starter needs it, and then only that member. IDEs and code generators tend to make everything `public`; don't accept that default. Every `public` type and member is something users can come to depend on, which makes it hard to change before 1.0 and harder after.

- Prefer a package-private class, with package-private constructors, for implementation pieces such as interceptors and helpers.
- When a type must be `public` because another package uses it, keep its other members package-private or `private`.
- Spring doesn't need `public` either: it can create beans from package-private classes and call package-private `@Bean` methods.
- Tests live in the same package as the code they test, so they don't need `public` access.

### Turn on only what's needed

Each feature area (transactions, metrics, CDC, AI, Continuum) has its own auto-configuration, gated by classpath conditions and `cockroachdb.*` properties. Transaction retries are on by default because every CockroachDB application needs them; heavier features such as CDC and AI are off until an application enables them.

### Run where Spring Boot runs

The published jar targets Java 17 and is tested on every supported JDK (17, 21 and 25). Nothing in the starter requires GraalVM, a particular container runtime or a CockroachDB Cloud account; native-image support is provided through runtime hints, not required.

### Documentation that can't go stale

User-facing behavior is documented in the [README](../README.md) and taught by executable getting-started guides that CI builds against every change (see [guides.md](guides.md)). A change that alters what users see updates both in the same pull request.

## Repository layout

```
cockroachdb-spring-boot-starter/
├── README.md              User-facing reference for the starter
├── AGENTS.md              Instructions for coding agents working in this repo
├── build.gradle           The starter (the published library)
├── src/                   Starter source and tests
├── docs/architecture.md   This document
├── docs/guides.md         Rules and structure for the getting-started guides
├── docs/rules.md          Rules for how changes are scoped, tracked and reviewed
├── docker/                Local multi-node CockroachDB clusters for manual testing
└── guides/                Executable getting-started guides
    ├── README.md          Guide index and the end-to-end process for writing them
    └── <slug>/            One directory per guide
```

The starter is the product. Everything in `guides/` exists to teach someone how to use it; see [guides.md](guides.md) for the rules every guide follows and [guides/README.md](../guides/README.md) for how to write one.

## Starter internals

`cockroachdb-spring-boot-starter` is a single Gradle module that uses Spring Boot auto-configuration to conditionally wire CockroachDB-specific beans. Each feature area has its own `@AutoConfiguration` class gated by properties, so applications only activate what they need.

### Module Layout

```
src/main/java/com/cockroachlabs/data/
├── Core Layer
│   ├── annotation/         Declarative annotations (@CockroachDBTransactional, @FollowerRead, etc.)
│   ├── aop/                AOP aspects that intercept annotated methods (@FollowerRead)
│   ├── transaction/        40001 retry advisor/interceptor, shared retry loop, session-region listener
│   ├── autoconfigure/      Spring Boot auto-configuration and properties binding
│   ├── template/           CockroachDBTransactionTemplate / CockroachDBTransactionOperations — programmatic retry API
│   ├── region/             RegionContext (ThreadLocal), RegionResolver (strategy)
│   ├── jpa/                JPA entity listeners (RegionalByRowListener)
│   └── metrics/            Micrometer metric bindings for transactions
│
├── CDC Layer
│   ├── cdc/annotation/     @CockroachDBCDC annotation
│   ├── cdc/handler/        Webhook controller, handler registry, method invoker
│   ├── cdc/factory/        Programmatic changefeed management (requires spring-jdbc)
│   ├── cdc/metrics/        Per-endpoint CDC metrics
│   ├── cdc/test/           CDCTestHarness, CDCEventBuilder
│   └── cdc/autoconfigure/  CDC auto-configuration classes
│
└── AI Layer
    ├── ai/annotation/      @VectorSearch, @SemanticSearch, @VectorColumn
    ├── ai/aop/             Vector/Semantic search aspects
    ├── ai/metrics/         AI metrics (search count, latency, results)
    └── ai/autoconfigure/   AI auto-configuration and properties
```

### Auto-Configuration Strategy

Each feature area registers its own auto-configuration class:

| Class | Gate | Purpose |
|-------|------|---------|
| `CockroachDBAutoConfiguration` | Always active | Transaction retry advisor, template, region listener and beans |
| `CockroachDBMetricsAutoConfiguration` | Micrometer on classpath | Transaction metrics |
| `CockroachDBCDCAutoConfiguration` | `cockroachdb.cdc.enabled=true` | CDC webhook endpoint registration |
| `CockroachDBCDCMetricsAutoConfiguration` | CDC + Micrometer | CDC metrics |
| `CockroachDBAIAutoConfiguration` | `cockroachdb.ai.enabled=true` | Vector search beans |
| `CockroachDBAIMetricsAutoConfiguration` | AI + Micrometer | AI metrics |

### Key Design Decisions

#### Retry outside the transaction boundary
CockroachDB requires a new transaction after a serialization error (SQLSTATE 40001); retrying within the same transaction is not supported. `CockroachDBTransactionAdvisor` therefore runs at `LOWEST_PRECEDENCE - 2`, outside Spring's own transaction advisor (`LOWEST_PRECEDENCE`), and re-enters the rest of the interceptor chain once per attempt through `ProxyMethodInvocation.invocableClone()`, so every attempt gets a fresh transaction from Spring's `TransactionInterceptor`. It retries only at the outermost transactional call: when a transaction is already active, the method runs once and the `40001` propagates to the call that owns the transaction. Like Spring's own advisor, it is registered by an `@Enable` annotation, `@EnableCockroachDBTransactionManagement` (with an `order` attribute like `@EnableTransactionManagement`'s), which the auto-configuration applies with its defaults when Spring's transaction advisor exists and the application hasn't declared it; the auto-configuration runs after Boot's `TransactionAutoConfiguration`. At startup the advisor fails fast if Spring's transaction advisor is missing or ordered at or before it.

#### Reuse Spring's transaction model instead of reimplementing it
`@CockroachDBTransactional` is a composed annotation, meta-annotated with `@Transactional` and redeclaring its attributes with `@AliasFor`, so Spring's transaction support handles propagation, isolation, timeouts, rollback rules, qualifiers and labels; the starter adds only retry settings and a region. The retry advisor matches through the same `TransactionAttributeSource` as Spring's transaction advisor, so plain `@Transactional` (on methods, classes or interfaces), composed annotations and `jakarta.transaction.Transactional` all get retries, unless `cockroachdb.retry.apply-to-transactional-methods=false`. `CockroachDBTransactionTemplate` extends `TransactionTemplate` and implements `CockroachDBTransactionOperations` (which extends `TransactionOperations`), mirroring Spring's template/operations split. The annotations and the template share one retry loop (`SerializationRetry`), so a fix to retry behavior lands in one place.

#### Session region at transaction begin
`SET crdb_region` must run inside the transaction, after it begins, which no advisor ordered around Spring's transaction advisor can guarantee. `CockroachDBRegions` is a `TransactionExecutionListener` that the starter registers with every `ConfigurableTransactionManager` bean. Retried methods and the template bind a scope on the current thread; when a transaction begins inside that scope, the listener runs `SET crdb_region` from the resolution chain below. `@FollowerRead` suppresses it, because `SET TRANSACTION AS OF SYSTEM TIME` must be a follower-read transaction's first statement.

#### Region resolution chain
Multi-region support uses a priority chain: annotation or template setting > ThreadLocal (`RegionContext`) > `RegionResolver` bean > `cockroachdb.region.default-region` > gateway region. This lets applications mix static routing (annotation), request-scoped routing (context/filter), and dynamic routing (GeoIP resolver).

#### CDC as AOP + Spring MVC
CDC handlers are discovered at startup via `@CockroachDBCDC` annotation scanning. The registry maps endpoint paths to handler methods. A single `CDCWebhookController` dispatches incoming webhook payloads to the correct handler, deserializing the JSON envelope into typed `CDCEvent<T>` objects using Jackson's `TypeReference` resolution.

#### Vector search as aspects
`@VectorSearch` and `@SemanticSearch` are AOP-intercepted. The aspect builds the SQL query (including `ORDER BY <vector_col> <=> $1` and optional filters), executes it via `JdbcTemplate`, and replaces the method's return value. This keeps the annotation declarative while the aspect handles SQL generation and parameter binding.

### Build

```bash
./gradlew build          # Compile + test
./gradlew publishToMavenLocal   # Install to ~/.m2
```

### Dependencies

- **Required:** Spring Boot Starter, Spring AOP, AspectJ, Spring TX, Spring JDBC
- **Optional:** Micrometer (metrics), Jackson (CDC JSON parsing), Spring Web (CDC webhook endpoint), JPA/Hibernate (entity annotations)

### Build constraints

These apply to the starter itself and shape what guides can assume ([guides.md](guides.md)):

- Built with GraalVM 25 and compiled with `--release 17`, so the published jar runs on any Java 17+ JVM.
- Tests run on JDK 17 by default; CI runs them on 17, 21 and 25.
- Integration tests use Testcontainers (`testcontainers-cockroachdb`) and need only a container runtime.
