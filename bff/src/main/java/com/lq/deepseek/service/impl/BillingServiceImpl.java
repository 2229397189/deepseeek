package com.lq.deepseek.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.domain.entity.BillingWallet;
import com.lq.deepseek.domain.entity.CreditLedger;
import com.lq.deepseek.domain.entity.InviteCode;
import com.lq.deepseek.domain.mapper.BillingWalletMapper;
import com.lq.deepseek.domain.mapper.CreditLedgerMapper;
import com.lq.deepseek.domain.mapper.InviteCodeMapper;
import com.lq.deepseek.dto.BillingDtos;
import com.lq.deepseek.service.BillingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 计费额度服务实现。
 *
 * <p>并发正确性依赖两层：条件更新 SQL（余额不足则更新 0 行）+ 幂等键唯一索引。
 * 事务内不做任何远程调用，避免长事务持有连接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingServiceImpl implements BillingService {

    private static final String TYPE_RECHARGE = "RECHARGE";
    private static final String TYPE_GRANT = "GRANT";
    private static final String TYPE_CONSUME = "CONSUME";
    private static final String TYPE_FREEZE = "FREEZE";
    private static final String TYPE_UNFREEZE = "UNFREEZE";

    private final BillingWalletMapper walletMapper;
    private final CreditLedgerMapper ledgerMapper;
    private final InviteCodeMapper inviteCodeMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BillingDtos.WalletVO ensureWallet(Long userId) {
        BillingWallet wallet = walletMapper.selectByUserId(userId);
        if (wallet == null) {
            BillingWallet fresh = new BillingWallet();
            fresh.setUserId(userId);
            fresh.setBalanceCredit(0L);
            fresh.setFrozenCredit(0L);
            fresh.setTotalGranted(0L);
            fresh.setTotalConsumed(0L);
            fresh.setVersion(0);
            try {
                walletMapper.insert(fresh);
                wallet = fresh;
            } catch (Exception e) {
                // 唯一索引冲突说明并发下已被创建，回查即可
                log.debug("钱包并发创建冲突，回查 userId={}", userId);
                wallet = walletMapper.selectByUserId(userId);
                if (wallet == null) {
                    throw e;
                }
            }
        }
        return toVO(wallet);
    }

    @Override
    public BillingDtos.WalletVO getWallet(Long userId) {
        BillingWallet wallet = walletMapper.selectByUserId(userId);
        if (wallet == null) {
            throw BusinessException.of(ErrorCode.WALLET_NOT_FOUND);
        }
        return toVO(wallet);
    }

    @Override
    public BillingDtos.LedgerPage pageLedger(Long userId, long pageNum, long pageSize) {
        Page<CreditLedger> page = new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 100));
        LambdaQueryWrapper<CreditLedger> wrapper = new LambdaQueryWrapper<CreditLedger>()
                .eq(CreditLedger::getUserId, userId)
                .orderByDesc(CreditLedger::getCreatedAt)
                .orderByDesc(CreditLedger::getId);
        Page<CreditLedger> result = ledgerMapper.selectPage(page, wrapper);
        List<BillingDtos.LedgerVO> records = result.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        return BillingDtos.LedgerPage.builder()
                .total(result.getTotal())
                .pageNum(result.getCurrent())
                .pageSize(result.getSize())
                .records(records)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long recharge(Long userId, long amount, String idempotencyKey, String remark) {
        return grant(userId, amount, TYPE_RECHARGE, "RECHARGE", null, idempotencyKey,
                StringUtils.hasText(remark) ? remark : "额度充值");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long grant(Long userId, long amount, String changeType, String bizType, Long bizId,
                      String idempotencyKey, String remark) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "入账额度必须大于 0");
        }
        BillingWallet wallet = requireWallet(userId);
        String key = normalizeKey(idempotencyKey, changeType, bizType, bizId, userId);
        if (alreadyApplied(key)) {
            return wallet.available();
        }
        walletMapper.increase(wallet.getId(), amount);
        BillingWallet after = walletMapper.selectByUserId(userId);
        writeLedger(userId, wallet.getId(), changeType, amount, after.getBalanceCredit(),
                bizType, bizId, null, key, remark);
        return after.available();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BillingDtos.RedeemResult redeem(Long userId, String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请输入兑换码");
        }
        String normalized = code.trim();

        InviteCode invite = inviteCodeMapper.selectOne(new LambdaQueryWrapper<InviteCode>()
                .eq(InviteCode::getCode, normalized));
        if (invite == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "兑换码不存在");
        }
        if (!"ACTIVE".equalsIgnoreCase(invite.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "兑换码已失效");
        }
        if (invite.getExpireAt() != null && invite.getExpireAt().isBefore(OffsetDateTime.now())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "兑换码已过期");
        }
        if (invite.getRewardCredit() == null || invite.getRewardCredit() <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "兑换码无可发放额度");
        }

        // 原子占用一次：条件更新，更新 0 行即为已被抢完/失效，杜绝并发超额兑换
        LambdaUpdateWrapper<InviteCode> uw = new LambdaUpdateWrapper<InviteCode>()
                .eq(InviteCode::getId, invite.getId())
                .eq(InviteCode::getStatus, invite.getStatus())
                .setSql("used_count = used_count + 1");
        if (invite.getMaxUses() != null) {
            uw.lt(InviteCode::getUsedCount, invite.getMaxUses());
        }
        if (inviteCodeMapper.update(null, uw) == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "兑换码已被使用或已失效");
        }

        long reward = invite.getRewardCredit();
        long available = grant(userId, reward, TYPE_GRANT, "INVITE_CODE", invite.getId(),
                "redeem:" + userId + ":" + invite.getId(), "兑换码 " + normalized + " 激活");
        log.info("兑换码已激活 userId={} code={} reward={}", userId, normalized, reward);

        return BillingDtos.RedeemResult.builder()
                .rewardCredit(reward)
                .availableCredit(available)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long freeze(Long userId, long amount, String bizType, Long bizId, String idempotencyKey) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "冻结额度必须大于 0");
        }
        BillingWallet wallet = requireWallet(userId);
        String key = normalizeKey(idempotencyKey, TYPE_FREEZE, bizType, bizId, userId);
        if (alreadyApplied(key)) {
            return null;
        }
        if (walletMapper.freezeIfEnough(wallet.getId(), amount) == 0) {
            throw BusinessException.of(ErrorCode.INSUFFICIENT_CREDIT);
        }
        BillingWallet after = walletMapper.selectByUserId(userId);
        CreditLedger ledger = writeLedger(userId, wallet.getId(), TYPE_FREEZE, -amount,
                after.getBalanceCredit(), bizType, bizId, null, key, "AI 调用预授权冻结");
        return ledger.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long settle(Long userId, Long freezeLedgerId, long frozenAmount, long actualAmount,
                       String bizType, Long bizId, String runId, String idempotencyKey, String remark) {
        BillingWallet wallet = requireWallet(userId);

        if (frozenAmount > 0) {
            walletMapper.unfreeze(wallet.getId(), frozenAmount);
        }

        long realCost = Math.max(actualAmount, 0L);
        long charged = 0L;
        if (realCost > 0) {
            String key = normalizeKey(idempotencyKey, TYPE_CONSUME, bizType, bizId, userId);
            if (!alreadyApplied(key)) {
                if (walletMapper.deductIfEnough(wallet.getId(), realCost) == 0) {
                    // 冻结额已释放但余额不足：按可用余额扣到 0，剩余记为欠费由后续充值补齐
                    BillingWallet current = walletMapper.selectByUserId(userId);
                    long affordable = Math.max(current.available(), 0L);
                    if (affordable > 0 && walletMapper.deductIfEnough(wallet.getId(), affordable) > 0) {
                        charged = affordable;
                    }
                    log.warn("结算额度不足 userId={} runId={} 应扣={} 实扣={}", userId, runId, realCost, charged);
                } else {
                    charged = realCost;
                }
                BillingWallet after = walletMapper.selectByUserId(userId);
                writeLedger(userId, wallet.getId(), TYPE_CONSUME, -charged, after.getBalanceCredit(),
                        bizType, bizId, runId, key,
                        StringUtils.hasText(remark) ? remark : "AI 调用结算（冻结单 " + freezeLedgerId + "）");
            }
        }

        return walletMapper.selectByUserId(userId).available();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long deduct(Long userId, long amount, String bizType, Long bizId, String runId,
                       String idempotencyKey, String remark) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "扣减额度必须大于 0");
        }
        BillingWallet wallet = requireWallet(userId);
        String key = normalizeKey(idempotencyKey, TYPE_CONSUME, bizType, bizId, userId);
        if (alreadyApplied(key)) {
            return wallet.available();
        }
        if (walletMapper.deductIfEnough(wallet.getId(), amount) == 0) {
            throw BusinessException.of(ErrorCode.INSUFFICIENT_CREDIT);
        }
        BillingWallet after = walletMapper.selectByUserId(userId);
        writeLedger(userId, wallet.getId(), TYPE_CONSUME, -amount, after.getBalanceCredit(),
                bizType, bizId, runId, key, remark);
        return after.available();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unfreeze(Long userId, long amount, String bizType, Long bizId,
                         String idempotencyKey, String remark) {
        if (amount <= 0) {
            return;
        }
        BillingWallet wallet = requireWallet(userId);
        String key = normalizeKey(idempotencyKey, TYPE_UNFREEZE, bizType, bizId, userId);
        if (alreadyApplied(key)) {
            return;
        }
        walletMapper.unfreeze(wallet.getId(), amount);
        BillingWallet after = walletMapper.selectByUserId(userId);
        writeLedger(userId, wallet.getId(), TYPE_UNFREEZE, amount, after.getBalanceCredit(),
                bizType, bizId, null, key,
                StringUtils.hasText(remark) ? remark : "释放预授权冻结");
    }

    // ------------------------------------------------------------------ 内部方法

    private BillingWallet requireWallet(Long userId) {
        BillingWallet wallet = walletMapper.selectByUserId(userId);
        if (wallet == null) {
            throw BusinessException.of(ErrorCode.WALLET_NOT_FOUND);
        }
        return wallet;
    }

    private boolean alreadyApplied(String idempotencyKey) {
        return ledgerMapper.existsByIdempotencyKey(idempotencyKey) > 0;
    }

    private CreditLedger writeLedger(Long userId, Long walletId, String changeType, long amount,
                                     Long balanceAfter, String bizType, Long bizId, String runId,
                                     String idempotencyKey, String remark) {
        CreditLedger ledger = new CreditLedger();
        ledger.setUserId(userId);
        ledger.setWalletId(walletId);
        ledger.setChangeType(changeType);
        ledger.setAmount(amount);
        ledger.setBalanceAfter(balanceAfter == null ? 0L : balanceAfter);
        ledger.setBizType(bizType);
        ledger.setBizId(bizId);
        ledger.setRunId(runId);
        ledger.setIdempotencyKey(idempotencyKey);
        ledger.setRemark(remark);
        ledgerMapper.insert(ledger);
        return ledger;
    }

    /**
     * 幂等键缺失时按业务四元组派生，天然去重；调用方显式传入时优先使用调用方键。
     */
    private String normalizeKey(String idempotencyKey, String changeType, String bizType,
                                Long bizId, Long userId) {
        if (StringUtils.hasText(idempotencyKey)) {
            return idempotencyKey;
        }
        return String.join(":", changeType, String.valueOf(bizType), String.valueOf(bizId),
                String.valueOf(userId));
    }

    private BillingDtos.WalletVO toVO(BillingWallet wallet) {
        return BillingDtos.WalletVO.builder()
                .userId(wallet.getUserId())
                .availableCredit(wallet.available())
                .balanceCredit(wallet.getBalanceCredit())
                .frozenCredit(wallet.getFrozenCredit())
                .totalGranted(wallet.getTotalGranted())
                .totalConsumed(wallet.getTotalConsumed())
                .build();
    }

    private BillingDtos.LedgerVO toVO(CreditLedger ledger) {
        return BillingDtos.LedgerVO.builder()
                .id(ledger.getId())
                .changeType(ledger.getChangeType())
                .amount(ledger.getAmount())
                .balanceAfter(ledger.getBalanceAfter())
                .bizType(ledger.getBizType())
                .bizId(ledger.getBizId())
                .runId(ledger.getRunId())
                .remark(ledger.getRemark())
                .createdAt(ledger.getCreatedAt())
                .build();
    }
}
