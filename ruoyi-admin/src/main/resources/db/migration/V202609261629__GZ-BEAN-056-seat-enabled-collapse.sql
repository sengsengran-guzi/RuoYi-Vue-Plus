-- GZ-BEAN-056 / ADR-0024 拼豆桌型开关收敛：删除 gz_bean_seat_type_config.enabled 与 gz_bean_seat.enabled，
--   「退役」语义改由软删（del_flag）表达，mp_visible 保留为唯一的「是否对小程序开放」开关。
--
-- 为什么这么迁（语义等价，不丢业务态）：
--   ① gz_bean_seat_type_config.enabled=0 的原语义 = 「退役」——看板 / 座位批量生成 / walk-in / mp 全部消失
--      （ADR-0023 §1 的三态表）。新模型里「半存在的退役态」不存在了，「退役」就等于软删，
--      故 enabled=0 的行按 del_flag='1' 落库；@TableLogic 的 del_flag='0' 过滤从此自动接管原先四处
--      enabled=1 判断（座位批量生成 resolveConfigs / mp 目录 / 包天选项 / 余量等），语义不变。
--   ② gz_bean_seat.enabled=0 的原语义仅「分座时硬拒 SEAT_DISABLED」，对小程序售卖无任何影响
--      （slotCapacity 从不读座位表，ADR-0024 §1）；新模型里没有对应物。按甲方口径「关闭座仍可被分给新客」
--      （无论是否关闭，看板与分座都不受影响），这些行一律置 1，不写 del_flag —— 不删座位、不丢看板格子。
--
-- 两表都是小表（config ~18 行 / seat ~66 行），ALTER 重建代价可忽略。
-- 表上以 enabled 打头的索引（gz_bean_seat_type_config.idx_tenant_store_enabled_sort /
--   gz_bean_seat.idx_tenant_store_enabled_sort / gz_bean_seat.idx_tenant_store_enabled）
--   随 DROP COLUMN 由 InnoDB 自动摘除，不需要（也不允许在列缺失后）显式 DROP INDEX。

SET NAMES utf8mb4;

-- ① 桌型 enabled=0（原语义「退役」）→ 等价软删
UPDATE gz_bean_seat_type_config
   SET del_flag = '1', update_time = NOW()
 WHERE enabled = 0
   AND del_flag = '0';

-- ② 座位 enabled=0（原语义仅「挡分座」）→ 新模型无对应物，统一置 1 保留在看板与分座池里
UPDATE gz_bean_seat
   SET enabled = 1, update_time = NOW()
 WHERE enabled = 0
   AND del_flag = '0';

-- ③ 删列
ALTER TABLE gz_bean_seat_type_config DROP COLUMN enabled;
ALTER TABLE gz_bean_seat DROP COLUMN enabled;
