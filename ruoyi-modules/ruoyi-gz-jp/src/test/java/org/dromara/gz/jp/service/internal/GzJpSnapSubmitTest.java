package org.dromara.gz.jp.service.internal;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.gz.jp.domain.dto.*;
import org.dromara.gz.jp.domain.bo.GzJpSnapSubmitBo;
import org.dromara.gz.jp.domain.entity.*;
import org.dromara.gz.jp.domain.vo.*;
import org.dromara.gz.jp.mapper.*;
import org.dromara.gz.common.pay.mapper.GzPayTransactionMapper;
import org.dromara.gz.common.pay.domain.entity.GzPayTransaction;
import org.dromara.gz.common.pay.service.internal.*;
import org.dromara.gz.common.pay.service.IGzPayTransactionService;
import org.dromara.gz.common.service.IGzUserService;
import org.dromara.gz.user.service.IGzUserAddressService;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Tag("dev")
class GzJpSnapSubmitTest {
    GzJpSnapQuoteService quotes=mock(GzJpSnapQuoteService.class);GzJpSnapItemMapper items=mock(GzJpSnapItemMapper.class);
    GzJpOrderMapper orders=mock(GzJpOrderMapper.class);GzJpOrderItemMapper rows=mock(GzJpOrderItemMapper.class);
    IGzUserAddressService addresses=mock(IGzUserAddressService.class);IGzUserService users=mock(IGzUserService.class);
    IGzPayTransactionService payments=mock(IGzPayTransactionService.class);GzPayTransactionMapper transactions=mock(GzPayTransactionMapper.class);
    IWechatPayClient wechat=mock(IWechatPayClient.class);PayAppidResolver appids=mock(PayAppidResolver.class);
    GzJpSnapSubmitService service=new GzJpSnapSubmitService(quotes,mock(GzJpSnapReadService.class),items,orders,rows,addresses,users,payments,transactions,wechat,appids,mock(PayOrderNoGenerator.class),new ObjectMapper(),mock(TransactionTemplate.class),mock(GzJpSnapImageService.class));
    GzJpSnapQuoteSnapshot quote=new GzJpSnapQuoteSnapshot("m1",1000,BigDecimal.ONE,BigDecimal.ZERO,1000,10,0,1010,System.currentTimeMillis(),System.currentTimeMillis()+600000,"auto","seller");
    GzJpSnapSubmitBo bo=new GzJpSnapSubmitBo();GzJpSnapItem item=new GzJpSnapItem();GzJpOrderItem held=new GzJpOrderItem();GzJpOrder order=new GzJpOrder();
    GzJpSnapDetailVO live(long price,String status,String payer,String gate){return new GzJpSnapDetailVO("m1","A","A","","",price,status,payer,"","",java.util.List.of(),true,null,1010L,gate,null,null,null);}
    @BeforeEach void before(){bo.setQuoteId("q");bo.setAddressId(8L);bo.setAckNoCancel(true);item.setId(3L);item.setActiveOrderItemId(2L);held.setId(2L);held.setOrderId(1L);held.setFulfillStatus("purchasing");order.setId(1L);order.setOrderNo("JPO-test");order.setUserId(7L);order.setBusinessStatus("created");order.setTotalAmountCent(1010L);when(quotes.require("q","m1")).thenReturn(quote);when(items.lockExternal("m1")).thenReturn(item);when(rows.lockById(2L)).thenReturn(held);when(orders.lockById(1L)).thenReturn(order);}
    @Test void soldRefused(){assertEquals(4125,assertThrows(ServiceException.class,()->GzJpSnapSubmitService.validateLive(quote,live(1000,"sold_out","seller","blocked"))).getCode());}
    @Test void priceIncreaseRefused(){assertEquals(4126,assertThrows(ServiceException.class,()->GzJpSnapSubmitService.validateLive(quote,live(1001,"on_sale","seller","auto"))).getCode());}
    @Test void shippingChangedRefused(){assertThrows(ServiceException.class,()->GzJpSnapSubmitService.validateLive(quote,live(900,"on_sale","buyer","manual")));}
    @Test void cheaperAllowed(){assertDoesNotThrow(()->GzJpSnapSubmitService.validateLive(quote,live(900,"on_sale","seller","auto")));}
    @Test void ackRequiredBeforeExternalRead(){bo.setAckNoCancel(false);assertEquals(4129,assertThrows(ServiceException.class,()->service.submit(bo,7L)).getCode());verifyNoInteractions(quotes);}
    @Test void quoteInvalidBeforeLock(){when(quotes.require("q","m1")).thenThrow(new ServiceException("expired",4123));assertThrows(ServiceException.class,()->service.submitLocked(bo,7L,quote,live(1000,"on_sale","seller","auto")));verify(items,never()).lockExternal(anyString());}
    @Test void someoneElseBlocked(){assertEquals(4127,assertThrows(ServiceException.class,()->service.submitLocked(bo,8L,quote,live(1000,"on_sale","seller","auto"))).getCode());verify(orders,never()).insert(any(GzJpOrder.class));}
    @Test void ownerPaidBlocked(){order.setBusinessStatus("paid");assertEquals(4128,assertThrows(ServiceException.class,()->service.submitLocked(bo,7L,quote,live(1000,"on_sale","seller","auto"))).getCode());}
    @Test void ownerCreatedReusesOriginalPayment(){var pending=new GzPayTransaction();pending.setPrepayId("original");pending.setOutTradeNo("JP-pay");when(transactions.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(pending);when(appids.resolveForCurrentApp()).thenReturn("app");when(wechat.buildPayParams("original","app")).thenReturn(new IWechatPayClient.JsapiPayParams("1","n","prepay_id=original","RSA","s"));var response=service.submitLocked(bo,7L,quote,live(1000,"on_sale","seller","auto"));assertEquals("JPO-test",response.getOrderNo());verifyNoInteractions(payments);verify(orders,never()).insert(any(GzJpOrder.class));}
    @Test void releaseHasConditionalMapperGuard(){var locks=new GzJpSnapLockService(items,rows);when(items.releaseSnapLock(2L)).thenReturn(0);assertEquals(0,locks.releaseSnapLock(2L));verify(items).releaseSnapLock(2L);}
}
