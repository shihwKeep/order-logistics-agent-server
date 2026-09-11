package com.xjjk.agent.memory.service;

import com.xjjk.agent.chat.service.summary.SensitiveContentSanitizer;

import java.util.Objects;
import java.util.regex.Pattern;

public class MemorySensitiveContentPolicy {

    private static final Pattern CHINESE_ID = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern HEALTH = Pattern.compile("(?:诊断为|病历|病史|处方|疾病|高血压|糖尿病|癌症|肿瘤)");
    private static final Pattern BUSINESS_RECORD = Pattern.compile(
            "(?:订单号|订单编号|物流单号|运单号|快递单号|退款记录|退款单|退款编号)"
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
        return sanitized.equals(value)
                && !CHINESE_ID.matcher(value).find()
                && !PHONE.matcher(value).find()
                && !BANK_CARD.matcher(value).find()
                && !HEALTH.matcher(value).find()
                && !BUSINESS_RECORD.matcher(value).find();
    }
}
