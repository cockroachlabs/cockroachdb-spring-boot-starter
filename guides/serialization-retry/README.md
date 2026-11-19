# Retrying Serialization Conflicts in `@Transactional` Code

## What you'll build

You'll build a small Spring Boot app that moves money between two bank accounts, then hit it with 200 transfers running at the same time. Without retries, most of them fail with a CockroachDB serialization error (SQLSTATE `40001`). Then you'll let the CockroachDB Spring Boot Starter retry your ordinary `@Transactional` method, and tune it with the starter's `@CockroachDBTransactional`, until every one of them succeeds.

**Goal:** keep money transfers succeeding when concurrent transactions conflict, using the CockroachDB starter's transaction retries.

## What you need

- About 35 minutes
- JDK 17 or newer (`java -version` to check)
- Docker or Podman, running (the guide starts CockroachDB in a container for you)
- A text editor or IDE

You don't need to install CockroachDB or Gradle.

> **Using Podman?** Testcontainers looks for a Docker socket. Point it at Podman before running any `./gradlew` command:
>
> ```bash
> export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
> export TESTCONTAINERS_RYUK_DISABLED=true
> ```

## 1. Get the starting project

Clone the repository and change to this guide's `initial/` directory:

```bash
git clone https://github.com/cockroachlabs/cockroachdb-spring-boot-starter.git
cd cockroachdb-spring-boot-starter/guides/serialization-retry/initial
```

The finished version of everything you're about to write is in `../complete/`, if you get stuck.

Here's what `initial/` already contains.

`build.gradle` pulls in Spring Boot's JDBC support, the PostgreSQL driver (CockroachDB speaks the PostgreSQL wire protocol), the CockroachDB starter, and Testcontainers for running CockroachDB in tests:

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.1.1'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.example'
version = '0.0.1-SNAPSHOT'

// Compile for Java 17 so the guide runs on any Java 17+ JDK.
tasks.withType(JavaCompile).configureEach {
    options.release = 17
}

repositories {
    mavenCentral()
    maven { url = 'https://cockroachlabs.github.io/cockroachdb-spring-boot-starter/maven/' }
}

dependencies {
    implementation 'com.cockroachlabs:cockroachdb-spring-boot-starter:0.3.0'
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
    runtimeOnly 'org.postgresql:postgresql'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.testcontainers:testcontainers-cockroachdb'
    testImplementation 'org.testcontainers:testcontainers-junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

`src/main/resources/schema.sql` and `data.sql` create an `accounts` table with two accounts holding 1,000.00 each, so the bank holds 2,000.00 in total:

```sql
CREATE TABLE IF NOT EXISTS accounts (
    id      INT PRIMARY KEY,
    owner   STRING NOT NULL,
    balance DECIMAL(12, 2) NOT NULL
);
```

```sql
UPSERT INTO accounts (id, owner, balance) VALUES
    (1, 'alice', 1000.00),
    (2, 'bob',   1000.00);
```

`src/main/resources/application.properties` tells Spring Boot to run those scripts at startup. It also switches off one feature of the starter for now: the starter normally retries `@Transactional` methods that hit a serialization conflict, and you'll first see what happens without that.

```properties
spring.application.name=serialization-retry
spring.sql.init.mode=always

# The starter retries @Transactional methods that hit a serialization conflict.
# It's switched off for now so you can see the problem first; step 4 turns it on.
cockroachdb.retry.apply-to-transactional-methods=false
```

`src/test/java/com/example/retry/TestcontainersConfiguration.java` starts a single-node CockroachDB container. `@ServiceConnection` hands its connection details to Spring Boot, so you never configure a JDBC URL:

```java
package com.example.retry;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.cockroachdb.CockroachContainer;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    CockroachContainer cockroach() {
        return new CockroachContainer("cockroachdb/cockroach:v26.3.3");
    }
}
```

There's also a standard `RetryApplication` main class, a `RetryApplicationTests` test that checks the application starts, and a `TestRetryApplication` class that runs the app against the container (you'll use it in step 3).

