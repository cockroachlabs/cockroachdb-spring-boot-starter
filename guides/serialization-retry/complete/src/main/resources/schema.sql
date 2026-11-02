CREATE TABLE IF NOT EXISTS accounts (
    id      INT PRIMARY KEY,
    owner   STRING NOT NULL,
    balance DECIMAL(12, 2) NOT NULL
);
