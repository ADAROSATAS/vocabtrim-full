import { emptySnapshot, normalizeSnapshot } from '@/utils/snapshot'
import type { VocabSnapshot } from '@/types/vocab'

const DB_NAME = 'vocabtrim-full'
const STORE_NAME = 'snapshots'
const DB_VERSION = 1
const ETAG_PREFIX = 'vocabtrim-full.cloud-etag.v1:'

export async function loadLocalSnapshot(userId: number): Promise<VocabSnapshot> {
  try {
    const database = await openDb()
    const value = await requestPromise<unknown>(database.transaction(STORE_NAME, 'readonly').objectStore(STORE_NAME).get(userKey(userId)))
    database.close()
    if (!value) return emptySnapshot()
    const decoded = typeof value === 'string' ? JSON.parse(value) : value
    return normalizeSnapshot(decoded)
  } catch (error) {
    console.warn('IndexedDB load failed; using empty local snapshot', error)
    return emptySnapshot()
  }
}

export async function saveLocalSnapshot(userId: number, snapshot: VocabSnapshot): Promise<void> {
  const database = await openDb()
  try {
    // Pinia/Vue state is reactive (Proxy) and cannot be passed directly to
    // IndexedDB structured cloning. A VocabTrim snapshot is JSON by contract,
    // so persist the JSON representation instead.
    const payload = JSON.stringify(snapshot)
    await requestPromise(database.transaction(STORE_NAME, 'readwrite').objectStore(STORE_NAME).put(payload, userKey(userId)))
  } finally {
    database.close()
  }
}

export function loadCloudEtag(userId: number): string | null {
  return localStorage.getItem(`${ETAG_PREFIX}${userId}`)
}

export function saveCloudEtag(userId: number, etag: string | null): void {
  const key = `${ETAG_PREFIX}${userId}`
  if (etag) localStorage.setItem(key, etag)
  else localStorage.removeItem(key)
}

function userKey(userId: number) { return `user:${userId}` }

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(STORE_NAME)) db.createObjectStore(STORE_NAME)
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

function requestPromise<T = IDBValidKey>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}
