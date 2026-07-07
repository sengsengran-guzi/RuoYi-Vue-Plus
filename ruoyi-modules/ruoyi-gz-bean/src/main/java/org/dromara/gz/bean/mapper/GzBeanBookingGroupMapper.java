package org.dromara.gz.bean.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.gz.bean.domain.entity.GzBeanBookingGroup;
import org.dromara.gz.bean.domain.vo.GzBeanBookingGroupVO;

/**
 * gz_bean_booking_group 数据层（ADR-0018 §1 组单支付聚合）。
 *
 * <p>CRUD 走 BaseMapperPlus 默认方法（多租户 / 软删由 ruoyi 拦截器自动处理）。额外一个按 group_no 回表，
 * 供支付回调 {@code onPindouGroupPaid} 定位（businessOrderNo = group_no，与单笔单 selectByBookingNo 同模式）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-BEAN-051)
 */
public interface GzBeanBookingGroupMapper extends BaseMapperPlus<GzBeanBookingGroup, GzBeanBookingGroupVO> {

    /**
     * 按组业务码回表（支付回调 / 关单定位）。忽略多租户按 group_no（UNIQUE 含 tenant，回调无登录态显式扫）。
     *
     * @param groupNo 组业务码 BG...
     * @return 组单实体（未命中 null）
     */
    @Select("SELECT * FROM gz_bean_booking_group WHERE group_no = #{groupNo} AND del_flag = '0' LIMIT 1")
    GzBeanBookingGroup selectByGroupNo(@Param("groupNo") String groupNo);
}
