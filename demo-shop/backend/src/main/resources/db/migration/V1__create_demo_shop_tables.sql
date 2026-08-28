CREATE TABLE product (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    name           VARCHAR(120)   NOT NULL,
    description    VARCHAR(1000)  NULL,
    price          DECIMAL(12, 2) NOT NULL,
    stock_quantity INT            NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    created_at     DATETIME(6)    NOT NULL,
    updated_at     DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_product_price CHECK (price >= 0),
    CONSTRAINT ck_product_stock_quantity CHECK (stock_quantity >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
