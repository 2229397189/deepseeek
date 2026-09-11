import React from 'react';
import { createRoot } from 'react-dom/client';
import App from '@/App';
import { registerUnauthorizedHandler } from '@/lib/apiClient';
import { useAuthStore } from '@/store/authStore';
import { router } from '@/router';
import './styles/index.css';

// 401001：清登录态并跳登录页（docs §4.2）。通过回调注入，避免 apiClient 依赖 router。
registerUnauthorizedHandler(() => {
  useAuthStore.getState().logout();
  router.navigate('/login');
});

const container = document.getElementById('root');
if (!container) throw new Error('Root element #root not found');

createRoot(container).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
