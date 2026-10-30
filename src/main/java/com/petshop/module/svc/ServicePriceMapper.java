package com.petshop.module.svc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ServicePriceMapper {

    List<ServicePriceRow> findRows();

    ServicePriceRow findRowById(@Param("id") Long id);

    int insertRow(ServicePriceRow row);

    int updateRow(ServicePriceRow row);

    int deleteRow(@Param("id") Long id);

    List<ServicePriceCell> findCells();

    /** 开单页：只取可见分类下已定价的格子，按 rowId 索引。 */
    List<ServicePriceCell> findSellableCells();

    default java.util.Map<Long, java.util.Map<Long, ServicePriceCell>> findCellsByRow() {
        java.util.Map<Long, java.util.Map<Long, ServicePriceCell>> index = new java.util.HashMap<>();
        for (ServicePriceCell c : findSellableCells()) {
            index.computeIfAbsent(c.getRowId(), k -> new java.util.HashMap<>()).put(c.getCategoryId(), c);
        }
        return index;
    }

    ServicePriceCell findCell(@Param("rowId") Long rowId, @Param("categoryId") Long categoryId);

    int insertCell(ServicePriceCell cell);

    int updateCell(ServicePriceCell cell);

    int deleteCellsByRow(@Param("rowId") Long rowId);
}
