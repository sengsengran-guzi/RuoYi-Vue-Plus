-- GZ-BEAN-035 / ADR-0016 §6：门店级「临近结束」提前提醒分钟数（甲方「30/40/20 店家自己设置」）。
-- 计时看板 in_use 单 remainingMinutes ≤ 本值 → near_end 高亮 + admin 主动弹窗提醒前台。
-- 取代旧全局单值 sys_config gz.bean.board.near_end_minutes（service 仍保留其为回退级）。
ALTER TABLE gz_bean_store
    ADD COLUMN near_end_minutes INT NOT NULL DEFAULT 30
    COMMENT '计时看板临近结束提前提醒分钟数(ADR-0016 §6;默认30)' AFTER max_advance_days;
