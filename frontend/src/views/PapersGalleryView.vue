<script setup lang="ts">
import { ref } from 'vue'
import WrappedPresent from '../wrapping/WrappedPresent.vue'
import WrappingPaperPicker from '../wrapping/WrappingPaperPicker.vue'
import { WRAPPING_PAPERS } from '../wrapping/papers'

// Development-only page (see the router) for looking at the papers while designing them.
const selected = ref('holly')
</script>

<template>
  <h1>Wrapping paper gallery</h1>
  <div class="grid">
    <figure v-for="p in WRAPPING_PAPERS" :key="p.key">
      <WrappedPresent :paper="p.key" tag="Alexandria" />
      <figcaption>{{ p.key }}</figcaption>
    </figure>
    <figure>
      <WrappedPresent paper="not-a-paper" tag="Zach" />
      <figcaption>unknown key (fallback)</figcaption>
    </figure>
    <figure class="small"><WrappedPresent paper="plaid" tag="Zach" /><figcaption>small</figcaption></figure>
  </div>
  <h2>Picker</h2>
  <WrappingPaperPicker v-model="selected" />
  <p>Selected: {{ selected }}</p>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(11rem, 1fr));
  gap: 1rem;
  align-items: end;
}
figure {
  margin: 0;
}
figcaption {
  text-align: center;
  color: var(--color-muted);
  font-size: 0.85rem;
}
.small {
  max-width: 5rem;
}
</style>
