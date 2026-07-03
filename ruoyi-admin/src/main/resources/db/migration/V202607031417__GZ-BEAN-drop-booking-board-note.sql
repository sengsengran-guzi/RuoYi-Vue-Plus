-- 撤回「看板本次占用备注」双存储：备注最终口径 = 纯挂座位（gz_bean_seat.remark），与是否有人/空闲无关，
-- 店员手动填/清，座位状态变化不自动清。gz_bean_booking.board_note（V202607021535 加的占用态备注列）不再使用，
-- 删除之。ADD 迁移已应用、append-only 不可改，故新建本 DROP 迁移；新环境按序 ADD→DROP，末态无此列。
ALTER TABLE gz_bean_booking DROP COLUMN board_note;
