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

    ServicePriceCell findCell(@Param("rowId") Long rowId, @Param("categoryId") Long categoryId);

    int insertCell(ServicePriceCell cell);

    int updateCell(ServicePriceCell cell);

    int deleteCellsByRow(@Param("rowId") Long rowId);
}
