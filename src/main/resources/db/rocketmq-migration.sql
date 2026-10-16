-- Run against the existing hmdp database. Never reimport hmdp.sql into a live database.
-- A duplicate precheck aborts before any table/index changes.
DELIMITER $$
CREATE PROCEDURE migrate_seckill_mq()
BEGIN
 IF EXISTS (SELECT 1 FROM tb_voucher_order GROUP BY user_id,voucher_id HAVING COUNT(*)>1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Duplicate user/voucher orders: resolve manually before migration';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND index_name='uk_user_voucher') THEN
  ALTER TABLE tb_voucher_order ADD UNIQUE KEY uk_user_voucher(user_id,voucher_id);
 END IF;
 CREATE TABLE IF NOT EXISTS tb_seckill_request (
  id BIGINT NOT NULL PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  voucher_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(16) NOT NULL,
  reason VARCHAR(64) NULL,
  compensated TINYINT NOT NULL DEFAULT 0,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_compensation(status,compensated,id)
 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
END$$
DELIMITER ;
CALL migrate_seckill_mq();
DROP PROCEDURE migrate_seckill_mq;
