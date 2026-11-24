<script setup lang="ts">
import { ref } from 'vue'
import WrappedPresent from '../wrapping/WrappedPresent.vue'
import WrappingPaperPicker from '../wrapping/WrappingPaperPicker.vue'

// Development-only page (see the router) for looking at the papers while designing them.
// The big view IS the picker: click a present to choose it.
const selected = ref('holly')
</script>

<template>
  <h1>Wrapping paper gallery</h1>
  <p>Click a present to choose that paper.</p>
  <WrappingPaperPicker v-model="selected" />
  <p>Selected: <strong>{{ selected }}</strong></p>

  <h2>Edge cases</h2>
  <div class="edge">
    <figure>
      <WrappedPresent paper="not-a-paper" tag="Zach" />
      <figcaption>unknown key (falls back)</figcaption>
    </figure>
    <figure>
      <WrappedPresent :paper="selected" tag="Bartholomew" />
      <figcaption>long name on the tag</figcaption>
    </figure>
    <figure class="small">
      <WrappedPresent :paper="selected" tag="Zach" />
      <figcaption>small</figcaption>
    </figure>
  </div>
</template>

<style scoped>
.edge {
  display: flex;
  gap: 1.5rem;
  align-items: end;
  flex-wrap: wrap;
}
figure {
  margin: 0;
  width: 11rem;
}
figure.small {
  width: 5rem;
}
figcaption {
  text-align: center;
  color: var(--color-muted);
  font-size: 0.85rem;
}
</style>
