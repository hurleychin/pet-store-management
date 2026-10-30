package com.petshop.module.customer;

/** 会员组：服务折扣取「会员组折扣」时按其折扣率计价；客户未设会员组视为散客组。 */
public class MemberGroup {

    /** 散客组固定 id，由 schema.sql / v1.3 迁移预置 */
    public static final long WALK_IN_ID = 1L;
    /** 全额折扣率（十分位整数） */
    public static final int FULL_RATE = 10;

    private Long id;
    private String name;
    private int discountRate;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getDiscountRate() { return discountRate; }
    public void setDiscountRate(int discountRate) { this.discountRate = discountRate; }
}