Check that everything works:

```bash
./gradlew build
```

The first run downloads Gradle, the dependencies, and the CockroachDB image, so it can take a few minutes. You should see `BUILD SUCCESSFUL`.

## 2. Write a transfer service

Create `src/main/java/com/example/retry/TransferService.java`. Start with the version most Spring developers would write first: a `@Transactional` method that reads both balances and writes the new ones.

```java
package com.example.retry;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final JdbcClient jdbc;

    // Counts every time transfer() starts, including retries.
    private final AtomicInteger attempts = new AtomicInteger();

    public TransferService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void transfer(int fromId, int toId, BigDecimal amount) {
        attempts.incrementAndGet();

        BigDecimal fromBalance = balanceOf(fromId);
        BigDecimal toBalance = balanceOf(toId);

        setBalance(fromId, fromBalance.subtract(amount));
        setBalance(toId, toBalance.add(amount));
    }

    public BigDecimal totalBalance() {
        return jdbc.sql("SELECT sum(balance) FROM accounts")
                .query(BigDecimal.class)
                .single();
    }

    public int attempts() {
        return attempts.get();
    }

    private BigDecimal balanceOf(int accountId) {
        return jdbc.sql("SELECT balance FROM accounts WHERE id = ?")
                .param(accountId)
                .query(BigDecimal.class)
                .single();
    }

    private void setBalance(int accountId, BigDecimal balance) {
        jdbc.sql("UPDATE accounts SET balance = ? WHERE id = ?")
                .params(balance, accountId)
                .update();
    }
}
```

The `attempts` counter isn't something you'd put in a real service. It's here so you can see, in the next steps, how many times the method body actually runs.

## 3. Run concurrent transfers and watch them fail

Create `src/test/java/com/example/retry/TransferDemo.java`. It uses `CockroachDBConcurrencyRunner`, a small helper that ships with the starter, to run 200 transfers on 8 threads at once. Each transfer moves 1.00 in a random direction between Alice and Bob.

```java
package com.example.retry;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

import com.cockroachlabs.data.test.CockroachDBConcurrencyRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.TestComponent;

/**
 * Runs 200 concurrent transfers between two accounts and prints what happened.
 */
@TestComponent
class TransferDemo implements CommandLineRunner {

    private final TransferService transfers;

    TransferDemo(TransferService transfers) {
        this.transfers = transfers;
    }

    @Override
    public void run(String... args) {
        BigDecimal before = transfers.totalBalance();

        CockroachDBConcurrencyRunner.Result result =
                CockroachDBConcurrencyRunner.run(8, 25, this::randomTransfer);

        System.out.println();
        System.out.println("Transfers succeeded: " + result.successes());
        System.out.println("Transfers failed:    " + result.failures());
        System.out.println("Attempts made:       " + transfers.attempts());
        System.out.println("Total balance:       " + transfers.totalBalance() + " (started at " + before + ")");
        result.errors().stream().findFirst().ifPresent(e ->
                System.out.println("First failure:       " + e.getClass().getSimpleName()
                        + " (SQLSTATE " + sqlState(e) + ")"));
        System.out.println();
    }

    private static String sqlState(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return "unknown";
    }

    private void randomTransfer() {
        if (ThreadLocalRandom.current().nextBoolean()) {
            transfers.transfer(1, 2, BigDecimal.ONE);
        } else {
            transfers.transfer(2, 1, BigDecimal.ONE);
        }
    }
}
```

Now register the demo in `src/test/java/com/example/retry/TestRetryApplication.java` by adding `TransferDemo.class` next to `TestcontainersConfiguration.class`:

```java
package com.example.retry;

import org.springframework.boot.SpringApplication;

/**
 * Starts the application against a CockroachDB container and runs the transfer demo.
 * Run it with {@code ./gradlew bootTestRun}.
 */
public class TestRetryApplication {

    public static void main(String[] args) {
        SpringApplication.from(RetryApplication::main)
                .with(TestcontainersConfiguration.class, TransferDemo.class)
                .run(args);
    }
}
```

