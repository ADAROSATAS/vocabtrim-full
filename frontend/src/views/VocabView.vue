<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ApiRequestError, downloadSnapshot, uploadSnapshot } from '@/api/client'
import NoticeToast from '@/components/NoticeToast.vue'
import RenameDialog from '@/components/RenameDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { useVocabStore } from '@/stores/vocab'
import { downloadText } from '@/utils/download'
import { displayName, exportWordList, getHeadword, getWordDetails } from '@/utils/wordlist'

const router = useRouter()
const auth = useAuthStore()
const vocab = useVocabStore()
const fileInput = ref<HTMLInputElement | null>(null)
const renameId = ref<string | null>(null)
const renameValue = ref('')
const notice = ref('')
const noticeError = ref(false)
const syncBusy = ref(false)
const openingId = ref<string | null>(null)
const moreOpenByClick = ref(false)
let noticeTimer: number | null = null

const list = computed(() => vocab.currentList)
const currentRecord = computed(() => list.value?.records[list.value.cursor])
const currentStatus = computed(() => list.value?.statuses[list.value.cursor] || '')
const headword = computed(() => getHeadword(currentRecord.value))
const details = computed(() => getWordDetails(currentRecord.value))
const isEmptyList = computed(() => Boolean(list.value && list.value.records.length === 0))

watch(() => auth.user?.id, async (id) => { if (id != null) await vocab.initialize(id) }, { immediate: true })

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))

function showNotice(message: string, error = false) {
  notice.value = message; noticeError.value = error
  if (noticeTimer != null) window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => { notice.value = '' }, error ? 3200 : 2200)
}

function phone(value: string): string {
  if (!value) return '—'
  return value.startsWith('/') && value.endsWith('/') ? value : `/${value}/`
}

async function logout() {
  try { await auth.logout() } finally { vocab.resetSession(); await router.replace('/login') }
}

async function importSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  try { await vocab.importFile(file); showNotice('导入成功') }
  catch (error) { showNotice(error instanceof Error ? error.message : '导入失败', true) }
}

function requestRename(id: string, name: string) { renameId.value = id; renameValue.value = displayName(name) }
async function confirmRename(value: string) {
  if (!renameId.value) return
  try { await vocab.renameList(renameId.value, value); renameId.value = null; showNotice('已重命名') }
  catch (error) { showNotice(error instanceof Error ? error.message : '重命名失败', true) }
}

async function removeList(id: string, name: string) {
  if (!window.confirm(`确定删除“${displayName(name)}”吗？`)) return
  await vocab.deleteList(id); showNotice('已删除')
}

function exportList(id: string) {
  const target = vocab.snapshot.lists.find((item) => item.id === id)
  if (!target) return
  downloadText(target.name, exportWordList(target)); showNotice('已导出')
}

async function produce() {
  try { await vocab.produceCurrent(); showNotice('已产出新词表') }
  catch (error) { showNotice(error instanceof Error ? error.message : '无法产出词表', true) }
}

async function upload() {
  if (syncBusy.value) return
  syncBusy.value = true
  try {
    await vocab.persist()
    let result
    try { result = await uploadSnapshot(vocab.snapshot, vocab.localEtag, auth.csrfToken) }
    catch (error) {
      if (error instanceof ApiRequestError && error.code === 'SYNC_CONFLICT') {
        const overwrite = window.confirm('云端数据已经被其他设备更新。强制上传会覆盖云端当前版本，确定继续吗？')
        if (!overwrite) return
        result = await uploadSnapshot(vocab.snapshot, vocab.localEtag, auth.csrfToken, true)
      } else throw error
    }
    vocab.setCloudEtag(result.etag); showNotice('已上传全部词表')
  } catch (error) { handleApiError(error, '上传同步失败') }
  finally { syncBusy.value = false }
}

async function download() {
  if (syncBusy.value) return
  syncBusy.value = true
  try {
    const remote = await downloadSnapshot()
    if (!window.confirm('下载会用远端数据覆盖本机全部词表，确定继续吗？')) return
    await vocab.replaceFromCloud(remote.snapshot, remote.etag)
    showNotice('已下载全部词表')
  } catch (error) { handleApiError(error, '下载同步失败') }
  finally { syncBusy.value = false }
}

function handleApiError(error: unknown, fallback: string) {
  if (error instanceof ApiRequestError && error.status === 401) {
    vocab.resetSession(); auth.user = null; void router.replace('/login'); return
  }
  showNotice(error instanceof Error ? error.message : fallback, true)
}

async function copyWord() {
  if (!headword.value) return
  try { await navigator.clipboard.writeText(headword.value); showNotice('已复制单词') }
  catch { showNotice('复制失败', true) }
}

