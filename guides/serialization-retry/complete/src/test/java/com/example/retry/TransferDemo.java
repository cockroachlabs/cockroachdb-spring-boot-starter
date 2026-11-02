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
