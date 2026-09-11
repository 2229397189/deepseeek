import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AppShell } from '@/app/AppShell';
import { RequireAdmin, RequireAuth } from '@/router/guards';
import { LoginPage } from '@/features/auth/LoginPage';
import { WorkbenchPage } from '@/features/workbench/WorkbenchPage';
import { ResumeCenterPage } from '@/features/resume/ResumeCenterPage';
import { DecisionPage } from '@/features/decision/DecisionPage';
import { InterviewListPage } from '@/features/interview/InterviewListPage';
import { InterviewRoomPage } from '@/features/interview/InterviewRoomPage';
import { GraphPage } from '@/features/graph/GraphPage';
import { ProfilePage } from '@/features/profile/ProfilePage';
import { AdminModelsPage } from '@/features/admin/models/AdminModelsPage';
import { AdminKbPage } from '@/features/admin/kb/AdminKbPage';

/**
 * 路由表。docs §1 信息架构与路由表。
 * 登录页在 AppShell 之外；其余受保护路由挂在 AppShell 下。
 */
export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppShell />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <WorkbenchPage /> },
      { path: 'resume', element: <ResumeCenterPage /> },
      { path: 'decision', element: <DecisionPage /> },
      { path: 'interview', element: <InterviewListPage /> },
      { path: 'interview/:sessionId', element: <InterviewRoomPage /> },
      { path: 'graph', element: <GraphPage /> },
      { path: 'profile', element: <ProfilePage /> },
      {
        path: 'admin/models',
        element: (
          <RequireAdmin>
            <AdminModelsPage />
          </RequireAdmin>
        ),
      },
      {
        path: 'admin/kb',
        element: (
          <RequireAdmin>
            <AdminKbPage />
          </RequireAdmin>
        ),
      },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
]);
