-- GZ-RECYCLE-009 核销备注：店员核对回收单时可填核销备注（客户 7.09「店员应可输入一些内容」）。
-- 独立列 verify_remark，不复用用户下单整单备注 remark（remark 是顾客下单备注、已回显，复用会覆盖顾客数据）。
-- 落 markConfirmedOnsite（submitted→confirmed_onsite）时随核对留痕一并写入。append-only 新列。
ALTER TABLE gz_recycle_appointment
    ADD COLUMN verify_remark VARCHAR(500) NULL COMMENT '核销备注（店员核对时填，独立于顾客下单备注 remark）';