async function bulk(mode: Parameters<typeof vocab.bulk>[0], message: string) {
  await vocab.bulk(mode); vocab.moreOpen = false; moreOpenByClick.value = false; showNotice(message)
}
async function alphabetical() { await vocab.reorderAlphabetically(); vocab.moreOpen = false; moreOpenByClick.value = false; showNotice('已按字母重整词序') }
async function shuffle() { await vocab.shuffle(); vocab.moreOpen = false; moreOpenByClick.value = false; showNotice('已打乱词序') }
async function toggleDefaultDetails() { await vocab.setDefaultDetails(!vocab.snapshot.settings.defaultDetails); vocab.moreOpen = false; moreOpenByClick.value = false; showNotice(vocab.snapshot.settings.defaultDetails ? '默认显示音义' : '默认隐藏音义') }

function moreMouseEnter() {
  if (!vocab.moreOpen) { vocab.moreOpen = true; moreOpenByClick.value = false }
}
function moreMouseLeave() { vocab.moreOpen = false; moreOpenByClick.value = false }
function toggleMore() {
  if (vocab.moreOpen && moreOpenByClick.value) { vocab.moreOpen = false; moreOpenByClick.value = false }
  else { vocab.moreOpen = true; moreOpenByClick.value = true }
}

async function openList(id: string) {
  if (openingId.value) return
  const target = vocab.snapshot.lists.find((item) => item.id === id)
  if (!target?.records.length) { showNotice('这个词表没有词条', true); return }
  openingId.value = id
  await new Promise((resolve) => window.setTimeout(resolve, 250))
  vocab.openList(id)
  openingId.value = null
}
function onListRowKey(event: KeyboardEvent, id: string) {
  if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openList(id) }
}
async function toggleDetails() {
  if (!list.value || vocab.marking || vocab.hidingDetails || isEmptyList.value) return
  if (!vocab.detailsVisible) { vocab.detailsVisible = true; return }
  vocab.hidingDetails = true
  await new Promise((resolve) => window.setTimeout(resolve, 220))
  vocab.detailsVisible = false
  vocab.hidingDetails = false
}

function onKeydown(event: KeyboardEvent) {
  if (!list.value || renameId.value || vocab.marking || vocab.hidingDetails || event.target instanceof HTMLInputElement) return
  if (event.key === 'ArrowLeft') void vocab.move(-1)
  else if (event.key === 'ArrowRight') void vocab.move(1)
}
</script>

