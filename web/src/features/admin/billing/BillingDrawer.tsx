import { useState } from 'react';
import { Drawer } from '@/shared/components/Drawer';
import { Button } from '@/shared/components/Button';
import { Plus } from 'lucide-react';
import { WalletCard } from './WalletCard';
import { LedgerTable } from './LedgerTable';
import { RechargeDialog } from './RechargeDialog';

interface BillingDrawerProps {
  open: boolean;
  onClose: () => void;
}

/** 额度账户抽屉：钱包 + 流水 + 充值入口。docs §1（额度按钮）。 */
export function BillingDrawer({ open, onClose }: BillingDrawerProps): JSX.Element {
  const [rechargeOpen, setRechargeOpen] = useState(false);

  return (
    <>
      <Drawer
        open={open}
        onClose={onClose}
        title="额度账户"
        width={560}
        footer={
          <Button size="sm" onClick={() => setRechargeOpen(true)}>
            <Plus size={15} />
            充值
          </Button>
        }
      >
        <div className="space-y-5">
          <WalletCard />
          <LedgerTable />
        </div>
      </Drawer>
      <RechargeDialog open={rechargeOpen} onClose={() => setRechargeOpen(false)} />
    </>
  );
}
