import { Routes } from '@angular/router';
import { authGuard } from './core/api';

/** Chargement différé de chaque écran (directive §4). */
export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./pages/auth').then(m => m.LoginPage) },
  { path: 'register', loadComponent: () => import('./pages/auth').then(m => m.RegisterPage) },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell').then(m => m.Shell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'servers' },
      { path: 'servers', loadComponent: () => import('./pages/servers').then(m => m.ServersPage) },
      { path: 'servers/:id', loadComponent: () => import('./pages/server-detail').then(m => m.ServerDetailPage) },
      { path: 'operations', loadComponent: () => import('./pages/operations').then(m => m.OperationsPage) },
      { path: 'members', loadComponent: () => import('./pages/admin-pages').then(m => m.MembersPage) },
      { path: 'tokens', loadComponent: () => import('./pages/admin-pages').then(m => m.TokensPage) },
      { path: 'support', loadComponent: () => import('./pages/admin-pages').then(m => m.SupportPage) },
      { path: 'billing', loadComponent: () => import('./pages/admin-pages').then(m => m.BillingPage) },
      { path: 'audit', loadComponent: () => import('./pages/admin-pages').then(m => m.AuditPage) },
      { path: 'security', loadComponent: () => import('./pages/account').then(m => m.SecurityPage) },
      { path: 'platform', loadComponent: () => import('./pages/account').then(m => m.PlatformPage) },
    ],
  },
  { path: '**', redirectTo: '' },
];
