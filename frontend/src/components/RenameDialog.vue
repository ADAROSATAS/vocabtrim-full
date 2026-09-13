<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
const props = defineProps<{ open: boolean; value: string }>()
const emit = defineEmits<{ close: []; confirm: [value: string] }>()
const name = ref('')
const input = ref<HTMLInputElement | null>(null)
watch(() => props.open, async (open) => {
  if (open) { name.value = props.value; await nextTick(); input.value?.focus(); input.value?.select() }
})
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('close')
  if (event.key === 'Enter') emit('confirm', name.value)
}
</script>
<template>
  <div v-if="open" class="rename-backdrop" @mousedown.self="$emit('close')">
    <div class="rename-dialog" role="dialog" aria-modal="true" aria-labelledby="rename-title">
      <h2 id="rename-title">重命名</h2>
      <input ref="input" v-model="name" maxlength="180" @keydown="onKeydown" />
      <div class="rename-dialog-actions">
        <button type="button" @click="$emit('close')">取消</button>
        <button type="button" class="rename-confirm" @click="$emit('confirm', name)">确定</button>
      </div>
    </div>
  </div>
</template>
