package com.petshop.module.customer;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemberGroupMapper {

    MemberGroup findById(@Param("id") Long id);

    List<MemberGroup> findAll();
}
