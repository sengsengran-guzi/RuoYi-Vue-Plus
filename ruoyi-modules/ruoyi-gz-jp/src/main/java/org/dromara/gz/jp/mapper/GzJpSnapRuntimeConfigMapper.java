package org.dromara.gz.jp.mapper;

import org.apache.ibatis.annotations.*;

/** Writes the existing JSON key; row lock prevents lost admin/config updates. */
@Mapper
public interface GzJpSnapRuntimeConfigMapper {
    @Select("SELECT config_value FROM sys_config WHERE tenant_id='1001' AND config_key='gz.jp.snap.auto' FOR UPDATE")
    String lockAutoConfig();
    @Update("UPDATE sys_config SET config_value=#{value}, update_time=NOW() WHERE tenant_id='1001' AND config_key='gz.jp.snap.auto'")
    int writeAutoConfig(@Param("value") String value);
}
