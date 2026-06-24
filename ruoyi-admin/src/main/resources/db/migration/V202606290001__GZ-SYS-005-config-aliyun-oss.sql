-- GZ-SYS-005：把 ruoyi 自带的 aliyun OSS 配置行预填为 guzi-sensenran 桶（杭州）。
--
-- 背景：文件上传走 ruoyi OssClient 抽象（GzFileServiceImpl → OssFactory.instance()），无业务代码改动；
--       切对象存储 = 改 sys_oss_config 数据 + 运维填 RAM 密钥。本迁移只改「非机密」字段，可入 git。
--
-- ⚠️ 不在此设默认（status 不动）：sys_oss_config 是单表，status='0' 决定全局默认；若此处激活 aliyun，
--    dev 本地的 minio 会被一并顶掉（dev 无真实 RAM key → dev 上传崩）。激活是「环境相关动作」：
--    staging/prod 在 admin「系统管理 → OSS 配置 → aliyun → 设为默认」启用；dev 保持 minio。
--
-- ⚠️ access_key / secret_key 是机密，不入 git：本迁移不写真实密钥，由运维在 OSS 配置页填 RAM 子账号
--    AccessKey/Secret 后保存（保存即刷新 Redis 缓存）。
--
-- 私有桶 + 后端签名 URL：access_policy='0'（private），对齐 GzFileServiceImpl.createPresignedGetUrl（1h TTL）。
--    桶在阿里云保持「私有」读写权限即可，无需公共读。

UPDATE sys_oss_config
SET bucket_name   = 'guzi-sensenran',
    endpoint      = 'oss-cn-hangzhou.aliyuncs.com',
    region        = 'cn-hangzhou',
    is_https      = 'Y',
    access_policy = '0',
    remark        = '谷子宇宙 staging/prod 对象存储（杭州私有桶 + 后端签名 URL）。AK/SK 由运维在 OSS 配置页填 RAM 子账号密钥（不入 git），并「设为默认」启用；dev 保持 minio。'
WHERE config_key = 'aliyun' AND tenant_id = '000000';
