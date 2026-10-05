package com.xjjk.agent.chat.orchestration;

import com.xjjk.agent.customer.service.CustomerOrderQueryResult;
import com.xjjk.agent.customer.service.CustomerOrderResolution;
import com.xjjk.agent.order.domain.OrderCard;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 从客户订单结果中确定性选择最近的公开订单号。 */
public final class LatestOrderResolver {
    private static final List<DateTimeFormatter> LOCAL_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME);

    public Optional<ResolvedOrder> resolve(CustomerOrderQueryResult customerOrders) {
        if (customerOrders == null
                || customerOrders.resolution() != CustomerOrderResolution.FOUND
                || customerOrders.orders() == null) {
            return Optional.empty();
        }
        return customerOrders.orders().items().stream()
                .map(this::candidate)
                .flatMap(Optional::stream)
                .max(Comparator.comparing(Candidate::orderTime)
                        .thenComparing(Candidate::orderCode,
                                Comparator.reverseOrder()))
                .map(candidate -> new ResolvedOrder(
                        candidate.orderCode(), candidate.orderTimeText()));
    }

    private Optional<Candidate> candidate(OrderCard card) {
        if (card == null || card.orderCode() == null || card.orderCode().isBlank()
                || card.orderTime() == null || card.orderTime().isBlank()) {
            return Optional.empty();
        }
        return parse(card.orderTime().strip())
                .map(time -> new Candidate(card.orderCode().strip(), card.orderTime().strip(), time));
    }

    private Optional<LocalDateTime> parse(String value) {
        try {
            return Optional.of(OffsetDateTime.parse(value).toLocalDateTime());
        } catch (DateTimeParseException ignored) {
            // 订单服务当前常见格式没有时区，继续尝试本地时间格式。
        }
        for (DateTimeFormatter formatter : LOCAL_FORMATS) {
            try {
                return Optional.of(LocalDateTime.parse(value, formatter));
            } catch (DateTimeParseException ignored) {
                // 尝试下一个兼容格式。
            }
        }
        return Optional.empty();
    }

    public record ResolvedOrder(String orderCode, String orderTime) {
        public ResolvedOrder {
            if (orderCode == null || orderCode.isBlank()) {
                throw new IllegalArgumentException("解析出的订单号不能为空");
            }
            if (orderTime == null || orderTime.isBlank()) {
                throw new IllegalArgumentException("解析出的订单时间不能为空");
            }
            orderCode = orderCode.strip().toUpperCase(Locale.ROOT);
            orderTime = orderTime.strip();
        }
    }

    private record Candidate(String orderCode, String orderTimeText, LocalDateTime orderTime) {
    }
}
