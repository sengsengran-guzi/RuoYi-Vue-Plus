package org.dromara.gz.jp.service.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.CacheNames;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.jp.mapper.GzJpSnapRuntimeConfigMapper;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.*;

/** Restricted admin JSON configuration. No default business prices are invented. */
@Service @RequiredArgsConstructor
public class GzJpSnapConfigService {
    private final GzJpSnapRuntimeConfigMapper configs;
    private final ObjectMapper json;
    private final CacheManager cacheManager;
    private static final Map<String,Set<String>> FIELDS=Map.of(
        "pricing",Set.of("markupRatio","fixedFeeCent","estShippingCent","quoteTtlSec"),
        "filter",Set.of("categoryWhitelist","keywordBlacklist"),
        "gate",Set.of("blockKeywords","proxyRefuseKeywords","manualKeywords"),
        "trade",Set.of("rateDeadlineHours","patrolMinutes"));
    private String key(String key) {
        if(!FIELDS.containsKey(key))throw new ServiceException("不支持的配置项 / Unsupported config key");
        return "gz.jp.snap."+key;
    }
    public JsonNode read(String key) {
        String raw=configs.read(key(key));
        if(raw==null)throw new ServiceException("配置不存在 / Config missing");
        try{return json.readTree(raw);}catch(java.io.IOException e){throw new IllegalStateException("Invalid snap config JSON",e);}
    }
    @Transactional(rollbackFor=Exception.class)
    public void save(String key,JsonNode input,Long operator) {
        String full=key(key);
        ObjectNode normalized=normalize(key,input);
        if(configs.write(full,normalized.toString(),operator)!=1)throw new ServiceException("配置不存在 / Config missing");
        Runnable evict=()->TenantHelper.dynamic("1001",()->Objects.requireNonNull(cacheManager.getCache(CacheNames.SYS_CONFIG)).evictIfPresent(full));
        // evict would register a second afterCommit while synchronization remains active.
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization(){@Override public void afterCommit(){evict.run();}});
        else evict.run();
    }
    ObjectNode normalize(String key,JsonNode input) {
        key(key);
        if(input==null || !input.isObject())throw invalid("JSON");
        Set<String> found=new HashSet<>();input.fieldNames().forEachRemaining(found::add);
        if(!found.equals(FIELDS.get(key)))throw invalid("fields");
        ObjectNode result=((ObjectNode)input).deepCopy();
        switch(key) {
            case "pricing" -> {
                JsonNode ratio=result.get("markupRatio");
                if(!ratio.isNull() && (!ratio.isNumber() || ratio.decimalValue().compareTo(BigDecimal.ZERO)<0 || ratio.decimalValue().compareTo(BigDecimal.ONE)>0))throw invalid("markupRatio [0,1]");
                number(result,"fixedFeeCent",0,Long.MAX_VALUE,true);number(result,"estShippingCent",0,Long.MAX_VALUE,true);
                number(result,"quoteTtlSec",60,1800,false);
            }
            case "trade" -> {number(result,"rateDeadlineHours",1,240,false);number(result,"patrolMinutes",5,120,false);}
            default -> {
                for(String field:FIELDS.get(key)) {
                    JsonNode array=result.get(field);if(!array.isArray())throw invalid(field);
                    LinkedHashSet<String> words=new LinkedHashSet<>();
                    for(JsonNode item:array){if(!item.isTextual())throw invalid(field);String word=item.asText().strip();if(!word.isEmpty())words.add(word);}
                    var clean=result.putArray(field);words.forEach(clean::add);
                }
            }
        }
        return result;
    }
    private void number(ObjectNode n,String field,long min,long max,boolean nullable) {
        JsonNode value=n.get(field);if(nullable && value.isNull())return;
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong()<min || value.asLong()>max)throw invalid(field+" ["+min+","+max+"]");
    }
    private ServiceException invalid(String field){return new ServiceException("配置值不合法 / Invalid config: "+field);}
}
