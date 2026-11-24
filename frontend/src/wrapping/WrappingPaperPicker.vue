<script setup lang="ts">
import { useId } from 'vue'
import WrappedPresent from './WrappedPresent.vue'
import { WRAPPING_PAPERS } from './papers'

defineProps<{ modelValue?: string | null }>()
const emit = defineEmits<{ 'update:modelValue': [key: string] }>()

const groupName = `paper-${useId()}`
</script>

<template>
  <fieldset class="picker">
    <legend>Wrapping paper</legend>
    <div class="options">
      <label v-for="paper in WRAPPING_PAPERS" :key="paper.key" class="option" :class="{ selected: modelValue === paper.key }">
        <input
          type="radio"
          :name="groupName"
          :value="paper.key"
          :checked="modelValue === paper.key"
          :data-test="`paper-${paper.key}`"
          @change="emit('update:modelValue', paper.key)"
        />
        <WrappedPresent :paper="paper.key" aria-hidden="true" />
        <span class="name">{{ paper.label }}</span>
      </label>
    </div>
  </fieldset>
</template>

<style scoped>
.picker {
  border: 0;
  padding: 0;
  margin: 0;
}
legend {
  font-weight: 600;
  margin-bottom: 0.5rem;
}
.options {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(7rem, 1fr));
  gap: 0.75rem;
}
.option {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.25rem;
  padding: 0.5rem;
  border: 2px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
  cursor: pointer;
}
.option:hover {
  border-color: var(--color-gold);
}
.option.selected {
  border-color: var(--color-red);
  box-shadow: 0 0 0 2px var(--color-red);
}
/* The radio stays in the page for keyboard and screen-reader users, but is visually hidden. */
.option input {
  position: absolute;
  opacity: 0;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  cursor: pointer;
}
.option:focus-within {
  outline: 3px solid var(--color-gold);
  outline-offset: 2px;
}
.name {
  font-size: 0.9rem;
}
</style>
