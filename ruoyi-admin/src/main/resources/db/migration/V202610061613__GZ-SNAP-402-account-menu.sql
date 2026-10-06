-- 14060-14069 verified unused in live sys_menu and all existing migration files.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time) VALUES
(14060,'采购账号',14040,2,'account','gz-jp/snap/account/index',1,0,'C','0','0','gz:jp:snap:account:query','user',103,1,NOW()),
(14061,'查询',14060,1,'','',1,0,'F','0','0','gz:jp:snap:account:query','#',103,1,NOW()),
(14062,'编辑账号',14060,2,'','',1,0,'F','0','0','gz:jp:snap:account:edit','#',103,1,NOW()),
(14063,'自动下单开关',14060,3,'','',1,0,'F','0','0','gz:jp:snap:account:switch','#',103,1,NOW());
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id BETWEEN 14060 AND 14063
WHERE r.tenant_id='1001' AND r.role_key='owner' AND r.del_flag='0'
AND NOT EXISTS(SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);
