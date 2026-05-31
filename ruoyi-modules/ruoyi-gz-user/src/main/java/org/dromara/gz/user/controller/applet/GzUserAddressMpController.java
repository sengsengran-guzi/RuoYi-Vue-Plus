package org.dromara.gz.user.controller.applet;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.gz.user.domain.bo.GzUserAddressBo;
import org.dromara.gz.user.domain.vo.GzUserAddressVO;
import org.dromara.gz.user.service.IGzUserAddressService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GZ-USER-003 mp 端收货地址簿 Controller。
 *
 * <p>路径 {@code /app/gz/user/address}（mp 前缀 {@code /app/}，走 sa-token mp-client）。需登录态。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code GET    /list}          — 地址列表（默认置顶）</li>
 *   <li>{@code GET    /{id}}          — 地址详情</li>
 *   <li>{@code POST   /}              — 新增</li>
 *   <li>{@code PUT    /}              — 编辑</li>
 *   <li>{@code DELETE /{id}}          — 软删</li>
 *   <li>{@code POST   /{id}/default}  — 设为默认</li>
 * </ul>
 *
 * @author kevin-coder (sensenran-guzi · GZ-USER-003)
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/gz/user/address")
public class GzUserAddressMpController {

    private final IGzUserAddressService addressService;

    /** 地址列表（默认置顶 + 创建时间倒序）。 */
    @GetMapping("/list")
    public R<List<GzUserAddressVO>> list() {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(addressService.listByUser(userId));
    }

    /** 地址详情（仅本人）。 */
    @GetMapping("/{id}")
    public R<GzUserAddressVO> detail(@PathVariable Long id) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        GzUserAddressVO vo = addressService.getByIdForUser(userId, id);
        if (vo == null) {
            return R.fail("地址不存在");
        }
        return R.ok(vo);
    }

    /** 新增地址。 */
    @PostMapping
    public R<GzUserAddressVO> add(@Valid @RequestBody GzUserAddressBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        bo.setId(null); // 新增路径强制清空 id
        return R.ok(addressService.save(userId, bo));
    }

    /** 编辑地址。 */
    @PutMapping
    public R<GzUserAddressVO> update(@Valid @RequestBody GzUserAddressBo bo) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        if (bo.getId() == null || bo.getId().isBlank()) {
            return R.fail("地址 ID 不能为空");
        }
        return R.ok(addressService.save(userId, bo));
    }

    /** 软删地址。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        addressService.delete(userId, id);
        return R.ok();
    }

    /** 设为默认地址。 */
    @PostMapping("/{id}/default")
    public R<Void> setDefault(@PathVariable Long id) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        addressService.setDefault(userId, id);
        return R.ok();
    }
}
