package org.dromara.gz.user.service;

import org.dromara.gz.user.domain.bo.GzUserAddressBo;
import org.dromara.gz.user.domain.vo.GzUserAddressVO;

import java.util.List;

/**
 * 收货地址簿服务（GZ-USER-003）。
 *
 * <p>所有方法以当前登录用户 userId 为归属边界（不允许跨用户操作）。
 * 默认地址唯一性走业务层事务兜底（doc/11 §2.2）。</p>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-003)
 */
public interface IGzUserAddressService {

    /**
     * 当前用户地址列表（默认地址置顶 + 创建时间倒序，软删过滤）。
     */
    List<GzUserAddressVO> listByUser(Long userId);

    /**
     * 地址详情（仅本人）。
     *
     * @return VO；不存在 / 非本人 → null
     */
    GzUserAddressVO getByIdForUser(Long userId, Long id);

    /**
     * 新增 / 编辑地址。
     *
     * <p>bo.id 非空 = 编辑（校验归属）；为空 = 新增。
     * isDefault=1 时事务内 reset 该用户其他地址为非默认。</p>
     *
     * @return 保存后的 VO
     */
    GzUserAddressVO save(Long userId, GzUserAddressBo bo);

    /**
     * 软删地址（仅本人）。
     *
     * @return 是否删除成功
     */
    boolean delete(Long userId, Long id);

    /**
     * 设为默认地址（事务内 reset 其他 + set 当前，doc/10 §4.N7）。
     *
     * @return 是否成功
     */
    boolean setDefault(Long userId, Long id);
}
