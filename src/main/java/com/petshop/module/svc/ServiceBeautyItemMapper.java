package com.petshop.module.svc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ServiceBeautyItemMapper {

    ServiceBeautyItem findById(@Param("id") Long id);

    List<ServiceBeautyItem> findAll();

    int insert(ServiceBeautyItem item);

    int update(ServiceBeautyItem item);

    int delete(@Param("id") Long id);
}
