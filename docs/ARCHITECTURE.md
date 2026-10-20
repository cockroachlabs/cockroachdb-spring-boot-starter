# Architecture

## Overview

`cockroachdb-spring-boot-starter` is a single Gradle module that uses Spring Boot auto-configuration to conditionally wire CockroachDB-specific beans. Each feature area has its own `@AutoConfiguration` class gated by properties, so applications only activate what they need.

## Module Layout

```
src/main/java/com/cockroachlabs/data/
├── Core Layer
│   ├── annotation/         Declarative annotations (@CockroachDB, @FollowerRead, etc.)
│   ├── aop/                AOP aspects that intercept annotated methods
│   ├── autoconfigure/      Spring Boot auto-configuration and properties binding
│   ├── template/           CockroachDBTemplate — programmatic retry API
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

## Auto-Configuration Strategy

Each feature area registers its own auto-configuration class:

| Class | Gate | Purpose |
|-------|------|---------|
| `CockroachDBAutoConfiguration` | Always active | Core retry, template, region beans |
| `CockroachDBMetricsAutoConfiguration` | Micrometer on classpath | Transaction metrics |
| `CockroachDBCDCAutoConfiguration` | `cockroachdb.cdc.enabled=true` | CDC webhook endpoint registration |
| `CockroachDBCDCMetricsAutoConfiguration` | CDC + Micrometer | CDC metrics |
| `CockroachDBAIAutoConfiguration` | `cockroachdb.ai.enabled=true` | Vector search beans |
| `CockroachDBAIMetricsAutoConfiguration` | AI + Micrometer | AI metrics |

## Key Design Decisions

### Retry outside the transaction boundary
The `@CockroachDB` aspect runs at `@Order(LOWEST_PRECEDENCE - 2)` — outside Spring's transaction advisor. Each retry creates a fresh `TransactionTemplate.execute()` call. CockroachDB requires a new transaction after a serialization error (SQLSTATE 40001); retrying within the same transaction is not supported.

### Region resolution chain
Multi-region support uses a priority chain: annotation > ThreadLocal (`RegionContext`) > `RegionResolver` bean > default config > gateway region. This lets applications mix static routing (annotation), request-scoped routing (context/filter), and dynamic routing (GeoIP resolver).

### CDC as AOP + Spring MVC
CDC handlers are discovered at startup via `@CockroachDBCDC` annotation scanning. The registry maps endpoint paths to handler methods. A single `CDCWebhookController` dispatches incoming webhook payloads to the correct handler, deserializing the JSON envelope into typed `CDCEvent<T>` objects using Jackson's `TypeReference` resolution.

### Vector search as aspects
`@VectorSearch` and `@SemanticSearch` are AOP-intercepted. The aspect builds the SQL query (including `ORDER BY <vector_col> <=> $1` and optional filters), executes it via `JdbcTemplate`, and replaces the method's return value. This keeps the annotation declarative while the aspect handles SQL generation and parameter binding.

## Build

```bash
./gradlew build          # Compile + test
./gradlew publishToMavenLocal   # Install to ~/.m2
```

## Dependencies

- **Required:** Spring Boot Starter, Spring AOP, AspectJ, Spring TX, Spring JDBC
- **Optional:** Micrometer (metrics), Jackson (CDC JSON parsing), Spring Web (CDC webhook endpoint), JPA/Hibernate (entity annotations)
