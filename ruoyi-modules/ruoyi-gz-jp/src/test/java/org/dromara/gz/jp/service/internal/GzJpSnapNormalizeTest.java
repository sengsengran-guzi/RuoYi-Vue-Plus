package org.dromara.gz.jp.service.internal;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.jp.domain.entity.GzJpSnapTerm;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev")
class GzJpSnapNormalizeTest extends Snap302Fixture {
    @Test void bareIdAndSharedLinkRedirectWithoutSearch() {
        assertEquals("m99000000001", normalizer.normalize(" m99000000001 ", List.of()).redirectItemId());
        assertEquals("m99000000001", normalizer.normalize("商品 https://jp.mercari.com/item/m99000000001?foo=1", List.of()).redirectItemId());
        verifyNoInteractions(gateway);
    }
    @Test void shortLinkResolvesInsteadOfKeywordSearch() {
        when(gateway.resolve("https://merc.li/synthetic202")).thenReturn(node("{\"external_id\":\"m99000000001\"}"));
        assertEquals("m99000000001", normalizer.normalize("https://merc.li/synthetic202", List.of()).redirectItemId());
        verify(gateway).resolve("https://merc.li/synthetic202"); verify(gateway, never()).search(any(), any(), anyInt(), any());
    }
    @Test void shopsLinkAndResolvedShopsReject() {
        var ex = assertThrows(ServiceException.class, () -> normalizer.normalize("https://jp.mercari.com/shops/product/ABC", List.of()));
        assertEquals(4117, ex.getCode());
        when(gateway.resolve(any())).thenReturn(node("{\"external_id\":\"SYNTHETICshops01\"}"));
        assertEquals(4117, assertThrows(ServiceException.class, () -> normalizer.normalize("https://merc.li/shops", List.of())).getCode());
    }
    @Test void longestMatchWinsOverEarlierSortAndReplacementIsNotReprocessed() {
        var shortTerm = GzJpSnapTerm.builder().zhTerm("蓝色").jaTerm("WRONG").sortNo(1).enabled(true).build();
        var longTerm = GzJpSnapTerm.builder().zhTerm("蓝色监狱").jaTerm("ブルーロック").sortNo(99).enabled(true).build();
        assertEquals("ブルーロック 缶バッジ", normalizer.normalize("蓝色监狱 徽章", List.of(shortTerm, longTerm)).query());
    }
    @Test void disabledTermIgnoredAndJapaneseUnchanged() {
        var disabled = GzJpSnapTerm.builder().zhTerm("缶バッジ").jaTerm("WRONG").enabled(false).build();
        assertEquals("ブルーロック 缶バッジ", normalizer.normalize("ブルーロック 缶バッジ", List.of(disabled)).query());
    }
    @Test void translationFailureFallsBackAndSuccessCachesForSevenDays() {
        var broken = new GzJpSnapTranslator(properties, cache) { @Override protected String perform(String text, String from, String to) { throw new IllegalStateException("offline"); } };
        assertEquals("未录词", new GzJpSnapNormalizer(gateway, broken).normalize("未录词", List.of()).query());
        var translated = spy(translator);
        assertEquals("缶バッジ", translated.translate("徽章", "zh", "ja", "query"));
        assertEquals("缶バッジ", translated.translate("徽章", "zh", "ja", "query"));
        try { verify(translated, times(1)).perform("徽章", "zh", "ja"); } catch (Exception ex) { fail(ex); }
    }
}
