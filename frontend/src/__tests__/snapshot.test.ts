import { describe, expect, it } from 'vitest'
import { emptySnapshot, normalizeSnapshot } from '@/utils/snapshot'

describe('snapshot normalization', () => {
  it('creates the v1 snapshot contract', () => {
    const snapshot = emptySnapshot()
    expect(snapshot.format).toBe('vocabtrim-snapshot')
    expect(snapshot.schemaVersion).toBe(1)
  })

  it('normalizes missing statuses without losing records', () => {
    const snapshot = normalizeSnapshot({
      format: 'vocabtrim-snapshot', schemaVersion: 1, savedAt: 1,
      settings: { defaultDetails: false, keepDuration: 60, crossDuration: 205 },
      lists: [{ id: '1', name: 'a.json', records: [{ word: 'a' }], cursor: 0, shape: { kind: 'array' }, updatedAt: '2026-01-01T00:00:00Z' }],
    })
    expect(snapshot.lists[0]?.statuses).toEqual([''])
  })
})
