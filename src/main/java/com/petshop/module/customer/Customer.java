package com.petshop.module.customer;

import java.time.LocalDateTime;

public class Customer {

    private Long id;
    private String name;
    private String phone;
    private String source;
    private String remark;
    private boolean isActive;
    private LocalDateTime createdAt;
    /** v1.3 会员组 id；为空表示散客组（服务折扣「会员组折扣」按散客组折扣率计价） */
    private Long memberGroupId;
    /** 展示字段：会员组名称与折扣率（十分位整数，10=不打折） */
    private String memberGroupName;
    private Integer memberGroupRate;

    /** 列表页附带：名下宠物数 */
    private Long petCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public boolean getIsActive() { return isActive; }
    public void setIsActive(boolean active) { isActive = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getMemberGroupId() { return memberGroupId; }
    public void setMemberGroupId(Long memberGroupId) { this.memberGroupId = memberGroupId; }
    public String getMemberGroupName() { return memberGroupName; }
    public void setMemberGroupName(String memberGroupName) { this.memberGroupName = memberGroupName; }
    public Integer getMemberGroupRate() { return memberGroupRate; }
    public void setMemberGroupRate(Integer memberGroupRate) { this.memberGroupRate = memberGroupRate; }
    public Long getPetCount() { return petCount; }
    public void setPetCount(Long petCount) { this.petCount = petCount; }
}
