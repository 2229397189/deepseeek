package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.CreditLedger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CreditLedgerMapper extends BaseMapper<CreditLedger> {

    @Select("SELECT COUNT(1) FROM credit_ledger WHERE idempotency_key = #{key}")
    int existsByIdempotencyKey(String key);
}
