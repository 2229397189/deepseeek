import { useQuery } from '@tanstack/react-query';
import { Tag, Brain } from 'lucide-react';
import { Card } from '@/shared/components/Card';
import { Skeleton } from '@/shared/components/Skeleton';
import { ErrorState } from '@/shared/components/ErrorState';
import { getCapabilityTags, getMemories } from './api';
import { CapabilityTags } from './CapabilityTags';
import { MemoryQueue } from './MemoryQueue';

/** 用户能力追踪：能力画像 + 长期记忆纠偏队列。docs §6.9 / §1。 */
export function ProfilePage(): JSX.Element {
  const tagsQ = useQuery({ queryKey: ['capabilityTags'], queryFn: getCapabilityTags });
  const memQ = useQuery({ queryKey: ['memories'], queryFn: getMemories });

  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold text-ink">用户能力追踪</h1>

      <Card title="能力画像" extra={<Tag size={16} className="text-ink-faint" />}>
        {tagsQ.isLoading ? (
          <Skeleton className="h-16 w-full" />
        ) : tagsQ.isError ? (
          <ErrorState message="能力标签加载失败" onRetry={() => tagsQ.refetch()} />
        ) : (
          <CapabilityTags tags={tagsQ.data ?? []} />
        )}
      </Card>

      <Card title="长期记忆纠偏队列" extra={<Brain size={16} className="text-ink-faint" />}>
        {memQ.isLoading ? (
          <Skeleton className="h-40 w-full" />
        ) : memQ.isError ? (
          <ErrorState message="长期记忆加载失败" onRetry={() => memQ.refetch()} />
        ) : (
          <MemoryQueue memories={memQ.data ?? []} />
        )}
      </Card>
    </div>
  );
}
