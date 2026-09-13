<script setup lang="ts">
defineProps<{ message: string; error?: boolean }>()
</script>
<template><div v-if="message" class="notice" :class="{ error }" role="status">{{ message }}</div></template>
