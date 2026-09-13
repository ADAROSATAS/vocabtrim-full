import type { ListShape, WordList, WordRecord } from '@/types/vocab'

const WRAPPER_KEYS = ['words', 'items', 'entries', 'data']

export function importWordList(text: string, filename: string): WordList {
  const imported = parseWordList(text)
  return {
    id: crypto.randomUUID(),
    name: filename || '未命名词表.json',
    records: imported.records,
    shape: imported.shape,
    statuses: imported.records.map(() => ''),
    cursor: 0,
    updatedAt: new Date().toISOString(),
  }
}

export function parseWordList(raw: string): { records: WordRecord[]; shape: ListShape } {
  const source = String(raw ?? '').replace(/^\uFEFF/, '').trim()
  if (!source) throw new Error('这个文件是空的')
  try {
    const parsed = JSON.parse(source) as unknown
    if (Array.isArray(parsed)) {
      const records = parsed.filter(isRecord)
      if (records.length !== parsed.length) throw new Error('词表数组中包含非对象条目')
      return { records, shape: { kind: 'array' } }
    }
    if (!isRecord(parsed)) throw new Error('JSON 顶层需要是词条对象或词条数组')
    for (const key of WRAPPER_KEYS) {
      if (!Array.isArray(parsed[key])) continue
      const values = parsed[key] as unknown[]
      const records = values.filter(isRecord)
      if (records.length !== values.length) throw new Error('词表数组中包含非对象条目')
      return { records, shape: { kind: 'wrapper', key, value: structuredClone(parsed) } }
    }
    return { records: [parsed], shape: { kind: 'single' } }
  } catch (wholeFileError) {
    const lines = source.split(/\r?\n/).filter((line) => line.trim())
    if (lines.length <= 1) throw wholeFileError instanceof Error ? wholeFileError : new Error('无法读取这个 JSON 文件')
    const records = lines.map((line, index) => {
      try {
        const record = JSON.parse(line) as unknown
        if (!isRecord(record)) throw new Error('不是词条对象')
        return record
      } catch {
        throw new Error(`第 ${index + 1} 行不是有效的 JSON 词条`)
      }
    })
    return { records, shape: { kind: 'ndjson' } }
  }
}

export function exportWordList(list: WordList): string {
  const shape = list.shape || { kind: 'array' as const }
  if (shape.kind === 'ndjson') return list.records.map((record) => JSON.stringify(record)).join('\n') + '\n'
  if (shape.kind === 'single') return JSON.stringify(list.records[0] || {}, null, 2)
  if (shape.kind === 'wrapper') return JSON.stringify({ ...(shape.value || {}), [shape.key || 'words']: list.records }, null, 2)
  return JSON.stringify(list.records, null, 2)
}

export function getHeadword(record: WordRecord | undefined): string {
  const outer = isRecord(record?.content) ? record.content : {}
  const word = isRecord(outer.word) ? outer.word : {}
  return text(record?.headWord) || text(word.wordHead) || text(record?.word) || text(record?.name) || '未命名单词'
}

export interface WordDefinition { pos: string; chinese: string; english: string }
export interface WordDetails { uk: string; us: string; definitions: WordDefinition[] }

export function getWordDetails(record: WordRecord | undefined): WordDetails {
  const outer = isRecord(record?.content) ? record.content : {}
  const word = isRecord(outer.word) ? outer.word : {}
  const content = isRecord(word.content) ? word.content : {}
  const uk = text(content.ukphone)
  const us = text(content.usphone)
  const fallbackPhone = text(content.phone) || text(record?.phonetic)
  const translations = Array.isArray(content.trans) ? content.trans : []
  const definitions = translations.filter(isRecord).map((item) => ({
    pos: text(item.pos), chinese: text(item.tranCn), english: text(item.tranOther),
  })).filter((item) => item.chinese || item.english)
  if (!definitions.length) {
    const fallback = text(record?.translation) || text(record?.definition) || text(record?.meaning)
    if (fallback) definitions.push({ pos: '', chinese: fallback, english: '' })
  }
  return { uk: uk || (!us ? fallbackPhone : ''), us, definitions }
}

export function stem(name: string): string { return String(name || '').replace(/\.json$/i, '') }
export function displayName(name: string): string { return stem(text(name) || '未命名词表.json') }

export function uniqueName(existing: string[], desired: string): string {
  const names = new Set(existing)
  if (!names.has(desired)) return desired
  const base = stem(desired)
  let number = 2
  while (names.has(`${base} (${number}).json`)) number += 1
  return `${base} (${number}).json`
}
export function copyName(name: string): string { return `${stem(name)} 副本.json` }
export function producedName(name: string): string { return `${stem(name)}-新.json` }

function isRecord(value: unknown): value is WordRecord { return Boolean(value) && typeof value === 'object' && !Array.isArray(value) }
function text(value: unknown): string { return typeof value === 'string' ? value.trim() : '' }
