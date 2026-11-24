<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useParticipantStore, validateName } from '../stores/participant'

/**
 * Asks for the visitor's name. Two uses:
 *  - required: the first-visit gate. It can't be dismissed; the page behind it isn't shown.
 *  - not required: changing an existing name. It can be cancelled (button or Escape).
 */
const props = defineProps<{ required: boolean }>()

const participant = useParticipantStore()
const name = ref(participant.name)
const error = ref('')
const input = ref<HTMLInputElement | null>(null)

onMounted(() => {
  input.value?.focus()
  input.value?.select()
})

function save() {
  error.value = validateName(name.value)
  if (error.value) return
  participant.setName(name.value)
  participant.closeDialog()
}

function cancel() {
  if (!props.required) participant.closeDialog()
}
</script>

<template>
  <div class="backdrop" @keydown.esc.prevent="cancel">
    <div class="dialog card" role="dialog" aria-modal="true" aria-labelledby="name-title" data-test="name-dialog">
      <h2 id="name-title">{{ required ? "What's your name?" : 'Change your name' }}</h2>
      <p v-if="required" class="hint">
        We use it to show who brought and who claimed each book. We'll remember it on this device.
      </p>
      <form novalidate @submit.prevent="save">
        <label for="participant-name">Your name</label>
        <input
          id="participant-name"
          ref="input"
          v-model="name"
          autocomplete="given-name"
          :aria-invalid="!!error"
          aria-describedby="name-error"
          data-test="name-input"
        />
        <p id="name-error" class="error" role="alert" data-test="name-error">{{ error }}</p>
        <div class="actions">
          <button type="submit" data-test="name-save">{{ required ? 'Continue' : 'Save' }}</button>
          <button v-if="!required" type="button" class="secondary" data-test="name-cancel" @click="cancel">
            Cancel
          </button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.backdrop {
  position: fixed;
  inset: 0;
  background: rgb(0 0 0 / 0.45);
  display: grid;
  place-items: center;
  padding: 1rem;
  z-index: 10;
}
.dialog {
  width: min(26rem, 100%);
}
h2 {
  margin-top: 0;
}
label {
  display: block;
  font-weight: 600;
  margin-bottom: 0.25rem;
}
.hint {
  color: var(--color-muted);
}
.error {
  min-height: 1.5em;
  margin: 0.25rem 0;
}
.actions {
  display: flex;
  gap: 0.5rem;
}
</style>
