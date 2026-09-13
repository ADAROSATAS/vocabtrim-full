import type { VocabSettings, VocabSnapshot, WordList, WordStatus } from '@/types/vocab'

export const DEFAULT_SETTINGS: VocabSettings = {
  defaultDetails: false,
  keepDuration: 60,
  crossDuration: 205,
}

export function emptySnapshot(): VocabSnapshot {
  return {
    format: 'vocabtrim-snapshot',
    schemaVersion: 1,
    lists: [],
    settings: { ...DEFAULT_SETTINGS },
    savedAt: Date.now(),
  }
}

export function normalizeSnapshot(input: unknown): VocabSnapshot {
  if (!input || typeof input !== 'object') throw new Error('同步数据格式不正确')
  const value = input as Partial<VocabSnapshot>
  if (value.format !== 'vocabtrim-snapshot' || value.schemaVersion !== 1 || !Array.isArray(value.lists)) {
    throw new Error('不支持的同步数据格式')
  }
  const lists = value.lists.map(normalizeList)
  const rawSettings = (value.settings && typeof value.settings === 'object') ? value.settings : DEFAULT_SETTINGS
  const settings: VocabSettings = {
    defaultDetails: Boolean(rawSettings.defaultDetails),
    keepDuration: clampDuration(rawSettings.keepDuration, DEFAULT_SETTINGS.keepDuration),
    crossDuration: clampDuration(rawSettings.crossDuration, DEFAULT_SETTINGS.crossDuration),
  }
  return {
    format: 'vocabtrim-snapshot', schemaVersion: 1, lists, settings,
    savedAt: typeof value.savedAt === 'number' ? value.savedAt : Date.now(),
  }
}

export function normalizeList(value: unknown): WordList {
  if (!value || typeof value !== 'object') throw new Error('词表数据格式不正确')
  const raw = value as Partial<WordList>
  if (!Array.isArray(raw.records)) throw new Error('词表缺少 records')
  const records = raw.records.filter((record): record is Record<string, unknown> => Boolean(record) && typeof record === 'object' && !Array.isArray(record))
  const statuses: WordStatus[] = Array.isArray(raw.statuses)
    ? records.map((_, index) => normalizeStatus(raw.statuses?.[index]))
    : records.map(() => '')
  const cursor = records.length === 0 ? 0 : Math.min(Math.max(Number.isInteger(raw.cursor) ? Number(raw.cursor) : 0, 0), records.length - 1)
  return {
    id: typeof raw.id === 'string' && raw.id ? raw.id : crypto.randomUUID(),
    name: typeof raw.name === 'string' && raw.name ? raw.name : '未命名词表.json',
    records,
    statuses,
    cursor,
    shape: raw.shape && typeof raw.shape === 'object' ? raw.shape : { kind: 'ndjson' },
    updatedAt: typeof raw.updatedAt === 'string' && raw.updatedAt ? raw.updatedAt : new Date().toISOString(),
  }
}

export function snapshotJson(snapshot: VocabSnapshot): string {
  return JSON.stringify({ ...snapshot, savedAt: Date.now() })
}

function normalizeStatus(value: unknown): WordStatus {
  return value === 'keep' || value === 'crossed' ? value : ''
}

function clampDuration(value: unknown, fallback: number): number {
  const number = Number(value)
  return Number.isFinite(number) ? Math.min(2000, Math.max(0, Math.round(number))) : fallback
}
