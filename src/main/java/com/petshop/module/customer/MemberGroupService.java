package com.petshop.module.customer;

import com.petshop.common.BizException;
import com.petshop.common.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;

/** 会员组只读查询：本工单只保证「散客组」存在，开单时按客户所属会员组取服务折扣率。 */
@Service
public class MemberGroupService {

    private final MemberGroupMapper mapper;

    public MemberGroupService(MemberGroupMapper mapper) {
        this.mapper = mapper;
    }

    public List<MemberGroup> findAll() {
        return mapper.findAll();
    }

    /**
     * 客户的服务折扣率（十分位整数）：未设会员组或会员组已不存在时按散客组；
     * 散客组本身缺失时按全额（10 折），绝不因数据缺失而多打折。
     */
    public int rateOf(Customer customer) {
        Long groupId = customer == null || customer.getMemberGroupId() == null
                ? MemberGroup.WALK_IN_ID : customer.getMemberGroupId();
        MemberGroup g = mapper.findById(groupId);
        if (g == null) {
            g = mapper.findById(MemberGroup.WALK_IN_ID);
        }
        return g == null || g.getDiscountRate() <= 0 ? MemberGroup.FULL_RATE : g.getDiscountRate();
    }

    /** 客户表单选会员组时校验：为空 = 散客组；非空必须存在。 */
    public MemberGroup requireIfPresent(Long id) {
        if (id == null) {
            return null;
        }
        MemberGroup g = mapper.findById(id);
        if (g == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "会员组不存在，请刷新后重新选择");
        }
        return g;
    }
}
