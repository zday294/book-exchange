<script setup lang="ts">
import type { Exchange } from '../api/exchanges'
import HostMode from './HostMode.vue'
import HostSettings from './HostSettings.vue'

/**
 * Everything only the host may do, at the bottom of the exchange page. Shown only when the server says
 * the viewer is the host (see ExchangeView). Each kind of control is its own block component listed
 * below, so a new block (the submit/claim mode switch, the party controls, ...) is added by writing it
 * and listing it here, without touching the others.
 */
defineProps<{ exchange: Exchange }>()
defineEmits<{ saved: [exchange: Exchange] }>()
</script>

<template>
  <section class="host card" aria-labelledby="host-title" data-test="host-section">
    <h2 id="host-title">Host controls</h2>
    <p class="intro">Only you can see this. Anyone with the exchange's code can still join.</p>

    <HostMode :exchange="exchange" @saved="$emit('saved', $event)" />
    <HostSettings :exchange="exchange" @saved="$emit('saved', $event)" />
    <!-- More host blocks go here, each with its own heading. -->
  </section>
</template>

<style scoped>
.host {
  margin-top: 2.5rem;
  border-color: var(--color-gold);
  border-width: 2px;
}
h2 {
  margin-top: 0;
}
.intro {
  color: var(--color-muted);
  margin-top: 0;
}
</style>
