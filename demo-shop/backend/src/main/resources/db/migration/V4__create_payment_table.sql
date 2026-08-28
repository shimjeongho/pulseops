CREATE TABLE payment (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    payment_number  VARCHAR(40)    NOT NULL,
    order_id        BIGINT         NOT NULL,
    amount          DECIMAL(12, 2) NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    failure_code    VARCHAR(50)    NULL,
    failure_message VARCHAR(255)   NULL,
    created_at      DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_payment_number UNIQUE (payment_number),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_payment_amount CHECK (amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
