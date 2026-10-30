package com.petshop.module.svc;

import com.petshop.common.BizException;
import com.petshop.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 服务分类维护：增删改、切换大类、隐藏与调序。写操作仅管理员可进（由 AdminInterceptor 兜底）。 */
@Service
public class ServiceCategoryService {

    private final ServiceCategoryMapper mapper;
    private final JdbcTemplate jdbc;

    public ServiceCategoryService(ServiceCategoryMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    public List<ServiceCategory> findAll() {
        return mapper.findAll();
    }

    /** 新增或更新：id 为空即新增，排在当前最后且默认显示。 */
    @Transactional(rollbackFor = Exception.class)
    public ServiceCategory save(Long id, String name, String degree) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "分类名称不能为空");
        }
        if (!"SHOWER".equals(degree) && !"BEAUTY".equals(degree)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请选择服务大类：洗护类或美容类");
        }
        ServiceCategory c;
        if (id == null) {
            c = new ServiceCategory();
            List<ServiceCategory> all = mapper.findAll();
            c.setSortNo(all.isEmpty() ? 0 : all.get(all.size() - 1).getSortNo() + 1);
            c.setIsVisible(true);
        } else {
            c = mapper.findById(id);
            if (c == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "服务分类不存在，请刷新后重试");
            }
        }
        c.setName(n);
        c.setDegree(degree);
        if (id == null) {
            mapper.insert(c);
        } else {
            mapper.update(c);
        }
        return c;
    }

    @Transactional(rollbackFor = Exception.class)
    public void setVisible(Long id, boolean visible) {
        requireExists(id);
        mapper.setVisible(id, visible);
    }

    /** 相邻交换排序号；dir=up 与当前行前一行的分类交换，dir=down 与后一行交换。 */
    @Transactional(rollbackFor = Exception.class)
    public void move(Long id, String dir) {
        if (!"up".equals(dir) && !"down".equals(dir)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "排序方向不正确");
        }
        ServiceCategory cur = requireExists(id);
        List<ServiceCategory> all = mapper.findAll();
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(id)) {
                idx = i;
                break;
            }
        }
        int other = "up".equals(dir) ? idx - 1 : idx + 1;
        if (idx < 0 || other < 0 || other >= all.size()) {
            return; // 已在两端，无需移动
        }
        ServiceCategory neighbor = all.get(other);
        int a = cur.getSortNo();
        int b = neighbor.getSortNo();
        if (a != b) {
            mapper.updateSortNo(cur.getId(), b);
            mapper.updateSortNo(neighbor.getId(), a);
        } else if (idx != other) {
            // 排序号相同（历史数据）时交换行位置，仍按 (sort_no, id) 生效：把当前行挪到相邻位置
            mapper.updateSortNo(cur.getId(), "up".equals(dir) ? b - 1 : b + 1);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireExists(id);
        if (countReferences(id) > 0) {
            throw new BizException(ErrorCode.CATEGORY_IN_USE);
        }
        mapper.delete(id);
    }

    /** 价格格子 / 美容单项由后续工单建表；表还不存在时视为无引用。 */
    private int countReferences(Long id) {
        java.util.Set<String> tables = new java.util.HashSet<>(jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('service_price_cell', 'service_beauty_item')",
                String.class));
        return mapper.countReferences(id, tables.contains("service_price_cell"),
                tables.contains("service_beauty_item"));
    }

    private ServiceCategory requireExists(Long id) {
        ServiceCategory c = id == null ? null : mapper.findById(id);
        if (c == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "服务分类不存在，请刷新后重试");
        }
        return c;
    }
}
