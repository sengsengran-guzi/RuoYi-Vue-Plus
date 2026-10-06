-- 14080-14089 unused in live sys_menu and all migrations. Reuse 303 query/fx permission codes.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time) VALUES
(14080,'代切配置',14040,3,'config','gz-jp/snap/config/index',1,0,'C','0','0','gz:jp:snap:config:query','edit',103,1,NOW()),
(14081,'查询配置',14080,1,'','',1,0,'F','0','0','gz:jp:snap:config:query','#',103,1,NOW()),
(14082,'编辑配置',14080,2,'','',1,0,'F','0','0','gz:jp:snap:config:edit','#',103,1,NOW()),
(14083,'手动汇率',14080,3,'','',1,0,'F','0','0','gz:jp:snap:config:fx','#',103,1,NOW());
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id BETWEEN 14080 AND 14083
WHERE r.tenant_id='1001' AND r.role_key='owner' AND r.del_flag='0'
AND NOT EXISTS(SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);