Run it:

```bash
./gradlew bootTestRun
```

Gradle starts a CockroachDB container, starts the app against it, runs the demo, and exits. Look for output like this (your numbers will differ):

```
Transfers succeeded: 36
Transfers failed:    164
Attempts made:       200
Total balance:       2000.00 (started at 2000.00)
First failure:       CannotAcquireLockException (SQLSTATE 40001)
```

Most transfers failed. Two things are worth noticing.

**The money is all still there.** The total is still 2,000.00. Nothing was corrupted: every failed transfer was rolled back completely.

**The failures are SQLSTATE `40001`.** CockroachDB runs every transaction at SERIALIZABLE isolation, the strictest level: the end result must be the same as if the transactions had run one at a time. When two transfers read the same balance and both try to update it, they can't both be allowed to commit, because one of them would overwrite the other's change and money would disappear. So CockroachDB aborts one of them and returns `40001`, which means "this transaction conflicted with another one; run it again from the beginning."

On its own, Spring's `@Transactional` doesn't do that. It rolls back and hands the exception to the caller, so the transfer is simply lost. That's what you just saw, because `application.properties` switched off the starter's retries.

## 4. Let the starter retry `@Transactional` methods

Delete the last two lines from `src/main/resources/application.properties`, so it's back to:

```properties
spring.application.name=serialization-retry
spring.sql.init.mode=always
```

You don't change `TransferService` at all. With the CockroachDB starter on the classpath, every `@Transactional` method gets CockroachDB's retry behavior. When the method fails with `40001`, the starter:

1. lets Spring roll back the failed transaction,
2. waits a short time, longer after each failed attempt, with a little randomness so that conflicting transfers don't collide again in lockstep,
3. runs the whole method again in a **brand-new transaction**.

The new transaction matters. Once CockroachDB has aborted a transaction, it can't be resumed; the reads have to happen again so the method sees the balances the other transfer just committed.

Only `40001` errors are retried. Any other exception, such as a constraint violation or a bug in your code, goes straight to the caller as it always has with `@Transactional`.

Run the demo again:

```bash
./gradlew bootTestRun
```

Something like this:

```
Transfers succeeded: 199
Transfers failed:    1
Attempts made:       252
Total balance:       2000.00 (started at 2000.00)
First failure:       CannotAcquireLockException (SQLSTATE 40001)
```

Almost every transfer succeeds now, and `Attempts made` is higher than 200: the extra attempts are the retries the starter ran for you. You may still see one or two failures, or none at all. Those are transfers that conflicted on every attempt until the starter ran out of retries.

## 5. Tune a busy method with `@CockroachDBTransactional`

By default the starter retries a transaction 3 times before giving up, then passes the `40001` error to the caller. Eight threads fighting over two rows is heavy contention, so this method needs more.

In `TransferService`, replace `@Transactional` with `@CockroachDBTransactional(maxRetries = 10)` and swap the import. This is the final version of the file:

```java
package com.example.retry;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import com.cockroachlabs.data.annotation.CockroachDBTransactional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class TransferService {

    private final JdbcClient jdbc;

    // Counts every time transfer() starts, including retries.
    private final AtomicInteger attempts = new AtomicInteger();

    public TransferService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Retry a conflicting transfer up to 10 times before giving up.
    @CockroachDBTransactional(maxRetries = 10)
    public void transfer(int fromId, int toId, BigDecimal amount) {
        attempts.incrementAndGet();

        BigDecimal fromBalance = balanceOf(fromId);
        BigDecimal toBalance = balanceOf(toId);

        setBalance(fromId, fromBalance.subtract(amount));
        setBalance(toId, toBalance.add(amount));
    }

    public BigDecimal totalBalance() {
        return jdbc.sql("SELECT sum(balance) FROM accounts")
                .query(BigDecimal.class)
                .single();
    }

    public int attempts() {
        return attempts.get();
    }

    private BigDecimal balanceOf(int accountId) {
        return jdbc.sql("SELECT balance FROM accounts WHERE id = ?")
                .param(accountId)
                .query(BigDecimal.class)
                .single();
    }

    private void setBalance(int accountId, BigDecimal balance) {
        jdbc.sql("UPDATE accounts SET balance = ? WHERE id = ?")
                .params(balance, accountId)
                .update();
    }
}
```

