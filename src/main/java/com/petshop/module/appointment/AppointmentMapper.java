package com.petshop.module.appointment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AppointmentMapper {

    Appointment findById(@Param("id") Long id);

    List<Appointment> findByDate(@Param("date") String date);

    /** 该时段占用数（BOOKED + ARRIVED） */
    int countSlot(@Param("date") String date, @Param("slot") String slot);

    /**
     * 插入时同时写 service_name_snapshot（旧库迁移后才有该列；新库无此列时由调用方退回 insertWithoutSnapshot）。
     * 快照保证历史预约在美容单项改名或删除后仍显示当时的项目名。
     */
    int insertWithSnapshot(Appointment a);

    /** 新库（无 service_name_snapshot 列）的插入。 */
    int insertWithoutSnapshot(Appointment a);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int markDone(@Param("id") Long id, @Param("billId") Long billId);

    /** 结账事务内回写：仅 ARRIVED 状态且客户匹配时生效，返回影响行数 */
    int markDoneIfArrived(@Param("id") Long id, @Param("billId") Long billId,
                          @Param("customerId") Long customerId);

    /** 过期未处理（date < today 且仍 BOOKED） */
    List<Appointment> findStale(@Param("today") String today);
}
