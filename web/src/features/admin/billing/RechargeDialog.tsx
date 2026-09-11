import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Dialog } from '@/shared/components/Dialog';
import { Input } from '@/shared/components/Input';
import { useUiStore } from '@/store/uiStore';
import { recharge } from './api';

function genIdempotencyKey(): string {
  return `rc_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
}

interface RechargeDialogProps {
  open: boolean;
  onClose: () => void;
}

/** 充值弹窗（幂等键）。docs §6.6 / §5.1 /billing/recharge。 */
export function RechargeDialog({ open, onClose }: RechargeDialogProps): JSX.Element {
  const [amount, setAmount] = useState('');
  const [remark, setRemark] = useState('');
  const [idempotencyKey, setIdempotencyKey] = useState(genIdempotencyKey);
  const pushToast = useUiStore((s) => s.pushToast);
  const qc = useQueryClient();

  // 每次打开弹窗重新生成幂等键，避免常驻弹窗重复打开复用旧键导致 409 被拒。
  useEffect(() => {
    if (open) setIdempotencyKey(genIdempotencyKey());
  }, [open]);

  const mutation = useMutation({
    mutationFn: () =>
      recharge({
        amount: Number(amount),
        idempotencyKey,
        remark: remark || undefined,
      }),
    onSuccess: () => {
      pushToast({ tone: 'ok', message: `充值成功：${amount} 额度` });
      qc.invalidateQueries({ queryKey: ['wallet'] });
      qc.invalidateQueries({ queryKey: ['ledger'] });
      setAmount('');
      setRemark('');
      onClose();
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const valid = amount.trim() !== '' && Number(amount) > 0;

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="充值额度"
      confirmText="确认充值"
      confirmLoading={mutation.isPending}
      onConfirm={() => {
        if (valid) mutation.mutate();
      }}
    >
      <div className="space-y-4">
        <div>
          <label className="mb-1 block text-xs text-ink-soft">充值额度</label>
          <Input
            type="number"
            min={1}
            placeholder="如 100"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            suffix="点"
          />
        </div>
        <div>
          <label className="mb-1 block text-xs text-ink-soft">备注（可选）</label>
          <Input
            placeholder="如 演示充值"
            value={remark}
            onChange={(e) => setRemark(e.target.value)}
          />
        </div>
        <p className="text-2xs text-ink-faint">
          幂等键：<span className="font-mono">{idempotencyKey}</span>（重复提交不会重复入账）
        </p>
      </div>
    </Dialog>
  );
}
