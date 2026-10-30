package com.petshop.module.svc.discount;

import com.petshop.common.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 折扣率解析（价目格子与美容单项共用）：折数 -> 十分位整数。 */
class DiscountKindTest {

    private static final String FMT = "格式不正确";
    private static final String RANGE = "范围不正确";

    @Test
    void parsesZheToTenths() {
        assertEquals(85, DiscountKind.parseTenths("8.5", FMT, RANGE));
        assertEquals(90, DiscountKind.parseTenths(" 9 ", FMT, RANGE));
        assertEquals(80, DiscountKind.parseTenths("8.0", FMT, RANGE));
        int ten = DiscountKind.parseTenths("10", FMT, RANGE);
        assertEquals(100, ten);
        assertEquals(1, DiscountKind.parseTenths("0.1", FMT, RANGE));
    }

    @Test
    void rejectsOutOfRangeOrTooPrecise() {
        for (String bad : new String[]{"0", "-1", "10.5", "8.55"}) {
            BizException e = assertThrows(BizException.class, () -> DiscountKind.parseTenths(bad, FMT, RANGE));
            assertEquals(RANGE, e.getMessage());
        }
    }

    @Test
    void rejectsNonNumericWithFormatMessage() {
        for (String bad : new String[]{"", null, "八折"}) {
            BizException e = assertThrows(BizException.class, () -> DiscountKind.parseTenths(bad, FMT, RANGE));
            assertEquals(FMT, e.getMessage());
        }
    }
}
