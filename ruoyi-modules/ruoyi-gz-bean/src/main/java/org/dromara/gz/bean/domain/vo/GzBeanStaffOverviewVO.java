package org.dromara.gz.bean.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * mp 店员/管理者拼豆预约经营概览 VO（GZ-BEAN-008 扩展 — mp 管理面板）。
 *
 * <p>给绑定店员（owner/staff）在小程序「预约看板」做到心里有数：4 个关键数 + 待到店预约列表。</p>
 *
 * @author kevin-coder (sensenran-guzi)
 */
@Data
@Builder
public class GzBeanStaffOverviewVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 今日预约总数（sess_date = 今天，全状态）。 */
    private long todayTotal;

    /** 今日待核销（sess_date = 今天 且 status=pending）。 */
    private long todayPending;

    /** 今日已核销（sess_date = 今天 且 status=used）。 */
    private long todayUsed;

    /** 未来待到店（sess_date > 今天 且 status=pending）。 */
    private long upcomingPending;

    /** 待到店预约列表（status=pending 且 sess_date >= 今天，按到店日/时段升序，至多 50 条）。 */
    private List<GzBeanBookingVO> pendingList;
}
