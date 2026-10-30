package com.petshop.module.svc;

/** 价目行：宠物类型 + 自定义描述，显示为「狗：0-3kg」；肩高/毛长/品种为仅展示的高级标签。 */
public class ServicePriceRow {

    private Long id;
    private String animal;
    private String description;
    private String dogSize;
    private String furLength;
    private String breedTags;
    private int sortNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAnimal() { return animal; }
    public void setAnimal(String animal) { this.animal = animal; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDogSize() { return dogSize; }
    public void setDogSize(String dogSize) { this.dogSize = dogSize; }
    public String getFurLength() { return furLength; }
    public void setFurLength(String furLength) { this.furLength = furLength; }
    public String getBreedTags() { return breedTags; }
    public void setBreedTags(String breedTags) { this.breedTags = breedTags; }
    public int getSortNo() { return sortNo; }
    public void setSortNo(int sortNo) { this.sortNo = sortNo; }
}
