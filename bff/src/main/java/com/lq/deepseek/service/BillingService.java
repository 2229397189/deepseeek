package com.lq.deepseek.service;

import com.lq.deepseek.dto.BillingDtos;

/**
 * 计费额度服务。
 *
 * <p>所有额度变动遵循三条铁律：
 * <ol>
 *   <li>余额只通过条件更新的原子 SQL 修改，禁止"先查后写"；</li>
 *   <li>每一次变动都落一条不可变流水（credit_ledger），带幂等键；</li>
 *   <li>AI 调用采用「预授权冻结 -> 按实际消耗结算」，避免调用中途额度被并发耗尽。</li>
 * </ol>
 */
public interface BillingService {

    /** 幂等地准备钱包（注册时调用）。 */
    BillingDtos.WalletVO ensureWallet(Long userId);

    BillingDtos.WalletVO getWallet(Long userId);

    BillingDtos.LedgerPage pageLedger(Long userId, long pageNum, long pageSize);

    /** 通用入账（新人额度、邀请奖励等）。 */
    long grant(Long userId, long amount, String changeType, String bizType, Long bizId,
               String idempotencyKey, String remark);

    /**
     * 预授权冻结。
     *
     * @return 冻结流水 ID，作为后续结算凭证
     */
    Long freeze(Long userId, long amount, String bizType, Long bizId, String idempotencyKey);

    /**
     * 结算：先释放全部冻结，再按实际消耗扣减（实际消耗小于冻结额时相当于退还差额）。
     *
     * @return 变动后可用额度
     */
    long settle(Long userId, Long freezeLedgerId, long frozenAmount, long actualAmount,
                String bizType, Long bizId, String runId, String idempotencyKey, String remark);

    /** 直接扣减，用于无需预授权的场景。 */
    long deduct(Long userId, long amount, String bizType, Long bizId, String runId,
                String idempotencyKey, String remark);

    /** 释放冻结（AI 调用失败回滚）。 */
    void unfreeze(Long userId, long amount, String bizType, Long bizId,
                  String idempotencyKey, String remark);

    /** P2-28 兑换码激活：校验码 → 原子占用一次 → 发放奖励额度。 */
    BillingDtos.RedeemResult redeem(Long userId, String code);
}
