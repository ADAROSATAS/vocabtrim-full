export type WordStatus = '' | 'keep' | 'crossed'
export type WordRecord = Record<string, unknown>

export interface ListShape {
  kind: 'array' | 'wrapper' | 'single' | 'ndjson'
  key?: string
  value?: Record<string, unknown>
}

export interface WordList {
  id: string
  name: string
  records: WordRecord[]
  statuses: WordStatus[]
  cursor: number
  shape: ListShape
  updatedAt: string
}

export interface VocabSettings {
  defaultDetails: boolean
  keepDuration: number
  crossDuration: number
}

export interface VocabSnapshot {
  format: 'vocabtrim-snapshot'
  schemaVersion: 1
  lists: WordList[]
  settings: VocabSettings
  savedAt: number
}

export interface UserView {
  id: number
  username: string
}

export interface AuthState {
  authenticated: boolean
  user: UserView | null
  csrfToken: string
}

export interface ApiErrorBody {
  code: string
  message: string
  timestamp?: string
}
