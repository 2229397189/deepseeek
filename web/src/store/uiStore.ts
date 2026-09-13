import { create } from 'zustand';

/** Toast 语义色（与 Toast 组件 TONE_STYLE 对齐）。 */
export type ToastTone = 'ok' | 'danger' | 'warn' | 'info';

export interface ToastItem {
  id: string;
  tone: ToastTone;
  message: string;
  /** 自动消失时长（ms），<=0 则不自动消失。 */
  duration?: number;
}

function genId(): string {
  return `t_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
}

interface UIState {
  /** 侧栏折叠态。 */
  sidebarCollapsed: boolean;
  /** 当前选中的简历 assetId（跨 decision / interview 复用）。 */
  selectedResumeAssetId: string | null;
  /** 模型选择器当前值（默认 deepseek-chat）。 */
  modelPickerValue: string;
  /** Toast 队列。 */
  toasts: ToastItem[];

  toggleSidebar: () => void;
  setSidebarCollapsed: (v: boolean) => void;
  setSelectedResumeAssetId: (id: string | null) => void;
  setModelPickerValue: (v: string) => void;
  pushToast: (toast: { tone: ToastTone; message: string; duration?: number }) => void;
  dismissToast: (id: string) => void;
}

export const useUiStore = create<UIState>((set) => ({
  sidebarCollapsed: false,
  selectedResumeAssetId: null,
  modelPickerValue: 'deepseek-chat',
  toasts: [],

  toggleSidebar: () =>
    set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),

  setSidebarCollapsed: (v: boolean) => set({ sidebarCollapsed: v }),

  setSelectedResumeAssetId: (id: string | null) =>
    set({ selectedResumeAssetId: id }),

  setModelPickerValue: (v: string) => set({ modelPickerValue: v }),

  pushToast: (toast) =>
    set((s) => ({
      toasts: [...s.toasts, { id: genId(), duration: 4000, ...toast }],
    })),

  dismissToast: (id: string) =>
    set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
}));
