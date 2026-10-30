package com.petshop.module.svc;

/** 价格格子：价目行 × 服务分类；金额以分存储，服务折扣按 GROUP / FULL / RATE 记录。 */
public class ServicePriceCell {

    private Long id;
    private Long rowId;
    private Long categoryId;
    private int priceCent;
    private String discountType;
    private int discountRate;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRowId() { return rowId; }
    public void setRowId(Long rowId) { this.rowId = rowId; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public int getPriceCent() { return priceCent; }
    public void setPriceCent(int priceCent) { this.priceCent = priceCent; }
    public String getDiscountType() { return discountType; }
    public void setDiscountType(String discountType) { this.discountType = discountType; }
    public int getDiscountRate() { return discountRate; }
    public void setDiscountRate(int discountRate) { this.discountRate = discountRate; }
}
