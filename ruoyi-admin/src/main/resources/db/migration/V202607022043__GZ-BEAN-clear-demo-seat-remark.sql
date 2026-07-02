-- 座位备注默认应为空，只有店员在看板手写才有内容（GZ-BEAN 看板座位备注上卡片显示后暴露的问题）。
-- 历史 seed 迁移把 demo 文案写进了 gz_bean_seat.remark：
--   V202606291626（GZ-BEAN-022）→ 'ADR-0015 demo 座位单元，admin 可调/重新生成'
--   GZ-BEAN-002 早期 seed         → 'GZ-BEAN-002 demo 座位'（A1-B5 legacy 座）
-- 以前 remark 不展示无碍；现在备注上卡片显示 → 每个座位都挂着这句 demo 文案。清空这两批 seed 文案，
-- 只精准命中这两个 demo 串、不碰店员真写的备注（seed 迁移 append-only 不可改，故新建本迁移清数据）。
UPDATE gz_bean_seat
SET remark = NULL
WHERE remark LIKE 'ADR-0015 demo 座位单元%'
   OR remark LIKE 'GZ-BEAN-002 demo 座位%';