<template>
  <div class="shell" :style="{ '--cross-motion-duration': `${vocab.snapshot.settings.crossDuration}ms` }">
    <template v-if="!list">
      <header class="topbar">
        <button class="brand" type="button" @click="vocab.closeList()">词表速筛</button>
        <div class="topbar-actions">
          <button class="topbar-action" type="button" :disabled="syncBusy" @click="logout">退出</button>
          <button class="topbar-action" type="button" :disabled="syncBusy" @click="upload">上传同步</button>
          <button class="topbar-action" type="button" :disabled="syncBusy" @click="download">下载同步</button>
          <button class="topbar-action" type="button" :disabled="syncBusy" @click="fileInput?.click()">本地导入</button>
        </div>
        <input ref="fileInput" class="hidden-input" type="file" accept=".json,application/json" @change="importSelected" />
      </header>

      <main class="page">
        <div v-if="vocab.sortedLists.length" class="list">
          <article v-for="item in vocab.sortedLists" :key="item.id" class="list-row" :class="{ 'is-opening': openingId === item.id }" tabindex="0" @click="openList(item.id)" @keydown="onListRowKey($event, item.id)">
            <div class="list-title">
              <h2>{{ displayName(item.name) }}</h2>
              <p class="list-meta">
                {{ item.records.length.toLocaleString('zh-CN') }} 词<span v-if="item.statuses.some(s => s === 'keep' || s === 'crossed')"> · 保留 {{ item.statuses.filter(s => s === 'keep').length }} · 去除 {{ item.statuses.filter(s => s === 'crossed').length }}</span>
              </p>
            </div>
            <div class="list-actions" @click.stop>
              <button type="button" @click="vocab.copyList(item.id)">创建副本</button>
              <button type="button" @click="requestRename(item.id, item.name)">重命名</button>
              <button type="button" @click="exportList(item.id)">导出</button>
              <button type="button" class="delete-action" @click="removeList(item.id, item.name)">删除</button>
            </div>
          </article>
        </div>
        <div v-else class="empty"><p>暂无词表</p></div>
      </main>
    </template>

    <main v-else class="page workspace-page">
      <header class="workspace-top">
        <button class="back-button" type="button" @click="vocab.closeList()">退出</button>
        <div class="more-menu" @mouseenter="moreMouseEnter" @mouseleave="moreMouseLeave">
          <button class="more-button" type="button" @click="toggleMore">更多</button>
          <div v-if="vocab.moreOpen" class="more-panel">
            <div class="more-actions">
              <button type="button" @click="toggleDefaultDetails">{{ vocab.snapshot.settings.defaultDetails ? '默认隐藏音义' : '默认显示音义' }}</button>
              <button type="button" @click="alphabetical">词序按字母重整</button>
              <button type="button" @click="shuffle">词序打乱</button>
              <button type="button" @click="bulk('clear', '已清空全部标记')">清空全部标记</button>
              <button type="button" @click="bulk('keep-unmarked', '已保留全部未标记')">保留全部未标记</button>
              <button type="button" @click="bulk('cross-unmarked', '已去除全部未标记')">去除全部未标记</button>
              <button type="button" @click="bulk('keep-all', '已保留全部')">保留全部</button>
              <button type="button" @click="bulk('cross-all', '已去除全部')">去除全部</button>
            </div>
            <label class="motion-control">
              <span class="motion-control-head"><span>「保留」动效时长</span><span class="motion-value">{{ vocab.snapshot.settings.keepDuration === 0 ? '即时' : vocab.snapshot.settings.keepDuration + ' 毫秒' }}</span></span>
              <input :value="vocab.snapshot.settings.keepDuration" type="range" min="0" max="1000" step="5" @input="vocab.setKeepDuration(Number(($event.target as HTMLInputElement).value))" />
            </label>
            <label class="motion-control">
              <span class="motion-control-head"><span>「去除」动效时长</span><span class="motion-value">{{ vocab.snapshot.settings.crossDuration === 0 ? '即时' : vocab.snapshot.settings.crossDuration + ' 毫秒' }}</span></span>
              <input :value="vocab.snapshot.settings.crossDuration" type="range" min="0" max="1000" step="5" @input="vocab.setCrossDuration(Number(($event.target as HTMLInputElement).value))" />
            </label>
          </div>
        </div>
        <button class="produce-button" type="button" @click="produce">产出新词表</button>
      </header>

      <section v-if="!isEmptyList" class="progress-area">
        <div class="progress-line range-wrap">
          <span class="progress-name">{{ displayName(list.name) }}</span>
          <input :value="list.cursor" type="range" min="0" :max="Math.max(0, list.records.length - 1)" :disabled="list.records.length <= 1 || vocab.marking || vocab.hidingDetails" @input="vocab.setCursor(Number(($event.target as HTMLInputElement).value))" />
          <span>{{ list.cursor + 1 }} / {{ list.records.length }}</span>
        </div>
      </section>

      <section class="word-area" :class="{ 'has-details': vocab.detailsVisible, 'is-marking': vocab.marking, 'is-cross-marking': vocab.marking && currentStatus === 'crossed', 'is-hiding': vocab.hidingDetails }">
        <template v-if="!isEmptyList">
          <div class="word-wrap">
            <h1 class="word is-clickable" :class="{ 'is-kept': currentStatus === 'keep', 'is-crossed': currentStatus === 'crossed', 'is-animating': vocab.marking }" tabindex="0" @click="copyWord" @keydown.enter="copyWord" @keydown.space.prevent="copyWord">{{ headword }}</h1>
          </div>
          <div v-if="vocab.detailsVisible" class="details-panel">
            <div class="phonetics">
              <p><span>英</span>{{ phone(details.uk) }}</p>
              <p><span>美</span>{{ phone(details.us) }}</p>
            </div>
            <div class="definitions">
              <p v-if="!details.definitions.length" class="definition">该词条未提供释义</p>
              <div v-for="(definition, index) in details.definitions" :key="definition.pos + definition.chinese + definition.english + index" class="definition">
                <span>{{ definition.pos || '释义' }}</span>
                <div>
                  <p v-if="definition.chinese">{{ definition.chinese }}</p>
                  <p v-if="definition.english" class="english">{{ definition.english }}</p>
                </div>
              </div>
            </div>
          </div>
        </template>
        <p v-else class="empty-workspace">这个词表没有词条</p>
      </section>

      <nav class="fixed-actions" aria-label="词条操作">
        <button data-action="next" type="button" :disabled="isEmptyList || list.cursor >= list.records.length - 1 || vocab.marking || vocab.hidingDetails" @click="vocab.move(1)">下个</button>
        <button data-action="previous" type="button" :disabled="isEmptyList || list.cursor <= 0 || vocab.marking || vocab.hidingDetails" @click="vocab.move(-1)">上个</button>
        <button data-action="details" type="button" :disabled="isEmptyList || vocab.marking || vocab.hidingDetails" @click="toggleDetails">音义</button>
        <button data-action="cross" type="button" :disabled="isEmptyList || vocab.marking || vocab.hidingDetails" @click="vocab.mark('crossed')">去除</button>
        <button data-action="keep" type="button" :disabled="isEmptyList || vocab.marking || vocab.hidingDetails" @click="vocab.mark('keep')">保留</button>
      </nav>
    </main>

    <RenameDialog :open="renameId !== null" :value="renameValue" @close="renameId = null" @confirm="confirmRename" />
    <NoticeToast :message="notice" :error="noticeError" />
  </div>
</template>
