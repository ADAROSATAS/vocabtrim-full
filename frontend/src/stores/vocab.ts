import { defineStore } from 'pinia'
import type { VocabSnapshot, WordList, WordStatus } from '@/types/vocab'
import { emptySnapshot, normalizeSnapshot } from '@/utils/snapshot'
import { copyName, getHeadword, importWordList, producedName, uniqueName } from '@/utils/wordlist'
import { loadCloudEtag, loadLocalSnapshot, saveCloudEtag, saveLocalSnapshot } from '@/utils/localRepository'

let persistTimer: number | null = null

export const useVocabStore = defineStore('vocab', {
  state: () => ({
    userId: null as number | null,
    snapshot: emptySnapshot(),
    currentListId: null as string | null,
    detailsVisible: false,
    marking: false,
    hidingDetails: false,
    moreOpen: false,
    localEtag: null as string | null,
  }),
  getters: {
    currentList(state): WordList | null { return state.snapshot.lists.find((list) => list.id === state.currentListId) || null },
    sortedLists(state): WordList[] { return state.snapshot.lists },
  },
  actions: {
    async initialize(userId: number) {
      if (this.userId === userId) return
      this.userId = userId
      this.snapshot = await loadLocalSnapshot(userId)
      this.localEtag = loadCloudEtag(userId)
      this.currentListId = null
      this.moreOpen = false
    },
    resetSession() {
      if (persistTimer != null) { window.clearTimeout(persistTimer); persistTimer = null }
      this.userId = null; this.snapshot = emptySnapshot(); this.currentListId = null; this.localEtag = null; this.moreOpen = false
    },
    async persist() {
      if (persistTimer != null) { window.clearTimeout(persistTimer); persistTimer = null }
      if (this.userId == null) return
      this.snapshot.savedAt = Math.max(Date.now(), this.snapshot.savedAt + 1)
      await saveLocalSnapshot(this.userId, this.snapshot)
    },
    persistSoon(delayMs = 140) {
      if (persistTimer != null) window.clearTimeout(persistTimer)
      persistTimer = window.setTimeout(() => {
        persistTimer = null
        void this.persist().catch((error) => console.error('IndexedDB save failed', error))
      }, delayMs)
    },
    async importFile(file: File) {
      if (!/\.json$/i.test(file.name)) throw new Error('请选择 .json 词表文件')
      const list = importWordList(await file.text(), file.name)
      if (!list.records.length) throw new Error('词表中没有可用词条')
      list.name = uniqueName(this.snapshot.lists.map((item) => item.name), list.name)
      this.snapshot.lists.unshift(list)
      await this.persist()
    },
    openList(id: string): boolean {
      const list = this.snapshot.lists.find((item) => item.id === id)
      if (!list || !list.records.length) return false
      this.currentListId = id
      this.detailsVisible = this.snapshot.settings.defaultDetails
      this.moreOpen = false
      return true
    },
    closeList() { this.currentListId = null; this.moreOpen = false },
    async renameList(id: string, desired: string) {
      const list = this.snapshot.lists.find((item) => item.id === id)
      if (!list) return
      const clean = desired.replace(/\.json$/i, '').trim()
      if (!clean) throw new Error('词表名称不能为空')
      const name = `${clean}.json`
      const duplicate = this.snapshot.lists.some((item) => item.id !== id && item.name.toLowerCase() === name.toLowerCase())
      if (duplicate) throw new Error('已经有同名词表')
      list.name = name; list.updatedAt = new Date().toISOString(); await this.persist()
    },
    async deleteList(id: string) {
      this.snapshot.lists = this.snapshot.lists.filter((item) => item.id !== id)
      if (this.currentListId === id) this.currentListId = null
      await this.persist()
    },
    async copyList(id: string) {
      const source = this.snapshot.lists.find((item) => item.id === id)
      if (!source) return
      const copy: WordList = cloneJson(source)
      copy.id = crypto.randomUUID()
      copy.name = uniqueName(this.snapshot.lists.map((item) => item.name), copyName(source.name))
      copy.statuses = copy.records.map(() => '')
      copy.cursor = 0
      copy.updatedAt = new Date().toISOString()
      this.snapshot.lists.unshift(copy)
      await this.persist()
    },
    async produceCurrent() {
      const source = this.currentList
      if (!source) return
      const records = source.records.filter((_, index) => source.statuses[index] === 'keep')
      if (!records.length) throw new Error('请至少保留一个词')
      const produced: WordList = {
        id: crypto.randomUUID(),
        name: uniqueName(this.snapshot.lists.map((item) => item.name), producedName(source.name)),
        records: cloneJson(records), statuses: records.map(() => ''), cursor: 0,
        shape: cloneJson(source.shape), updatedAt: new Date().toISOString(),
      }
      this.snapshot.lists.unshift(produced)
      this.currentListId = null
      this.moreOpen = false
      await this.persist()
    },
    setCursor(index: number) {
      const list = this.currentList
      if (!list || !list.records.length) return
      list.cursor = Math.min(Math.max(Math.round(index), 0), list.records.length - 1)
      this.persistSoon()
    },
    move(delta: number) { const list = this.currentList; if (list && !this.marking && !this.hidingDetails) this.setCursor(list.cursor + delta) },
    async mark(status: Exclude<WordStatus, ''>) {
      const list = this.currentList
      if (!list || !list.records.length || this.marking || this.hidingDetails) return
      list.statuses[list.cursor] = status
      list.updatedAt = new Date().toISOString()
      const duration = status === 'keep' ? this.snapshot.settings.keepDuration : this.snapshot.settings.crossDuration
      this.marking = true
      try {
        await delay(duration)
        if (list.cursor < list.records.length - 1) list.cursor += 1
        this.detailsVisible = this.snapshot.settings.defaultDetails
        await this.persist()
      } finally {
        this.marking = false
      }
    },
    async bulk(mode: 'clear' | 'keep-unmarked' | 'cross-unmarked' | 'keep-all' | 'cross-all') {
      const list = this.currentList
      if (!list) return
      list.statuses = list.statuses.map((status) => {
        if (mode === 'clear') return ''
        if (mode === 'keep-unmarked') return status || 'keep'
        if (mode === 'cross-unmarked') return status || 'crossed'
        if (mode === 'keep-all') return 'keep'
        return 'crossed'
      })
      list.updatedAt = new Date().toISOString(); await this.persist()
    },
    async reorderAlphabetically() {
      const list = this.currentList; if (!list) return
      const current = list.records[list.cursor]
      const pairs = list.records.map((record, index) => ({ record, status: list.statuses[index] || '' as WordStatus }))
      const collator = new Intl.Collator('en', { numeric: true, sensitivity: 'base' })
      pairs.sort((a, b) => collator.compare(getHeadword(a.record), getHeadword(b.record)))
      list.records = pairs.map((pair) => pair.record); list.statuses = pairs.map((pair) => pair.status)
      list.cursor = current ? Math.max(0, list.records.indexOf(current)) : 0
      list.updatedAt = new Date().toISOString(); await this.persist()
    },
    async shuffle() {
      const list = this.currentList; if (!list) return
      const current = list.records[list.cursor]
      const pairs = list.records.map((record, index) => ({ record, status: list.statuses[index] || '' as WordStatus }))
      for (let index = pairs.length - 1; index > 0; index--) {
        const target = Math.floor(Math.random() * (index + 1)); [pairs[index], pairs[target]] = [pairs[target]!, pairs[index]!]
      }
      list.records = pairs.map((pair) => pair.record); list.statuses = pairs.map((pair) => pair.status)
      list.cursor = current ? Math.max(0, list.records.indexOf(current)) : 0
      list.updatedAt = new Date().toISOString(); await this.persist()
    },
    async setDefaultDetails(value: boolean) { this.snapshot.settings.defaultDetails = value; if (this.currentList) this.detailsVisible = value; await this.persist() },
    setKeepDuration(value: number) { this.snapshot.settings.keepDuration = value; this.persistSoon() },
    setCrossDuration(value: number) { this.snapshot.settings.crossDuration = value; this.persistSoon() },
    async replaceFromCloud(snapshot: unknown, etag: string) {
      this.snapshot = normalizeSnapshot(snapshot)
      this.currentListId = null
      this.localEtag = etag
      if (this.userId != null) saveCloudEtag(this.userId, etag)
      await this.persist()
    },
    setCloudEtag(etag: string) {
      this.localEtag = etag
      if (this.userId != null) saveCloudEtag(this.userId, etag)
    },
  },
})

function cloneJson<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function delay(ms: number) { return new Promise((resolve) => window.setTimeout(resolve, ms)) }
