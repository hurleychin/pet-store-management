package com.petshop.module.svc;

import com.petshop.common.BizException;
import com.petshop.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 价目表：价目行增删改与价格格子的改价 / 服务折扣。写操作仅管理员可进（由 AdminInterceptor 兜底）。 */
@Service
public class ServicePriceService {

    private final ServicePriceMapper mapper;
    private final ServiceCategoryMapper categoryMapper;

    public ServicePriceService(ServicePriceMapper mapper, ServiceCategoryMapper categoryMapper) {
        this.mapper = mapper;
        this.categoryMapper = categoryMapper;
    }

    public List<ServicePriceRow> findRows() {
        return mapper.findRows();
    }

    public ServicePriceRow findRow(Long id) {
        return id == null ? null : mapper.findRowById(id);
    }

    /** rowId -> (categoryId -> 格子)，供价目表逐格渲染。 */
    public Map<Long, Map<Long, ServicePriceCell>> cellIndex() {
        Map<Long, Map<Long, ServicePriceCell>> index = new HashMap<>();
        for (ServicePriceCell c : mapper.findCells()) {
            index.computeIfAbsent(c.getRowId(), k -> new HashMap<>()).put(c.getCategoryId(), c);
        }
        return index;
    }

    /** 新增（id 为空）或编辑价目行；空白标签视为未填。 */
    @Transactional(rollbackFor = Exception.class)
    public ServicePriceRow saveRow(Long id, String animal, String description,
                                   String dogSize, String furLength, String breedTags, int sortNo) {
        if (!"DOG".equals(animal) && !"CAT".equals(animal)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请选择宠物类型：狗或猫");
        }
        String desc = description == null ? "" : description.trim();
        if (desc.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "价目行描述不能为空，如「0-3kg」");
        }
        ServicePriceRow row = null;
        if (id != null) {
            row = mapper.findRowById(id);
            if (row == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "价目行不存在，请刷新后重试");
            }
        }
        if (row == null) {
            row = new ServicePriceRow();
        }
        row.setAnimal(animal);
        row.setDescription(desc);
        row.setDogSize(blankToNull(dogSize));
        row.setFurLength(blankToNull(furLength));
        row.setBreedTags(blankToNull(breedTags));
        row.setSortNo(sortNo);
        if (id == null) {
            mapper.insertRow(row);
        } else {
            mapper.updateRow(row);
        }
        return row;
    }

    /** 删除价目行，其价格格子一并删除。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRow(Long id) {
        if (findRow(id) == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "价目行不存在，请刷新后重试");
        }
        mapper.deleteCellsByRow(id);
        mapper.deleteRow(id);
    }

    /**
     * 保存格子价格与服务折扣：同一价目行 × 分类只保留一个格子。
     * priceYuan 为空 = 未定价（不建格子）；折扣类型 GROUP / FULL / RATE，RATE 需附带 (0,10] 的折扣率。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveCell(Long rowId, Long categoryId, Integer priceCent,
                         String discountType, String discountRateYuan) {
        if (findRow(rowId) == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "价目行不存在，请刷新后重试");
        }
        ServiceCategory category = categoryId == null ? null : categoryMapper.findById(categoryId);
        if (category == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "服务分类不存在，请刷新后重试");
        }
        if (priceCent != null && priceCent < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "格子价格不能为负数");
        }
        String type = discountType == null || discountType.trim().isEmpty() ? "FULL" : discountType.trim();
        int rate;
        if ("GROUP".equals(type)) {
            rate = 10; // 会员组折扣：实际折扣率取自客户的会员组，格子上不另存
        } else if ("FULL".equals(type)) {
            rate = 10;
        } else if ("RATE".equals(type)) {
            rate = parseRate(discountRateYuan);
        } else {
            throw new BizException(ErrorCode.PARAM_INVALID, "服务折扣类型不正确");
        }

        ServicePriceCell cell = mapper.findCell(rowId, categoryId);
        if (priceCent == null) {
            if (cell != null) {
                cell.setPriceCent(0);
                cell.setDiscountType("FULL");
                cell.setDiscountRate(10);
                mapper.updateCell(cell);
            }
            return;
        }
        if (cell == null) {
            cell = new ServicePriceCell();
            cell.setRowId(rowId);
            cell.setCategoryId(categoryId);
            cell.setPriceCent(priceCent);
            cell.setDiscountType(type);
            cell.setDiscountRate(rate);
            mapper.insertCell(cell);
        } else {
            cell.setPriceCent(priceCent);
            cell.setDiscountType(type);
            cell.setDiscountRate(rate);
            mapper.updateCell(cell);
        }
    }

    /** 折扣率十分位整数，合法范围 (0, 10]，如 9 表示 9 折、85 表示 8.5 折。与美容单项口径一致。 */
    private int parseRate(String raw) {
        String s = raw == null ? "" : raw.trim();
        BigDecimal d;
        try {
            d = new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "自定义折扣率不正确，如 8.5 表示 8.5 折");
        }
        BigDecimal tenths = d.multiply(BigDecimal.TEN).stripTrailingZeros();
        if (d.signum() <= 0 || d.compareTo(BigDecimal.TEN) > 0 || tenths.scale() > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "自定义折扣率需大于 0 且不超过 10，最多一位小数");
        }
        return tenths.intValueExact();
    }

    private String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
