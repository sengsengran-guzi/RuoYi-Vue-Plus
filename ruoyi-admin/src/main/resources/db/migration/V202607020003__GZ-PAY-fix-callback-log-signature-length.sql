-- ============================================================
-- GZ-PAY 修复：gz_pay_callback_log.signature 列扩容（VARCHAR(256) → VARCHAR(1024)）
--
-- 故障（真机首单暴露，2026-06-25 staging）：
--   真实微信回调头 Wechatpay-Signature 是 RSA-2048 base64 签名 ≈ 344 字符，超过原 VARCHAR(256)。
--   handlePaymentNotify 步骤② writeCallbackLog INSERT gz_pay_callback_log 时 MySQL 截断报错
--   （Data truncation: Data too long for column 'signature'）。该 INSERT 在 handlePaymentNotify
--   的 @Transactional(rollbackFor=Exception) 内 → 异常使整笔支付回调事务回滚 → pay_status 永远停在
--   paying（正向支付回调 + 退款回调 在真机/真微信下全部失败）。WeChat 每 ~15s 重试，始终同样截断。
--
--   连锁后果：用户已被微信扣款，但后端从未记成 paid → 取消预约时 cancel() 的 realPaid 判定为 false
--   （pay_status≠paid）→ 正确地不发起退款 → 表现为「取消成功但钱没退」。根因不是退款逻辑，是本列过窄。
--
-- 修复：扩到 VARCHAR(1024)。RSA-2048 base64=344、RSA-4096 base64=684 均容得下；signature 不建索引，
--   扩容零成本。raw_body 已是 TEXT、process_error VARCHAR(500) 均够用，仅 signature 需扩。
--
-- ⚠️ 已应用迁移不可改；本迁移版本号 > 当前最大 V202607020002。Flyway 启动自动 ALTER。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_pay_callback_log
  MODIFY COLUMN signature VARCHAR(1024) NULL COMMENT 'Wechatpay-Signature 头（验签后存档；RSA-2048 base64≈344，留余量 1024）';
