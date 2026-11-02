package com.example.retry;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import com.cockroachlabs.data.annotation.CockroachDB;
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

    @CockroachDB
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
