package com.lq.deepseek.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lq.deepseek.common.Result;
import com.lq.deepseek.dto.BillingDtos;
import com.lq.deepseek.service.BillingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 账号额度（钱包 / 流水 / 充值）。
 */
@Tag(name = "账号额度")
@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;

    @Operation(summary = "我的钱包")
    @GetMapping("/wallet")
    public Result<BillingDtos.WalletVO> wallet() {
        return Result.ok(billingService.getWallet(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "额度流水")
    @GetMapping("/ledger")
    public Result<BillingDtos.LedgerPage> ledger(@RequestParam(defaultValue = "1") long pageNum,
                                                 @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(billingService.pageLedger(StpUtil.getLoginIdAsLong(), pageNum, pageSize));
    }

    @Operation(summary = "充值（演示环境直接入账）")
    @PostMapping("/recharge")
    public Result<BillingDtos.WalletVO> recharge(@Valid @RequestBody BillingDtos.RechargeRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        String key = request.getIdempotencyKey() == null
                ? "recharge:" + UUID.randomUUID() : request.getIdempotencyKey();
        billingService.recharge(userId, request.getAmount(), key, request.getRemark());
        return Result.ok(billingService.getWallet(userId));
    }
}
