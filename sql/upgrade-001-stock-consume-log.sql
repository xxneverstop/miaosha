-- 同一库存流水共用一条扣库凭证，凭证与扣库一起由 InnoDB 提交。
CREATE TABLE IF NOT EXISTS stock_consume_log (
    stock_log_id VARCHAR(64) NOT NULL,
    item_id INT NOT NULL,
    amount INT NOT NULL,
    consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_log_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_general_ci;
