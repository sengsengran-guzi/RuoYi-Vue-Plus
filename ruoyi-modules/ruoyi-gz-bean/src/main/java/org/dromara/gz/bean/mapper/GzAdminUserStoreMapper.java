package org.dromara.gz.bean.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 读取 admin 后台账号（sys_user）绑定的门店 id（GZ-BEAN-008 store_id 权限隔离）。
 *
 * <p>{@code gz_store_id} 字段由 GZ-ADMIN-001 ALTER 到 ruoyi 自带 {@code sys_user} 表（V1.0 单店 default null）；
 * 不修改 ruoyi-system 源码（强约束 #1），仅在本模块开一只只读小 mapper 直查该列。</p>
 *
 * <p>权限隔离口径（doc/10 §3 N10/N11 + ticket 强约束 #6）：</p>
 * <ul>
 *   <li>owner / superadmin → gz_store_id 通常为 null（跨门店），由 service 层按角色判定看全部</li>
 *   <li>staff → gz_store_id 绑定具体门店，预约列表强制 {@code WHERE store_id = gz_store_id}</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-008)
 */
@Mapper
public interface GzAdminUserStoreMapper {

    /**
     * 查 admin 账号绑定的门店 id。
     *
     * <p>按 user_id 直查（user_id 全局唯一，不依赖 tenant_id 注入）。无绑定 → 返 null。</p>
     *
     * @param userId 当前登录 admin 用户 id
     * @return 绑定门店 id（null = 未绑定 / 跨门店）
     */
    @Select("SELECT gz_store_id FROM sys_user WHERE user_id = #{userId} AND del_flag = '0' LIMIT 1")
    Long selectStoreIdByUserId(@Param("userId") Long userId);
}
