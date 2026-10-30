package com.petshop.module.svc.discount;

import com.petshop.common.BizException;
import com.petshop.common.ErrorCode;

/**
 * 服务折扣：价目格子、美容单项、整单共用的优惠方式。
 * GROUP=会员组折扣（折扣率取客户所属会员组）/ FULL=全额（10 折）/ RATE=按折扣率（9、8、7 折或自定义）。
 * 折扣率存十分位整数：10=全额，9=9 折，95=9.5 折。
 */
public enum DiscountKind {
    GROUP("会员组折扣"),
    FULL("全额"),
    RATE("按折扣率");

    private final String label;

    DiscountKind(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 空值视为会员组折扣；无法识别的取值直接拒绝，避免静默改成全额或反方向打折。 */
    public static DiscountKind parse(String raw) {
        if (raw == null || raw.trim().isEmpty() || "GROUP".equals(raw.trim())) {
            return GROUP;
        }
        if ("FULL".equals(raw.trim())) {
            return FULL;
        }
        if ("RATE".equals(raw.trim())) {
            return RATE;
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "服务折扣取值不正确，请重新选择");
    }
}
