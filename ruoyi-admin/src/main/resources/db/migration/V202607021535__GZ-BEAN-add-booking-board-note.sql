-- 店内计时看板「本次占用备注」：店员点看板上「有人在使用」的座位时记录的备注，
-- 仅与当前这笔占用（预约单）有关；放座后该座位在看板判回空闲、本备注不再展示（纯展示逻辑，不物理删）。
-- 与 gz_bean_seat.remark（座位永久备注，空闲时显示/编辑）区分：座位空闲写 seat.remark（长期留存），
-- 座位占用写 booking.board_note（随本次占用生命周期）。复用 gz:bean:booking:verify 权限（店员可写）。
ALTER TABLE gz_bean_booking
    ADD COLUMN board_note VARCHAR(500) NULL COMMENT '看板本次占用备注（店员现场记录，仅当前占用有效，放座后看板不再展示）' AFTER remark;
