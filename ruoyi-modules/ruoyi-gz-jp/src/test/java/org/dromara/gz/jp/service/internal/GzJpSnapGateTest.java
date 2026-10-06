package org.dromara.gz.jp.service.internal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
@Tag("dev")
class GzJpSnapGateTest {
    final ObjectMapper json = new ObjectMapper();
    final GzJpSnapGate gate = new GzJpSnapGate();
    JsonNode rules() throws Exception { return json.readTree("{\"blockKeywords\":[\"専用\",\"様専用\",\"さん専用\"],\"proxyRefuseKeywords\":[\"代行業者\",\"楽一番\"],\"manualKeywords\":[\"コメント必須\",\"即購入不可\",\"必ずコメント\"]}"); }
    ObjectNode item() { return json.createObjectNode().put("title_ja","アニメ").put("description_ja", "").put("status","on_sale").put("category_id","745").put("shipping_payer","seller"); }
    GzJpSnapGate.Decision evaluate(JsonNode item, String intro) throws Exception { return gate.evaluate(item,intro,rules(),new GzJpSnapFilter.Rules(java.util.Set.of("745"),java.util.Set.of("毒"))); }
    @Test void auto() throws Exception { var r=evaluate(item(),""); assertEquals("auto",r.result().getCode());assertTrue(r.purchasable()); }
    @Test void reservedBeforeManual() throws Exception { var r=evaluate(item().put("title_ja","田中様専用 コメント必須"),"");assertEquals("blocked",r.result().getCode()); }
    @Test void refuseTitle() throws Exception { var r=evaluate(item().put("title_ja","楽一番NG 即購入不可"),"");assertEquals("proxy_refuse:楽一番@title",r.reason()); }
    @Test void refuseDescriptionBeforeManual() throws Exception { var r=evaluate(item().put("description_ja","越境取引代行業者は即購入不可…必ずコメントして下さい"),"");assertEquals("blocked",r.result().getCode());assertEquals("proxy_refuse:代行業者@description",r.reason()); }
    @Test void refuseSellerBeforeManual() throws Exception { var r=evaluate(item().put("description_ja","コメント必須"),"代行業者NG");assertEquals("proxy_refuse:代行業者@seller_intro",r.reason()); }
    @Test void commentInDescription() throws Exception { assertEquals("manual",evaluate(item().put("description_ja","コメント必須"),"").result().getCode()); }
    @Test void buyerShipping() throws Exception { assertEquals("manual",evaluate(item().put("shipping_payer","buyer"),"").result().getCode()); }
    @Test void allNonSaleStatesUnpurchasable() throws Exception { for(String state:java.util.List.of("sold_out","trading","deleted")) {var r=evaluate(item().put("status",state),""); assertFalse(r.purchasable());assertEquals("item_not_on_sale",r.unavailableReason());} }
    @Test void categoryAndShops() throws Exception { assertEquals("blocked",evaluate(item().put("category_id","x"),"").result().getCode());assertEquals("blocked",evaluate(item().put("item_type","shops"),"").result().getCode()); }
    @Test void parentLineage() throws Exception { var v=item().put("category_id","123");v.putArray("category_lineage").add("745");assertTrue(evaluate(v,"").purchasable()); }
    @Test void blacklist() throws Exception { assertFalse(evaluate(item().put("title_ja","毒"),"").purchasable()); }
}
