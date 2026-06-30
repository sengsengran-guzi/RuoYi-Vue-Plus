-- GZ-BEAN-039 / kevin-test §4：admin 代客预定（现场没带手机的用户，店员代为锁座，一步 used + 线下已付）。
-- gz_bean_booking 加 source 列区分下单来源：
--   mp    = 小程序用户自助下单（默认，存量单回填 mp）
--   admin = 店员代客预定（线下已付，out_trade_no 为 NULL，默认不进微信对账 GMV，便于对账/看板辨识）
ALTER TABLE gz_bean_booking
    ADD COLUMN `source` VARCHAR(16) NOT NULL DEFAULT 'mp'
        COMMENT '下单来源：mp=小程序用户 / admin=店员代客预定（GZ-BEAN-039）';
