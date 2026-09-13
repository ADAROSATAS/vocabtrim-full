import type { ApiErrorBody, AuthState, UserView, VocabSnapshot } from '@/types/vocab'

export class ApiRequestError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message) }
}

export async function getAuthState(): Promise<AuthState> {
  return jsonRequest<AuthState>('/api/v1/auth/me')
}

export async function register(username: string, password: string, confirmPassword: string, csrfToken: string): Promise<UserView> {
  return jsonRequest<UserView>('/api/v1/auth/register', {
    method: 'POST', headers: unsafeHeaders(csrfToken),
    body: JSON.stringify({ username, password, confirmPassword }),
  })
}

export async function login(username: string, password: string, csrfToken: string): Promise<{ user: UserView }> {
  const form = new URLSearchParams({ username, password })
  return jsonRequest<{ user: UserView }>('/api/v1/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': csrfToken }, body: form,
  })
}

export async function logout(csrfToken: string): Promise<void> {
  await jsonRequest('/api/v1/auth/logout', { method: 'POST', headers: unsafeHeaders(csrfToken) })
}

export async function downloadSnapshot(): Promise<{ snapshot: VocabSnapshot; etag: string }> {
  const response = await fetch('/api/v1/snapshot', { credentials: 'same-origin', cache: 'no-store' })
  if (!response.ok) throw await toError(response)
  const etag = response.headers.get('ETag')
  if (!etag) throw new ApiRequestError(500, 'MISSING_ETAG', '服务器没有返回同步版本')
  return { snapshot: await response.json() as VocabSnapshot, etag }
}

export async function uploadSnapshot(snapshot: VocabSnapshot, etag: string | null, csrfToken: string, force = false): Promise<{ etag: string }> {
  const headers: Record<string, string> = unsafeHeaders(csrfToken)
  if (!force) {
    if (etag) headers['If-Match'] = etag
    else headers['If-None-Match'] = '*'
  }
  const response = await fetch(`/api/v1/snapshot${force ? '?force=true' : ''}`, {
    method: 'PUT', credentials: 'same-origin', headers,
    body: JSON.stringify(snapshot),
  })
  if (!response.ok) throw await toError(response)
  const nextEtag = response.headers.get('ETag')
  if (!nextEtag) throw new ApiRequestError(500, 'MISSING_ETAG', '服务器没有返回同步版本')
  return { etag: nextEtag }
}

function unsafeHeaders(csrfToken: string): Record<string, string> {
  return { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken }
}

async function jsonRequest<T = unknown>(input: RequestInfo | URL, init?: RequestInit): Promise<T> {
  const response = await fetch(input, { ...init, credentials: 'same-origin', cache: 'no-store' })
  if (!response.ok) throw await toError(response)
  if (response.status === 204) return undefined as T
  return await response.json() as T
}

async function toError(response: Response): Promise<ApiRequestError> {
  let body: Partial<ApiErrorBody> = {}
  try { body = await response.json() as Partial<ApiErrorBody> } catch { /* ignore non-json error */ }
  return new ApiRequestError(response.status, body.code || `HTTP_${response.status}`, body.message || `请求失败 (${response.status})`)
}
