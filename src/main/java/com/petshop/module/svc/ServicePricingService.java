package com.petshop.module.svc;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 服务折扣计价：成交价 = round(标价 × 折扣率 / 10)，行折扣 = 标价 − 成交价。折扣率为十分位整数。 */
@Service
public class ServicePricingService {

    /**
     * 按折扣率（十分位整数：10=全额、9=9 折、85=8.5 折）计算成交价，四舍五入到分。
     * 折不动的数据不改价：折扣率越界时按全额处理。
     */
    public int dealPrice(int listPriceCent, int discountRate) {
        int rate = discountRate <= 0 || discountRate > 10 * 10 ? 100 : discountRate;
        long deal = BigDecimal.valueOf((long) listPriceCent * rate)
                .divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP)
                .longValueExact();
        return (int) deal;
    }
}
