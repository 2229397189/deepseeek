package com.lq.deepseek.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lq.deepseek.domain.entity.BillingWallet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BillingWalletMapper extends BaseMapper<BillingWallet> {

    @Select("SELECT * FROM billing_wallets WHERE user_id = #{userId} LIMIT 1")
    BillingWallet selectByUserId(Long userId);

    /**
     * 入账：余额与累计发放同时增加。
     */
    @Update("""
            UPDATE billing_wallets
               SET balance_credit = balance_credit + #{amount},
                   total_granted  = total_granted + #{amount},
                   version        = version + 1,
                   updated_at     = now()
             WHERE id = #{walletId}
            """)
    int increase(@Param("walletId") Long walletId, @Param("amount") long amount);

    /**
     * 扣减：仅当可用额度（余额 - 冻结）充足时才生效，条件更新天然防超发，无需悲观锁。
     */
    @Update("""
            UPDATE billing_wallets
               SET balance_credit = balance_credit - #{amount},
                   total_consumed = total_consumed + #{amount},
                   version        = version + 1,
                   updated_at     = now()
             WHERE id = #{walletId}
               AND balance_credit - frozen_credit >= #{amount}
            """)
    int deductIfEnough(@Param("walletId") Long walletId, @Param("amount") long amount);

    @Update("""
            UPDATE billing_wallets
               SET frozen_credit = frozen_credit + #{amount},
                   version       = version + 1,
                   updated_at    = now()
             WHERE id = #{walletId}
               AND balance_credit - frozen_credit >= #{amount}
            """)
    int freezeIfEnough(@Param("walletId") Long walletId, @Param("amount") long amount);

    @Update("""
            UPDATE billing_wallets
               SET frozen_credit = GREATEST(frozen_credit - #{amount}, 0),
                   version       = version + 1,
                   updated_at    = now()
             WHERE id = #{walletId}
            """)
    int unfreeze(@Param("walletId") Long walletId, @Param("amount") long amount);
}
