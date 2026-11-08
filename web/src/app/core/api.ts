import { HttpClient, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Observable, catchError, firstValueFrom, throwError } from 'rxjs';
import { I18n } from './i18n';

export const API = '/api/v2';

export interface ApiError { code: string; message: string; status: number; correlationId?: string; }

/** Session : jeton opaque (Bearer). Aucun cookie → pas de CSRF ; l'autorisation reste côté serveur. */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly http = inject(HttpClient);
  readonly token = signal<string | null>(Auth.load('ap.token'));
  readonly orgId = signal<string | null>(Auth.load('ap.org'));
  readonly me = signal<any | null>(null);
  readonly orgs = signal<any[]>([]);
  readonly org = computed(() => this.orgs().find(o => o.id === this.orgId()) ?? null);

  private static load(k: string): string | null {
    try { return sessionStorage.getItem(k); } catch { return null; }
  }
  private static save(k: string, v: string | null) {
    try { v ? sessionStorage.setItem(k, v) : sessionStorage.removeItem(k); } catch { /* ignore */ }
  }

  can(permission: string): boolean {
    return (this.org()?.permissions ?? []).includes(permission);
  }

  async login(email: string, password: string, totp?: string) {
    const r: any = await firstValueFrom(this.http.post(`${API}/public/auth/login`, { email, password, totp: totp || undefined }));
    await this.open(r.token);
  }

  async register(body: any) {
    const r: any = await firstValueFrom(this.http.post(`${API}/public/auth/register`, body));
    await this.open(r.token, r.organizationId);
  }

  private async open(token: string, orgId?: string) {
    this.token.set(token);
    Auth.save('ap.token', token);
    await this.refresh();
    this.selectOrg(orgId ?? this.orgId() ?? this.orgs()[0]?.id ?? null);
  }

  async refresh() {
    this.me.set(await firstValueFrom(this.http.get(`${API}/me`)));
    this.orgs.set(await firstValueFrom(this.http.get<any[]>(`${API}/orgs`)));
  }

  selectOrg(id: string | null) {
    this.orgId.set(id);
    Auth.save('ap.org', id);
  }

  async logout() {
    try { await firstValueFrom(this.http.post(`${API}/me/logout`, {})); } catch { /* déjà expirée */ }
    this.clear();
  }

  clear() {
    this.token.set(null);
    this.me.set(null);
    this.orgs.set([]);
    Auth.save('ap.token', null);
  }
}

/** Accès typé à l'API de l'organisation courante. */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(Auth);

  private orgBase(): string {
    return `${API}/orgs/${this.auth.orgId()}`;
  }

  get<T = any>(path: string): Promise<T> { return firstValueFrom(this.http.get<T>(this.orgBase() + path)); }
  post<T = any>(path: string, body: any = {}, headers?: Record<string, string>): Promise<T> {
    return firstValueFrom(this.http.post<T>(this.orgBase() + path, body, { headers }));
  }
  /** Multipart (restore staging) — ne pas fixer Content-Type (boundary géré par le navigateur). */
  upload<T = any>(path: string, form: FormData): Promise<T> {
    return firstValueFrom(this.http.post<T>(this.orgBase() + path, form));
  }
  patch<T = any>(path: string, body: any): Promise<T> { return firstValueFrom(this.http.patch<T>(this.orgBase() + path, body)); }
  put<T = any>(path: string, body: any): Promise<T> { return firstValueFrom(this.http.put<T>(this.orgBase() + path, body)); }
  delete(path: string): Promise<unknown> { return firstValueFrom(this.http.delete(this.orgBase() + path)); }
  public<T = any>(path: string): Promise<T> { return firstValueFrom(this.http.get<T>(`${API}/public${path}`)); }
  /** Hors organisation : compte ({@code /me}) et back-office ({@code /admin}). */
  rootGet<T = any>(path: string): Promise<T> { return firstValueFrom(this.http.get<T>(API + path)); }
  rootPost<T = any>(path: string, body: any = {}): Promise<T> { return firstValueFrom(this.http.post<T>(API + path, body)); }
  rootDelete(path: string): Promise<unknown> { return firstValueFrom(this.http.delete(API + path)); }
}

/** Dernière erreur affichée (bandeau), message déjà traduit par le serveur. */
@Injectable({ providedIn: 'root' })
export class Notice {
  readonly error = signal<ApiError | null>(null);
  readonly info = signal<string | null>(null);
  show(e: ApiError) { this.error.set(e); setTimeout(() => this.error.set(null), 8000); }
  ok(msg: string) { this.info.set(msg); setTimeout(() => this.info.set(null), 4000); }
}

export const apiInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(Auth);
  const i18n = inject(I18n);
  const notice = inject(Notice);
  const router = inject(Router);
  const headers: Record<string, string> = { 'Accept-Language': i18n.lang() };
  const token = auth.token();
  if (token && req.url.startsWith(API) && !req.url.startsWith(`${API}/public`)) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  return next(req.clone({ setHeaders: headers })).pipe(catchError((err: HttpErrorResponse): Observable<never> => {
    const body: ApiError = err.error && typeof err.error === 'object'
      ? err.error : { code: 'network', message: err.message, status: err.status };
    if (err.status === 401 && !req.url.includes('/public/auth/')) {
      auth.clear();
      router.navigateByUrl('/login');
    } else {
      notice.show(body);
    }
    return throwError(() => body);
  }));
};

export const authGuard: CanActivateFn = async () => {
  const auth = inject(Auth);
  const router = inject(Router);
  if (!auth.token()) {
    return router.parseUrl('/login');
  }
  if (!auth.me()) {
    try {
      await auth.refresh();
      if (!auth.org() && auth.orgs().length) auth.selectOrg(auth.orgs()[0].id);
    } catch {
      return router.parseUrl('/login');
    }
  }
  return true;
};
