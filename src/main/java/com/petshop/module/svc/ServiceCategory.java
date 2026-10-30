package com.petshop.module.svc;

/** 服务分类：价目表的列，归入洗护类（SHOWER）或美容类（BEAUTY）。 */
public class ServiceCategory {

    private Long id;
    private String name;
    private String degree;
    private int sortNo;
    private boolean isVisible;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDegree() { return degree; }
    public void setDegree(String degree) { this.degree = degree; }
    public int getSortNo() { return sortNo; }
    public void setSortNo(int sortNo) { this.sortNo = sortNo; }
    public boolean getIsVisible() { return isVisible; }
    public void setIsVisible(boolean visible) { isVisible = visible; }
}
