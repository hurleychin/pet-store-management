package com.petshop.module.svc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 价目相关审计的改前 / 改后快照，统一写成 JSON。
 * 金额以分、折扣率以十分位整数（9=9 折、85=8.5 折、GROUP/FULL 存 10）原样记录，不做展示口径转换。
 */
final class SvcAuditSnapshots {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SvcAuditSnapshots() {
    }

    static String priceCell(Long categoryId, Integer priceCent, String discountType, Integer discountRate) {
        ObjectNode n = JSON.createObjectNode();
        n.put("categoryId", categoryId);
        n.put("priceCent", priceCent);
        n.put("discountType", discountType);
        n.put("discountRate", discountRate);
        return n.toString();
    }

    static String priceRow(ServicePriceRow r) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", r.getId());
        n.put("animal", r.getAnimal());
        n.put("description", r.getDescription());
        n.put("dogSize", r.getDogSize());
        n.put("furLength", r.getFurLength());
        n.put("breedTags", r.getBreedTags());
        n.put("sortNo", r.getSortNo());
        return n.toString();
    }

    static String category(ServiceCategory c) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", c.getId());
        n.put("name", c.getName());
        n.put("degree", c.getDegree());
        n.put("sortNo", c.getSortNo());
        n.put("isVisible", c.getIsVisible());
        return n.toString();
    }

    static String beautyItem(ServiceBeautyItem i) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", i.getId());
        n.put("name", i.getName());
        n.put("categoryId", i.getCategoryId());
        n.put("categoryName", i.getCategoryName());
        n.put("retailPriceCent", i.getRetailPriceCent());
        n.put("discountType", i.getDiscountType());
        n.put("discountRate", i.getDiscountRate());
        n.put("showCashier", i.getShowCashier());
        n.put("showShop", i.getShowShop());
        n.put("earnPoints", i.getEarnPoints());
        return n.toString();
    }

    /** 批量设置折扣的改后快照：本项目的折扣 + 本次批量涉及的全部项目 id。 */
    static String beautyItemDiscount(ServiceBeautyItem i, java.util.List<Long> batchIds) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", i.getId());
        n.put("name", i.getName());
        n.put("discountType", i.getDiscountType());
        n.put("discountRate", i.getDiscountRate());
        ArrayNode ids = n.putArray("ids");
        for (Long id : batchIds) {
            ids.add(id);
        }
        return n.toString();
    }
}
