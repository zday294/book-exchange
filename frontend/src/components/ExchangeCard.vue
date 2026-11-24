<script setup lang="ts">
import { computed } from 'vue'
import type { ExchangeSummary } from '../api/exchanges'

const props = defineProps<{ exchange: ExchangeSummary }>()
const open = computed(() => props.exchange.status === 'OPEN')
const books = computed(() => `${props.exchange.bookCount} ${props.exchange.bookCount === 1 ? 'book' : 'books'}`)
</script>

<template>
  <RouterLink
    class="card exchange-card"
    :class="{ closed: !open }"
    :to="{ name: 'exchange', params: { exchangeCode: exchange.code } }"
    :data-test="`exchange-${exchange.code}`"
  >
    <span class="top">
      <strong class="name" data-test="card-name">{{ exchange.name }}</strong>
      <span class="badge" :class="open ? 'open' : 'closed'" data-test="card-status">{{ open ? 'Open' : 'Closed' }}</span>
    </span>
    <span v-if="exchange.description" class="description" data-test="card-description">{{ exchange.description }}</span>
    <span class="counts" data-test="card-counts">{{ books }} · {{ exchange.claimedCount }} claimed</span>
  </RouterLink>
</template>

<style scoped>
.exchange-card {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  text-decoration: none;
  color: inherit;
}
.exchange-card:hover,
.exchange-card:focus-visible {
  border-color: var(--color-gold);
}
.exchange-card.closed {
  background: #f4efe6;
}
.top {
  display: flex;
  justify-content: space-between;
  gap: 0.75rem;
  align-items: baseline;
}
.name {
  font-family: var(--font-display);
  font-size: 1.1rem;
  color: var(--color-red);
}
.badge {
  font-size: 0.75rem;
  font-weight: 600;
  padding: 0.1rem 0.5rem;
  border-radius: 999px;
  white-space: nowrap;
}
.badge.open {
  background: #dff1e6;
  color: var(--color-green);
}
.badge.closed {
  background: #e7e2d6;
  color: var(--color-muted);
}
.description {
  color: var(--color-text);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.counts {
  color: var(--color-muted);
  font-size: 0.9rem;
}
</style>
