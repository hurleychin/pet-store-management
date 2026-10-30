package com.petshop.module.svc;

import com.petshop.common.BizException;
import com.petshop.common.ErrorCode;
import com.petshop.common.Money;
import com.petshop.module.svc.discount.DiscountKind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 美容单项维护：新增 / 批量新增 / 编辑 / 删除。写操作仅管理员可进（由 AdminInterceptor 兜底）。 */
@Service
public class ServiceBeautyItemService {

    private final ServiceBeautyItemMapper mapper;
    private final ServiceCategoryMapper categoryMapper;

    public ServiceBeautyItemService(ServiceBeautyItemMapper mapper, ServiceCategoryMapper categoryMapper) {
        this.mapper = mapper;
        this.categoryMapper = categoryMapper;
    }

    public List<ServiceBeautyItem> findAll() {
        return mapper.findAll();
    }

    /** 新增（id 为空）或编辑：名称、分类、零售价、服务折扣必填。 */
    @Transactional(rollbackFor = Exception.class)
    public ServiceBeautyItem save(Long id, String name, Long categoryId, String retailPriceYuan,
                                  String discountType, String discountRate) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "美容单项名称不能为空");
        }
        checkCategory(categoryId);
        int price = price(retailPriceYuan);

        ServiceBeautyItem item;
        if (id == null) {
            item = new ServiceBeautyItem();
            item.setSortNo(nextSortNo());
            item.setShowCashier(true);
            item.setShowShop(true);
            item.setEarnPoints(false);
            item.setIsActive(true);
        } else {
            item = mapper.findById(id);
            if (item == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "美容单项不存在，请刷新后重试");
            }
        }
        item.setName(n);
        item.setCategoryId(categoryId);
        item.setRetailPriceCent(price);
        applyDiscount(item, discountType, discountRate);
        if (id == null) {
            mapper.insert(item);
        } else {
            mapper.update(item);
        }
        return item;
    }

    /**
     * 批量新增：每行「名称,零售价」，分类与折扣同样本；空行忽略。
     * 整批校验通过才写入，任一行不合法则整批不落库。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<ServiceBeautyItem> batchAdd(String categoryIdRaw, String discountType, String discountRate, String lines) {
        Long categoryId = parseCategoryId(categoryIdRaw);
        checkCategory(categoryId);
        DiscountKind kind = DiscountKind.parse(discountType);
        int rate = rate(kind, discountRate);
        if (lines == null || lines.trim().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请至少填写一行：名称,零售价");
        }
        List<ServiceBeautyItem> created = new ArrayList<>();
        String[] rows = lines.split("\\r?\\n");
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i].trim();
            if (row.isEmpty()) {
                continue;
            }
            String[] parts = row.split("[,，]", -1);
            if (parts.length != 2 || parts[0].trim().isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "第 " + (i + 1) + " 行格式不正确，应为「名称,零售价」");
            }
            ServiceBeautyItem item = new ServiceBeautyItem();
            item.setName(parts[0].trim());
            item.setCategoryId(categoryId);
            try {
                item.setRetailPriceCent(price(parts[1]));
            } catch (BizException e) {
                throw new BizException(ErrorCode.PARAM_INVALID, "第 " + (i + 1) + " 行零售价不正确");
            }
            item.setDiscountType(kind.name());
            item.setDiscountRate(rate);
            item.setShowCashier(true);
            item.setShowShop(true);
            item.setEarnPoints(false);
            item.setIsActive(true);
            created.add(item);
        }
        if (created.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请至少填写一行：名称,零售价");
        }
        int sort = nextSortNo();
        for (ServiceBeautyItem item : created) {
            item.setSortNo(sort++);
            mapper.insert(item);
        }
        return created;
    }

    /** 保存银（收银台）/ 微（网店）/ 积（积分）三个开关 */
    @Transactional(rollbackFor = Exception.class)
    public ServiceBeautyItem saveSwitches(Long id, boolean showCashier, boolean showShop, boolean earnPoints) {
        ServiceBeautyItem item = requireExists(id);
        item.setShowCashier(showCashier);
        item.setShowShop(showShop);
        item.setEarnPoints(earnPoints);
        mapper.update(item);
        return item;
    }

    /** 某分类下的美容单项（按排序号），用于「批量设置折扣」的分页签。 */
    public List<ServiceBeautyItem> findByCategory(Long categoryId) {
        List<ServiceBeautyItem> result = new ArrayList<>();
        for (ServiceBeautyItem item : mapper.findAll()) {
            if (item.getCategoryId() != null && item.getCategoryId().equals(categoryId)) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 批量设置服务折扣：只更新所选项目，未选中的保持原折扣。
     * 整批折扣先校验，任一项目不存在则整批不落库。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<ServiceBeautyItem> batchSetDiscount(List<Long> ids, String discountType, String discountRate) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请先勾选项目");
        }
        DiscountKind kind = DiscountKind.parse(discountType);
        int rate = rate(kind, discountRate);
        List<ServiceBeautyItem> items = new ArrayList<>();
        for (Long id : new java.util.LinkedHashSet<>(ids)) {
            items.add(requireExists(id));
        }
        for (ServiceBeautyItem item : items) {
            item.setDiscountType(kind.name());
            item.setDiscountRate(rate);
            mapper.update(item);
        }
        return items;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireExists(id);
        mapper.delete(id);
    }

    public ServiceBeautyItem requireExists(Long id) {
        ServiceBeautyItem item = id == null ? null : mapper.findById(id);
        if (item == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "美容单项不存在，请刷新后重试");
        }
        return item;
    }

    /** 分类必须存在；只允许挂到可见分类下，避免下架分类重新长出可开单项目。 */
    private void checkCategory(Long categoryId) {
        if (categoryId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请选择美容单项所属的服务分类");
        }
        ServiceCategory c = categoryMapper.findById(categoryId);
        if (c == null || !c.getIsVisible()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "所选服务分类不存在或已隐藏，请重新选择");
        }
    }

    private void applyDiscount(ServiceBeautyItem item, String discountType, String discountRate) {
        DiscountKind kind = DiscountKind.parse(discountType);
        item.setDiscountType(kind.name());
        item.setDiscountRate(rate(kind, discountRate));
    }

    /** FULL 固定 10 折；GROUP 存 10 占位，实际折扣率开放单时按会员组取；RATE 存 1~10 之间的十分位整数。 */
    private int rate(DiscountKind kind, String raw) {
        if (kind == DiscountKind.FULL) {
            return 10;
        }
        if (kind == DiscountKind.GROUP) {
            return 10;
        }
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请填写折扣率，例如 9 表示 9 折");
        }
        java.math.BigDecimal d;
        try {
            d = new java.math.BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "折扣率格式不正确，例如 9 表示 9 折");
        }
        java.math.BigDecimal tenths = d.multiply(java.math.BigDecimal.TEN).stripTrailingZeros();
        if (d.signum() <= 0 || d.compareTo(java.math.BigDecimal.TEN) > 0 || tenths.scale() > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "折扣率需大于 0 且不超过 10，最多一位小数");
        }
        return tenths.intValueExact();
    }

    private int price(String yuan) {
        int cent = Money.toCent(yuan);
        if (cent < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "零售价不能为负数");
        }
        return cent;
    }

    private Long parseCategoryId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请选择美容单项所属的服务分类");
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请选择美容单项所属的服务分类");
        }
    }

    private int nextSortNo() {
        List<ServiceBeautyItem> all = mapper.findAll();
        return all.isEmpty() ? 0 : all.get(all.size() - 1).getSortNo() + 1;
    }
}
