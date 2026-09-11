import { useUiStore } from '@/store/uiStore';

/** Mock 流水线开关。docs §6.1。 */
export function MockToggle(): JSX.Element {
  const modelValue = useUiStore((s) => s.modelPickerValue);
  // 以 model id 是否包含 'mock' 作为 Mock 态标识（best-effort）。
  const enabled = modelValue.toLowerCase().includes('mock');
  const setValue = useUiStore((s) => s.setModelPickerValue);

  const toggle = () => {
    if (enabled) {
      setValue('deepseek-chat');
    } else {
      setValue('mock-stream');
    }
  };

  return (
    <button
      type="button"
      onClick={toggle}
      className="flex h-9 items-center gap-2 rounded-md border border-line bg-surface px-3 text-base text-ink-soft transition-colors duration-base hover:bg-surface-2"
    >
      <span
        className={[
          'relative h-4 w-7 rounded-full transition-colors duration-base',
          enabled ? 'bg-brand' : 'bg-line-strong',
        ].join(' ')}
      >
        <span
          className={[
            'absolute top-0.5 h-3 w-3 rounded-full bg-white transition-all duration-base',
            enabled ? 'left-3.5' : 'left-0.5',
          ].join(' ')}
        />
      </span>
      Mock 流水线
    </button>
  );
}
