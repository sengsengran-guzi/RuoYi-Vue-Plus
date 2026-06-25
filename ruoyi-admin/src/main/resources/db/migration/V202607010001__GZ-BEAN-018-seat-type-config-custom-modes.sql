-- ============================================================
-- GZ-BEAN-018 拼豆座位类型「去字典化自定义 + 整桌/按座双模式」
--
-- 字段口径权威：doc/11 §3.4 + ADR-0014 §1。
-- 把 gz_bean_seat_type_config 从「字典枚举配额行」升级为门店自定义类型主数据：
--   + name      自定义显示名（取代字典 gz_bean_seat_type 的 label）
--   + book_mode 订法 whole=整桌（不可拆座）/ seat=按座（可拼桌）
--   + capacity  每桌座位数（seat 模式下 quantity*capacity = 每 1h 格总座数）
-- seat_type 列保留作门店内稳定 code（旧 single/double/quad；新行后端自动生成，admin 不暴露）。
-- 字典 gz_bean_seat_type 退役（sys_dict 保留不删，新类型不再写字典）。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_bean_seat_type_config
    ADD COLUMN name      VARCHAR(32) NOT NULL DEFAULT ''      COMMENT '自定义显示名（取代字典 label）'                       AFTER seat_type,
    ADD COLUMN book_mode VARCHAR(8)  NOT NULL DEFAULT 'whole' COMMENT '订法 whole=整桌(不可拆座)/seat=按座(可拼桌)'         AFTER name,
    ADD COLUMN capacity  INT         NOT NULL DEFAULT 1       COMMENT '每桌座位数（seat 模式 quantity*capacity=每格总座数）' AFTER book_mode;

-- 回填旧三类型（ADR-0014 §1 / 待澄清 F14.1 默认：单人=整桌1座 / 双人=整桌2座 / 四人桌=按座4座）
UPDATE gz_bean_seat_type_config SET name = '单人',   book_mode = 'whole', capacity = 1 WHERE seat_type = 'single' AND del_flag = '0';
UPDATE gz_bean_seat_type_config SET name = '双人',   book_mode = 'whole', capacity = 2 WHERE seat_type = 'double' AND del_flag = '0';
UPDATE gz_bean_seat_type_config SET name = '四人桌', book_mode = 'seat',  capacity = 4 WHERE seat_type = 'quad'   AND del_flag = '0';

-- 防同店重名（回填后 name 已就绪；UNIQUE 必含 tenant_id，CLAUDE.md §6 #3）
ALTER TABLE gz_bean_seat_type_config
    ADD UNIQUE KEY uk_gz_bean_stc_name (tenant_id, store_id, name);
