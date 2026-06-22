-- T8.3（D17 上线前审计）：sys_menu 6020「座位与时段配置」是 C 路由菜单，perms 误填逗号拼接脏值
-- 'gz:bean:seat:list,gz:bean:slot:list'。ruoyi 装载权限集时不按逗号拆分（整串塞进 HashSet），
-- @SaCheckPermission 单串等值匹配永不命中该脏串 → 死重量 + 潜伏 fail-closed 失配。
-- 真正鉴权由叶子 F 按钮 6021(gz:bean:seat:list) / 6030(gz:bean:slot:list) 承载，C 路由菜单无需业务 perm。
-- 故清空 6020 perms（铁律#5：新建更大时间戳迁移，不改已应用文件）。
UPDATE sys_menu SET perms = NULL
 WHERE menu_id = 6020 AND perms = 'gz:bean:seat:list,gz:bean:slot:list';
