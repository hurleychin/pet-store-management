package com.petshop.module.svc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ServiceCategoryMapper {

    ServiceCategory findById(@Param("id") Long id);

    List<ServiceCategory> findAll();

    int insert(ServiceCategory c);

    int update(ServiceCategory c);

    int setVisible(@Param("id") Long id, @Param("visible") boolean visible);

    int updateSortNo(@Param("id") Long id, @Param("sortNo") int sortNo);

    int delete(@Param("id") Long id);

    /** 引用计数：已有价格格子或美容单项引用该分类。 */
    int countReferences(@Param("id") Long id);
}
