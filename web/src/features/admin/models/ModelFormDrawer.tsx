import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Drawer } from '@/shared/components/Drawer';
import { Button } from '@/shared/components/Button';
import { Input } from '@/shared/components/Input';
import { useUiStore } from '@/store/uiStore';
import { createModel, updateModel } from './api';
import type { ModelConfig, ModelCreateRequest, ModelUpdateRequest } from './types';

interface ModelFormDrawerProps {
  open: boolean;
  onClose: () => void;
  model: ModelConfig | null;
}

/** 新增 / 编辑模型抽屉（baseUrl / model / apiKey）。docs §6.6。 */
export function ModelFormDrawer({ open, onClose, model }: ModelFormDrawerProps): JSX.Element {
  const qc = useQueryClient();
  const pushToast = useUiStore((s) => s.pushToast);
  const [name, setName] = useState('');
  const [provider, setProvider] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [modelName, setModelName] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [enabled, setEnabled] = useState(true);

  useEffect(() => {
    if (!open) return;
    setName(model?.name ?? '');
    setProvider(model?.provider ?? 'deepseek');
    setBaseUrl(model?.baseUrl ?? '');
    setModelName(model?.model ?? '');
    setApiKey('');
    setEnabled(model?.enabled ?? true);
  }, [open, model]);

  const mutation = useMutation({
    mutationFn: async () => {
      if (model) {
        const payload: ModelUpdateRequest = {
          name,
          provider,
          baseUrl,
          model: modelName,
          enabled,
        };
        if (apiKey) payload.apiKey = apiKey;
        return updateModel(model.id, payload);
      }
      const payload: ModelCreateRequest = {
        name,
        provider,
        baseUrl,
        model: modelName,
        apiKey,
        enabled,
      };
      return createModel(payload);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['models'] });
      pushToast({ tone: 'ok', message: '模型已保存' });
      onClose();
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const valid = name.trim() !== '' && baseUrl.trim() !== '' && modelName.trim() !== '';

  return (
    <Drawer
      open={open}
      onClose={onClose}
      title={model ? '编辑模型' : '新增模型'}
      footer={
        <>
          <Button size="sm" variant="secondary" onClick={onClose}>
            取消
          </Button>
          <Button size="sm" loading={mutation.isPending} disabled={!valid} onClick={() => mutation.mutate()}>
            保存
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <div>
          <label className="mb-1 block text-xs text-ink-soft">名称</label>
          <Input placeholder="如 DeepSeek 生产" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div>
          <label className="mb-1 block text-xs text-ink-soft">Provider</label>
          <Input placeholder="deepseek / openai" value={provider} onChange={(e) => setProvider(e.target.value)} />
        </div>
        <div>
          <label className="mb-1 block text-xs text-ink-soft">Base URL</label>
          <Input placeholder="https://api.deepseek.com" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />
        </div>
        <div>
          <label className="mb-1 block text-xs text-ink-soft">模型</label>
          <Input placeholder="deepseek-chat" value={modelName} onChange={(e) => setModelName(e.target.value)} />
        </div>
        <div>
          <label className="mb-1 block text-xs text-ink-soft">
            API Key{model ? '（留空则不修改）' : ''}
          </label>
          <Input
            type="password"
            placeholder="sk-..."
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
          />
        </div>
        <label className="flex items-center gap-2 text-sm text-ink-soft">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
            className="accent-brand"
          />
          启用该模型
        </label>
      </div>
    </Drawer>
  );
}