`@CockroachDBTransactional` is `@Transactional` with CockroachDB's retry settings added. It takes every attribute `@Transactional` does (`readOnly`, `timeout`, `rollbackFor` and the rest, with the same meaning), plus `maxRetries`, `delay`, `multiplier` and `maxDelay` for the backoff. The one difference is that its isolation level defaults to SERIALIZABLE, which is what CockroachDB uses anyway.

Use it on methods that need different retry settings from the rest of the application. To change the defaults for every transactional method instead, set `cockroachdb.retry.max-retries` (and its siblings `delay`, `multiplier` and `max-delay`) in `application.properties`.

Run the demo again:

```bash
./gradlew bootTestRun
```

This time:

```
Transfers succeeded: 200
Transfers failed:    0
Attempts made:       240
Total balance:       2000.00 (started at 2000.00)
```

Every transfer succeeded and the total is unchanged.

## 6. Prove it with a test

The demo is useful to watch, but a test keeps the behaviour from regressing. Create `src/test/java/com/example/retry/TransferServiceTests.java`:

```java
package com.example.retry;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

import com.cockroachlabs.data.test.CockroachDBConcurrencyRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferServiceTests {

    @Autowired
    TransferService transfers;

    @Test
    void concurrentTransfersAllSucceed() {
        BigDecimal totalBefore = transfers.totalBalance();
        int attemptsBefore = transfers.attempts();

        CockroachDBConcurrencyRunner.Result result =
                CockroachDBConcurrencyRunner.run(8, 25, this::randomTransfer);

        // Every transfer eventually committed...
        assertThat(result.failures()).isZero();
        assertThat(result.successes()).isEqualTo(200);

        // ...no money was created or lost...
        assertThat(transfers.totalBalance()).isEqualByComparingTo(totalBefore);

        // ...and some of them only got there by being retried.
        assertThat(transfers.attempts() - attemptsBefore).isGreaterThan(200);
    }

    private void randomTransfer() {
        if (ThreadLocalRandom.current().nextBoolean()) {
            transfers.transfer(1, 2, BigDecimal.ONE);
        } else {
            transfers.transfer(2, 1, BigDecimal.ONE);
        }
    }
}
```

Run the tests:

```bash
./gradlew test
```

You should see `BUILD SUCCESSFUL`. The test checks all three things you saw in the demo: no transfer failed, the total balance didn't change, and retries really happened. The last check means the test can't pass just because no conflicts occurred.

If you're curious, switch `TransferService` back to `@Transactional`, add `cockroachdb.retry.apply-to-transactional-methods=false` to `application.properties` again, and run `./gradlew test`. The test fails, because most transfers are lost.

## Summary

- CockroachDB runs transactions at SERIALIZABLE isolation. When concurrent transactions conflict, it aborts one with SQLSTATE `40001` instead of letting data go wrong.
- A `40001` error means "run the transaction again". On its own, Spring's `@Transactional` won't do that.
- With the CockroachDB starter on the classpath, `@Transactional` methods are retried in a fresh transaction, waiting a little longer after each failure. `cockroachdb.retry.apply-to-transactional-methods=false` turns that off.
- `@CockroachDBTransactional` is `@Transactional` plus retry settings, for methods that need their own.
- Only `40001` errors are retried. Tune how many retries with `cockroachdb.retry.max-retries` for every method, or `@CockroachDBTransactional(maxRetries = ...)` for one.

## Where to go next

- The starter's [README](../../README.md) lists every retry setting and the other things `@CockroachDBTransactional` can do.
- CockroachDB's [transaction retry error reference](https://www.cockroachlabs.com/docs/stable/transaction-retry-error-reference.html) explains the different reasons a transaction gets a `40001`.
