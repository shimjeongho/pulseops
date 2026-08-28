CREATE TABLE orders (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    order_number VARCHAR(40)    NOT NULL,
    status       VARCHAR(30)    NOT NULL,
    total_amount DECIMAL(12, 2) NOT NULL,
    created_at   DATETIME(6)    NOT NULL,
    updated_at   DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_orders_order_number UNIQUE (order_number),
    CONSTRAINT ck_orders_total_amount CHECK (total_amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE order_item (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    order_id     BIGINT         NOT NULL,
    product_id   BIGINT         NOT NULL,
    product_name VARCHAR(120)   NOT NULL,
    unit_price   DECIMAL(12, 2) NOT NULL,
    quantity     INT            NOT NULL,
    subtotal     DECIMAL(12, 2) NOT NULL,
    created_at   DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_order_item_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ck_order_item_quantity CHECK (quantity >= 1),
    CONSTRAINT ck_order_item_subtotal CHECK (subtotal >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
