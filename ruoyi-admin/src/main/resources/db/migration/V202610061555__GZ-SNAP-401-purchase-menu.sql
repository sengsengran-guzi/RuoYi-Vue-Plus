-- GZ-SNAP-401: verified unused 14040 / 14050-14054 in all migrations and live DB.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time) VALUES
(14040,'代切',14000,5,'snap',NULL,1,0,'M','0','0','','shopping',103,1,NOW()),
(14050,'代切下单',14040,1,'purchase','gz-jp/snap/purchase/index',1,0,'C','0','0','gz:jp:snap:purchase:query','shopping',103,1,NOW()),
(14051,'查询',14050,1,'','',1,0,'F','0','0','gz:jp:snap:purchase:query','#',103,1,NOW()),
(14052,'人工填结果',14050,2,'','',1,0,'F','0','0','gz:jp:snap:purchase:manual','#',103,1,NOW()),
(14053,'交易操作',14050,3,'','',1,0,'F','0','0','gz:jp:snap:purchase:trade','#',103,1,NOW()),
(14054,'查看证据',14050,4,'','',1,0,'F','0','0','gz:jp:snap:purchase:evidence','#',103,1,NOW());
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id IN(14040,14050,14051,14052,14053,14054)
WHERE r.tenant_id='1001' AND r.role_key='owner' AND r.del_flag='0'
AND NOT EXISTS(SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);
