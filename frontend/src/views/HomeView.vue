<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError } from '../api/client'
import { getExchange } from '../api/exchanges'

// Placeholder page (stories 12 and 13 build the real one). The check below proves the
// API client can reach the backend: it loads the seeded demo exchange.
const status = ref<'checking' | 'ok' | 'down'>('checking')
const detail = ref('')

onMounted(async () => {
  try {
    detail.value = (await getExchange('XMAS2026')).name
    status.value = 'ok'
  } catch (e) {
    status.value = 'down'
    detail.value = e instanceof ApiError ? e.message : 'Unexpected error'
  }
})
</script>

<template>
  <h1>Find or create an exchange</h1>
  <p>Coming soon: enter a code, browse public exchanges, or create your own.</p>
  <p class="card" data-test="api-status">
    <template v-if="status === 'checking'">Checking the backend…</template>
    <template v-else-if="status === 'ok'">Backend connected. Demo exchange: {{ detail }}</template>
    <template v-else><span class="error">Backend not reachable: {{ detail }}</span></template>
  </p>
</template>
