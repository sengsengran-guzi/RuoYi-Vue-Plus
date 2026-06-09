package org.dromara.gz.coupon.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.gz.coupon.domain.bo.GzCouponTemplateBo;
import org.dromara.gz.coupon.domain.bo.GzCouponTemplateQueryBo;
import org.dromara.gz.coupon.domain.vo.GzCouponTemplateVO;

/**
 * 券模板服务（GZ-COUPON-001 AC 3/5）。
 *
 * <p>CRUD + 状态流转（active/paused/archived）。状态走专门流转方法，不允许 add/edit 直接改 status。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-COUPON-001)
 */
public interface IGzCouponTemplateService {

    /** 分页查询模板列表（全状态 + 筛选）。 */
    TableDataInfo<GzCouponTemplateVO> selectPage(GzCouponTemplateQueryBo query, PageQuery pageQuery);

    /** 模板详情（编辑页回填）；不存在返 null。 */
    GzCouponTemplateVO selectById(Long id);

    /** 新建模板（status=active，template_no 系统生成，issuedCount=0）。返回新建 id。 */
    Long insertByBo(GzCouponTemplateBo bo);

    /** 编辑模板（template_no / status / issuedCount / version 不在编辑路径改）。 */
    boolean updateByBo(GzCouponTemplateBo bo);

    /** 逻辑删（软删 del_flag='2'）。 */
    boolean deleteByIds(java.util.List<Long> ids);

    /** 暂停发放（active → paused，已发券不受影响）。 */
    boolean pause(Long id);

    /** 重新启用（paused → active）。 */
    boolean activate(Long id);

    /** 归档（active/paused → archived，终态不可发）。 */
    boolean archive(Long id);
}
