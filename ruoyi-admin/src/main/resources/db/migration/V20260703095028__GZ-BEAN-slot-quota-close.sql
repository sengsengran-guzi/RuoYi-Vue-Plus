-- ============================================================
-- 拼豆「按桌型配额关闭」配置表 gz_bean_slot_quota_close + admin 菜单（客户 0702 反馈 #4a）
--
-- 语义：某门店某桌型在某具体服务日 sess_date 的某 1h 整点格 slot_start，店员直接关掉 close_count 个配额
--   （所见即所得）。取代老 gz_bean_seat_closure 按 seat_id 关闭再折算桌型余量的模型（店员想不通具体座位属哪桌型）。
--
-- 按具体日期（非周复发，区别于 gz_bean_seat_closure 的 weekday 周复发）：实时余量表格「周五 14-15 双人桌
--   开放 8 / 已约 5 / 剩 3，关 1 个」→ 只关这一天这一格。想周复发关走老规则（本 ticket 老表保留不动）。
--
-- 扣减口径（GzBeanBookingServiceImpl.selectTypeSlotAvailability / *Detail）：
--   effectiveCap = slotCapacity − closedSeatCount(老 seat_id 关闭) − quotaClose(本表 close_count)（下限 0），
--   remaining = effectiveCap − booked。remaining ≤ 0 → mp 该桌型该格灰显「已约满」。
--
-- ★ UNIQUE(tenant_id, store_id, seat_type_config_id, sess_date, slot_start)：一格一行（upsert 覆盖 close_count）。
--   uk 不含 del_flag（本表按具体日期，upsert 覆盖而非软删复用，无「软删后复用撞 DuplicateKey」路径；
--   如需清空该格关闭走 upsert close_count=0 落 0，不 DELETE，故 uk 无需含 del_flag）。
-- ============================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS gz_bean_slot_quota_close;

CREATE TABLE gz_bean_slot_quota_close (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT      COMMENT '主键',
    store_id             BIGINT UNSIGNED NOT NULL                     COMMENT 'FK → gz_bean_store.id',
    seat_type_config_id  BIGINT UNSIGNED NOT NULL                     COMMENT 'FK → gz_bean_seat_type_config.id（被关闭配额的桌型档）',
    sess_date            DATE            NOT NULL                     COMMENT '服务日（具体某天，非周复发）',
    slot_start           TIME            NOT NULL                     COMMENT '关闭作用的 1h 整点格起（含），如 14:00 表示 [14:00,15:00) 该格',
    close_count          INT             NOT NULL DEFAULT 0           COMMENT '该格关闭的配额个数（0=不关；upsert 覆盖，不累加）',

    -- 公共字段（doc/11 §1.1）
    tenant_id            VARCHAR(20)     NOT NULL DEFAULT '1001'      COMMENT '租户 ID',
    create_dept          BIGINT          NULL                         COMMENT '创建部门',
    create_by            BIGINT          NULL                         COMMENT '创建者',
    create_time          DATETIME        NULL                         COMMENT '创建时间',
    update_by            BIGINT          NULL                         COMMENT '更新者',
    update_time          DATETIME        NULL                         COMMENT '更新时间',
    del_flag             CHAR(1)         NOT NULL DEFAULT '0'         COMMENT '软删 0=正常 / 1=删除（@TableLogic 全局口径）',
    remark               VARCHAR(500)    NULL                         COMMENT '备注',

    PRIMARY KEY (id),
    UNIQUE KEY uk_gz_bean_slot_quota_close (tenant_id, store_id, seat_type_config_id, sess_date, slot_start)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拼豆按桌型配额关闭（按具体服务日，客户 0702 反馈 #4a）';

-- ============================================================
-- admin 菜单 + 权限（GZ-BEAN 段，grep 全部迁移 + 活库确认 6064/6065/6066 为空号；6062/6063 已被营业额占）
--   6064 = 页菜单（实时余量表格，parent 6000 拼豆业务，component gz-bean/slot-availability/index，path slot-availability）
--   6065 = 查询按钮（perm gz:bean:slotQuota:list —— 表格加载 + 明细列表共用此权限点）
--   6066 = 关闭数编辑按钮（perm gz:bean:slotQuota:edit —— upsert 单格 close_count）
-- 授权：owner(100) + staff(101) 均可见可查可改（店员日常在表上直接关座位）。
-- ============================================================

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(6064, '实时余量', 6000, 9, 'slot-availability', 'gz-bean/slot-availability/index', '', 1, 0, 'C', '0', '0', 'gz:bean:slotQuota:list', 'chart', 103, 1, NOW(), NULL, NULL, '拼豆实时余量表格（门店+日期→桌型×时段 开放/已约/剩余/关闭数；客户 0702 反馈 #4a）'),
(6065, '余量查询', 6064, 1, '', '', '', 1, 0, 'F', '0', '0', 'gz:bean:slotQuota:list', '#', 103, 1, NOW(), NULL, NULL, '实时余量表格加载 + 明细列表'),
(6066, '关闭数编辑', 6064, 2, '', '', '', 1, 0, 'F', '0', '0', 'gz:bean:slotQuota:edit', '#', 103, 1, NOW(), NULL, NULL, 'upsert 单格配额关闭数（表格 stepper 改关闭数即回写）');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
  (100, 6064), (100, 6065), (100, 6066),
  (101, 6064), (101, 6065), (101, 6066);
