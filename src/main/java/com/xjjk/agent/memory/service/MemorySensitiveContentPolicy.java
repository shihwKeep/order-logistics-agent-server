package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer;

import java.text.Normalizer;
import java.util.Objects;
import java.util.regex.Pattern;

public class MemorySensitiveContentPolicy {

    private static final Pattern CHINESE_ID = Pattern.compile(
            "(?:\\d{15}|\\d{17}[0-9Xx])");
    private static final Pattern PHONE = Pattern.compile("(?:86)?1[3-9]\\d{9}");
    private static final Pattern BANK_CARD = Pattern.compile("\\d{16,19}");
    private static final Pattern HEALTH = Pattern.compile(
            "(?:诊断为|患者|病人|病历|病史|处方|疾病|高血压|糖尿病|癌症|肿瘤|"
                    + "肝炎|乙肝|甲肝|丙肝|艾滋|抑郁|焦虑|服用|用药|药物|药品|"
                    + "过敏|手术|症状|阿司匹林)"
    );
    private static final Pattern BUSINESS_RECORD = Pattern.compile(
            "(?:订单号|订单编号|物流单号|运单号|快递单号|退款记录|退款单|退款编号)"
    );
    private static final Pattern EXACT_ADDRESS = Pattern.compile(
            "(?:家庭地址|住址|详细地址|收货地址|地址是|住所|居住地|现住址|居住地址|住宅地址)|"
                    + "(?:(?:我家在|家在|住在|居住于|家住|家庭住在).{0,32}"
                    + "(?:路|街|道|大道|巷|号))|"
                    + "(?:[\\u4e00-\\u9fa5]{2,}(?:省|市|自治区).{0,24}(?:区|县|旗)"
                    + ".{0,24}(?:路|街|道|大道|巷).{0,16}\\d+号)"
    );
    private static final Pattern CUSTOMER_DATA = Pattern.compile(
            "(?:客户|顾客|会员|联系人)(?:姓名|名称|信息|资料|手机号|电话|地址|身份证)"
    );
    private static final Pattern PAYMENT_OR_RESULT = Pattern.compile(
            "(?:(?:支付|实付|付款|退款|赔付|结算)(?:金额)?[^。；,，]{0,16}(?:[¥￥]\\s*\\d|\\d+(?:\\.\\d+)?\\s*元))|"
                    + "(?:已退款|退款成功|已付款|支付成功|已发货|已签收|审批通过|审批拒绝)"
    );
    private static final Pattern ENTERPRISE_OR_PRODUCT_RULE = Pattern.compile(
            "(?:(?:公司|企业|部门|平台)(?:制度|规定|政策|流程|规则)|"
                    + "(?:商品|产品|退款|退货|换货|售后)(?:制度|规定|政策|流程|规则))"
    );

    private final SensitiveContentSanitizer sanitizer;

    public MemorySensitiveContentPolicy(SensitiveContentSanitizer sanitizer) {
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
    }

    public boolean isAllowed(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        final String sanitized;
        try {
            sanitized = sanitizer.sanitize(value);
        } catch (RuntimeException exception) {
            return false;
        }
        String privacyNormalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        String digitsOnly = privacyNormalized.replaceAll("[^0-9]", "");
        String digitsAndX = privacyNormalized.replaceAll("[^0-9Xx]", "");
        return sanitized.equals(value)
                && !CHINESE_ID.matcher(digitsAndX).find()
                && !PHONE.matcher(digitsOnly).find()
                && !BANK_CARD.matcher(digitsOnly).find()
                && !HEALTH.matcher(value).find()
                && !BUSINESS_RECORD.matcher(value).find()
                && !EXACT_ADDRESS.matcher(value).find()
                && !CUSTOMER_DATA.matcher(value).find()
                && !PAYMENT_OR_RESULT.matcher(value).find()
                && !ENTERPRISE_OR_PRODUCT_RULE.matcher(value).find();
    }
}
