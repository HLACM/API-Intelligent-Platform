package com.czq.apithirdpartyservices.controller;

import cn.hutool.core.date.DateUtil;
import cn.hutool.extra.qrcode.QrCodeUtil;
import cn.hutool.extra.qrcode.QrConfig;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePrecreateModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.czq.apicommon.common.BaseResponse;
import com.czq.apicommon.common.ResultUtils;
import com.czq.apithirdpartyservices.config.AliPayConfig;
import com.czq.apithirdpartyservices.model.dto.AlipayRequest;
import com.czq.apithirdpartyservices.model.entity.AlipayInfo;
import com.czq.apithirdpartyservices.service.AlipayInfoService;
import com.czq.apithirdpartyservices.utils.OrderPaySuccessMqUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.czq.apicommon.constant.RedisConstant.*;

/**
 * 支付宝沙箱支付
 */
@Slf4j
@RestController
@RequestMapping("/alipay")
public class AliPayController {


    @Resource
    private AliPayConfig aliPayConfig;
    @Resource
    private AlipayInfoService alipayInfoService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OrderPaySuccessMqUtils orderPaySuccessMqUtils;


    /**
     * 调用后台支付接口，填写支付商品的相关信息，由后台调用支付宝的支付二维码接口并回显
     * @param alipayRequest
     * @return 二维码 Base64 编码字符串
     * @throws AlipayApiException
     */
    @PostMapping("/payCode")
    public BaseResponse<String> payCode(@RequestBody AlipayRequest alipayRequest) throws AlipayApiException {
        //支付宝交易凭证号
        String outTradeNo = alipayRequest.getTraceNo();
        //交易名称
        String subject = alipayRequest.getSubject() ;
        //总金额
        double totalAmount = alipayRequest.getTotalAmount();

        //使用支付宝 SDK 中的 DefaultAlipayClient 类创建了一个支付宝客户端对象。往这个客户端对象中传入各种支付宝详细参数
        AlipayClient alipayClient = new DefaultAlipayClient(aliPayConfig.getGatewayUrl(),
                aliPayConfig.getAppId(),
                aliPayConfig.getPrivateKey(),
                "json",aliPayConfig.getCharset(),
                aliPayConfig.getPublicKey(),aliPayConfig.getSignType());

        //创建一个支付宝预下单请求对象
        AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
        //支付宝预创建交易的模型类，用于设置预下单请求的业务参数
        AlipayTradePrecreateModel model = new AlipayTradePrecreateModel();

        //请求的通知地址，这个通知地址用于接收支付宝支付结果的异步通知
        request.setNotifyUrl("http://localhost:9000/api/third/alipay/notify");

        //将模型对象设置到预下单请求对象中
        request.setBizModel(model);
        //设置预下单参数交易号、交易金额、交易名称
        model.setOutTradeNo(outTradeNo);
        model.setTotalAmount(String.valueOf(totalAmount));
        model.setSubject(subject);

        AlipayTradePrecreateResponse response = alipayClient.execute(request);
        log.info("响应支付二维码详情："+response.getBody());
        //从预下单响应对象中获取支付二维码的内容，并使用 QrCodeUtil 工具类生成二维码的 Base64 编码字符串。
        String base64 = QrCodeUtil.generateAsBase64(response.getQrCode(), new QrConfig(300, 300), "png");

        return ResultUtils.success(base64);
    }


    /**
     * 支付成功回调,注意这里必须是POST接口
     * @param request
     * @return
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)//捕获到任何异常时都会回滚事务
    @PostMapping("/notify")
    public synchronized void payNotify(HttpServletRequest request) throws Exception {
        //首先检查支付宝通知中的交易状态（trade_status），如果是 "TRADE_SUCCESS" 表示支付成功，才进行后续处理
        if (request.getParameter("trade_status").equals("TRADE_SUCCESS")) {
            Map<String, String> params = new HashMap<>();
            Map<String, String[]> requestParams = request.getParameterMap();
            for (String name : requestParams.keySet()) {
                params.put(name, request.getParameter(name));
            }

            // 对支付宝通知参数进行验签，确保通知是由支付宝发送的，并且没有被篡改
            if (AlipaySignature.rsaCheckV1 (params, aliPayConfig.getPublicKey(), aliPayConfig.getCharset(), aliPayConfig.getSignType())) {
                //验证成功
                log.info("支付成功:{}",params);
                // 幂等性保证：判断该订单号是否被处理过，解决因为多次重复收到阿里的回调通知导致的订单重复处理的问题
                Object outTradeNo = stringRedisTemplate.opsForValue().get(ALIPAY_TRADE_SUCCESS_RECORD + params.get("out_trade_no"));
                if (null == outTradeNo ){
                    // 验签通过，将订单信息存入数据库
                    AlipayInfo alipayInfo = new AlipayInfo();
                    alipayInfo.setSubject(params.get("subject"));
                    alipayInfo.setTradeStatus(params.get("trade_status"));
                    alipayInfo.setTradeNo(params.get("trade_no"));
                    alipayInfo.setOrderNumber(params.get("out_trade_no"));
                    alipayInfo.setTotalAmount(Double.valueOf(params.get("total_amount")));
                    alipayInfo.setBuyerId(params.get("buyer_id"));
                    alipayInfo.setGmtPayment(DateUtil.parse(params.get("gmt_payment")));
                    alipayInfo.setBuyerPayAmount(Double.valueOf(params.get("buyer_pay_amount")));
                    alipayInfoService.save(alipayInfo);
                    //记录处理成功的订单，实现订单幂等性
                    stringRedisTemplate.opsForValue().set(ALIPAY_TRADE_SUCCESS_RECORD +alipayInfo.getOrderNumber(),EXIST_KEY_VALUE,30, TimeUnit.MINUTES);
                    //修改数据库，完成整个订单功能
                    orderPaySuccessMqUtils.sendOrderPaySuccess(params.get("out_trade_no"));
                }
            }
        }
    }




}
