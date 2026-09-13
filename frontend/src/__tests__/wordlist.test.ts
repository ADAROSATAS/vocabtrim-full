import { describe, expect, it } from 'vitest'
import { copyName, exportWordList, getHeadword, importWordList, producedName, uniqueName } from '@/utils/wordlist'

describe('wordlist utilities', () => {
  it('imports an array and preserves it on export', () => {
    const list = importWordList('[{"headWord":"alpha"},{"headWord":"beta"}]', 'demo.json')
    expect(list.records).toHaveLength(2)
    expect(getHeadword(list.records[0])).toBe('alpha')
    expect(JSON.parse(exportWordList(list))).toEqual([{ headWord: 'alpha' }, { headWord: 'beta' }])
  })

  it('preserves wrapper metadata', () => {
    const list = importWordList('{"book":"A","words":[{"word":"hello"}]}', 'book.json')
    expect(JSON.parse(exportWordList(list))).toEqual({ book: 'A', words: [{ word: 'hello' }] })
  })

  it('generates the same style of copy and produced names', () => {
    expect(copyName('demo.json')).toBe('demo 副本.json')
    expect(producedName('demo.json')).toBe('demo-新.json')
    expect(uniqueName(['demo.json', 'demo (2).json'], 'demo.json')).toBe('demo (3).json')
  })
})
