-- 14070-14079 verified unused in live sys_menu and repository migrations.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time) VALUES
(14070,'代切词表',14040,4,'term','gz-jp/snap/term/index',1,0,'C','0','0','gz:jp:snap:term:query','edit',103,1,NOW()),
(14071,'查询词表',14070,1,'','',1,0,'F','0','0','gz:jp:snap:term:query','#',103,1,NOW()),
(14072,'新增词条',14070,2,'','',1,0,'F','0','0','gz:jp:snap:term:add','#',103,1,NOW()),
(14073,'编辑词条',14070,3,'','',1,0,'F','0','0','gz:jp:snap:term:edit','#',103,1,NOW()),
(14074,'删除词条',14070,4,'','',1,0,'F','0','0','gz:jp:snap:term:remove','#',103,1,NOW());
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id BETWEEN 14070 AND 14074
WHERE r.tenant_id='1001' AND r.role_key='owner' AND r.del_flag='0'
AND NOT EXISTS(SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);
