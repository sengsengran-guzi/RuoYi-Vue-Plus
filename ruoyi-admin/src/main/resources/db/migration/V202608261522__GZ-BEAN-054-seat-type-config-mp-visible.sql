-- ============================================================
-- GZ-BEAN-054 拼豆「临时桌型」= 桌型档增加小程序可见性开关（甲方 2026-08-26 微信）
--
-- 痛点：看板计时格 = 小程序开放的桌型座位数（当前 3*4 = 12 位）。周末临时加桌（4*4 / 1*4）后
--   看板上没有对应格子 → 店员只能写纸条、无法计时。甲方要「看板里加临时桌，但不进小程序」，
--   并明确「你别改我设置好的小程序桌子」「小程序就是只开放 2*4」。
--
-- 根因：enabled 是唯一开关且四处耦合（mp type-slots / 看板 / 座位批量生成 / walk-in），
--   停用桌型 = 小程序不显示 **且** 看板上该桌型座位全消失 → 没有「只对小程序隐藏」的能力。
--
-- 决策（ADR-0023）：把「是否存在」（enabled）与「是否对小程序开放」（mp_visible）拆成两个正交轴。
--   enabled=1, mp_visible=1 → 正常桌型（存量全部，DEFAULT 1 保证零行为变化）
--   enabled=1, mp_visible=0 → 临时桌：看板有格 + 可 walk-in 计时 + 可承接线上单现场分座；小程序不展示不可订
--   enabled=0            → 退役（语义完全不变，看板/座位生成/walk-in 全消失）
--
-- 过滤点（仅小程序可订面 6 处）：type-slots / day-pass-options / seat-map /
--   submitPaid / submitPaidGroup / submitDayPass；外加 admin 实时余量表（配额关闭对临时桌无意义，
--   walk-in 故意绕过配额闸，摆一个 stepper 就是拨了不生效的假开关）。
--   看板 / batchGenerate / walk-in / admin-create / 核销分座 / 营业额聚合 **不过滤**。
--
-- 命名取 mp_visible 而不是 is_temp：不变量只有「不进小程序」，is_temp 会承诺一个不存在的
--   时间语义（自动过期？周末才有效？）。UI 上仍叫「临时桌」（甲方的词），DB/API 用机制名。
--
-- 索引：不新增。既有 idx_tenant_store_enabled_sort(tenant_id,store_id,enabled,sort_no)
--   已把单店桌型收敛到个位数行，多一个布尔谓词无需索引支持。
-- 菜单 / 权限：无新增。开关落在既有桌型档表单内，沿用 gz:bean:seatTypeConfig:edit。
--   （备注：6000 段下一个空号是 6068 —— 6062/6063 营业额、6064-6066 实时余量、6067 座位管理 shell 已占。）
--
-- Flyway append-only：本文件为新增，不改任何旧迁移。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE gz_bean_seat_type_config
    ADD COLUMN mp_visible TINYINT NOT NULL DEFAULT 1
        COMMENT '是否对小程序开放：1=开放可订（正常桌型）/ 0=仅后台看板可见的临时桌（GZ-BEAN-054）'
        AFTER enabled;
