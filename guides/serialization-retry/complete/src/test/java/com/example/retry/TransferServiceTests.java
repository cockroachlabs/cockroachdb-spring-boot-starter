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
