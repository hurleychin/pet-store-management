package com.petshop.module.svc;

/** 美容单项：价目表之下的独立可开单项目。 */
public class ServiceBeautyItem {

    private Long id;
    private String name;
    private Long categoryId;
    private int retailPriceCent;
    private String discountType;
    private int discountRate;
    private boolean showCashier;
    private boolean showShop;
    private boolean earnPoints;
    private int sortNo;
    private boolean isActive;
    // 展示字段
    private String categoryName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public int getRetailPriceCent() { return retailPriceCent; }
    public void setRetailPriceCent(int retailPriceCent) { this.retailPriceCent = retailPriceCent; }
    public String getDiscountType() { return discountType; }
    public void setDiscountType(String discountType) { this.discountType = discountType; }
    public int getDiscountRate() { return discountRate; }
    public void setDiscountRate(int discountRate) { this.discountRate = discountRate; }
    public boolean getShowCashier() { return showCashier; }
    public void setShowCashier(boolean showCashier) { this.showCashier = showCashier; }
    public boolean getShowShop() { return showShop; }
    public void setShowShop(boolean showShop) { this.showShop = showShop; }
    public boolean getEarnPoints() { return earnPoints; }
    public void setEarnPoints(boolean earnPoints) { this.earnPoints = earnPoints; }
    public int getSortNo() { return sortNo; }
    public void setSortNo(int sortNo) { this.sortNo = sortNo; }
    public boolean getIsActive() { return isActive; }
    public void setIsActive(boolean active) { isActive = active; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
}
