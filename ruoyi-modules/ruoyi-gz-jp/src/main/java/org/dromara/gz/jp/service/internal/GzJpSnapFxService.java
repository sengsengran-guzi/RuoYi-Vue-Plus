package org.dromara.gz.jp.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.jp.domain.entity.GzJpFxRate;
import org.dromara.gz.jp.mapper.GzJpFxRateMapper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

@Slf4j @Service @RequiredArgsConstructor
public class GzJpSnapFxService {
    public static final String BOC_URL = "https://www.boc.cn/sourcedb/whpj/";
    private final GzJpFxRateMapper rates;
    public GzJpFxRate manual(BigDecimal rate) { return append(rate, "manual"); }
    private GzJpFxRate append(BigDecimal rate, String source) {
        if (rate == null || rate.signum() <= 0 || rate.scale() > 6 || rate.compareTo(new BigDecimal("1000000")) >= 0)
            throw new IllegalArgumentException("汇率必须为大于0的每100日元人民币金额，最多6位小数");
        GzJpFxRate entity = GzJpFxRate.builder().ratePer100jpy(rate).source(source).fetchedAt(LocalDateTime.now()).build();
        rates.insert(entity); return entity;
    }
    public List<GzJpFxRate> history() {
        return rates.selectList(new LambdaQueryWrapper<GzJpFxRate>().orderByDesc(GzJpFxRate::getFetchedAt).orderByDesc(GzJpFxRate::getId).last("LIMIT 50"));
    }
    /** On failure append nothing: previous observations and manual overrides remain intact. */
    public void fetch() {
        try {
            var request = HttpRequest.newBuilder(URI.create(BOC_URL)).timeout(Duration.ofSeconds(15)).GET().build();
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("BOC HTTP " + response.statusCode());
            BigDecimal rate = parse(response.body());
            TenantHelper.dynamic("1001", () -> append(rate, "boc"));
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); log.warn("BOC rate fetch interrupted; keeping previous observation", ex); }
        catch (Exception ex) { log.warn("BOC rate fetch failed; keeping previous observation", ex); }
    }
    /** Column identity, not ordinal, selects spot selling rate. Official table unit is CNY per 100 foreign units. */
    public BigDecimal parse(String html) {
        var tables = Pattern.compile("(?is)<table\\b[^>]*>(.*?)</table>").matcher(html);
        while (tables.find()) {
            List<String> headers = null;
            var rows = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>").matcher(tables.group(1));
            while (rows.find()) {
                List<String> cells = new ArrayList<>();
                var values = Pattern.compile("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]>").matcher(rows.group(1));
                while (values.find()) cells.add(values.group(1).replaceAll("(?is)<[^>]+>", "").replace("&nbsp;", " ").trim());
                if (cells.contains("货币名称") && cells.contains("现汇卖出价")) { headers = cells; continue; }
                if (headers == null || cells.size() != headers.size()) continue;
                if ("日元".equals(cells.get(headers.indexOf("货币名称")))) {
                    BigDecimal rate = new BigDecimal(cells.get(headers.indexOf("现汇卖出价")));
                    if (rate.signum() <= 0 || rate.scale() > 6) throw new IllegalArgumentException("Invalid BOC JPY rate");
                    return rate;
                }
            }
        }
        throw new IllegalArgumentException("BOC 日元现汇卖出价列缺失");
    }
}
