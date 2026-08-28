-- ============================================================
-- GZ-RECYCLE-016 补丁 — 修正客服二维码 sys_config 行的 tenant_id
--
-- 根因：上一个迁移（V202608271147）用 INSERT IGNORE 裸写，未显式指定 tenant_id，
-- 落到了 sys_config.tenant_id 列自身的 DEFAULT '000000'。而本项目已在 V202607272010
-- 把双租户合并成单租户 1001（sys_user/role/config/dept/post/notice 一并搬迁），
-- admin 现在的会话固定在 tenant_id=1001 —— 000000 那份对 admin「参数设置」页不可见
-- （查询按当前租户过滤），导致甲方要改这张二维码时在 admin 里搜不到这一行。
--
-- 同类既有 key（gz.home.banners / gz.bean.home.banner）当前都是 tenant_id=1001，
-- 但那是 V202607272010 那次历史性搬迁事后改过的结果，不是它们各自原始 INSERT 时
-- 就带对的——本迁移之后新增任何 sys_config 行都必须显式写 tenant_id='1001'，
-- 不能再照抄旧迁移里"裸 INSERT 不写 tenant_id"的写法。
--
-- 迁移 append-only：不改动 V202608271147（已应用），改用本文件订正。
-- ============================================================

UPDATE sys_config
   SET tenant_id = '1001'
 WHERE config_key = 'gz.recycle.serviceQrcode'
   AND tenant_id = '000000';
