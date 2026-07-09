-- GZ-RECYCLE-008 补授权：13007「回收配置」菜单遗漏 sys_role_menu 授权，owner 侧边栏不显（V202607091528 漏授权修补）。
-- 铁律：13000 段不在 ruoyi 5000-5999 批量授权范围，新菜单必须显式 INSERT sys_role_menu，否则甲方负责人(role_id=100)看不到。
-- 幂等：先 DELETE 该 menu 授权再 INSERT，重复执行同结果。回收配置为 owner 级配置（点数档 + 时段），仅授 owner(100)，与 13005/13006 一致。
DELETE FROM sys_role_menu WHERE menu_id = 13007;
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (100, 13007);
