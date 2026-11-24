<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ApiError } from '../api/client'
import { updateExchange, type Exchange } from '../api/exchanges'

/**
 * The host's "Edit settings" block: the same settings as the create-exchange page. Everything the server
 * validates comes back as per-field messages; what was typed is always kept.
 */
const props = defineProps<{ exchange: Exchange }>()
const emit = defineEmits<{ saved: [exchange: Exchange] }>()

interface Values {
  name: string
  description: string
  organizerName: string
  listed: boolean
}
const valuesOf = (e: Exchange): Values => ({
  name: e.name,
  description: e.description ?? '',
  organizerName: e.organizerName ?? '',
  listed: e.listed,
})

const baseline = ref<Values>(valuesOf(props.exchange)) // what the server currently has
const form = reactive<Values>({ ...baseline.value })

const dirty = computed(
  () =>
    form.name !== baseline.value.name ||
    form.description !== baseline.value.description ||
    form.organizerName !== baseline.value.organizerName ||
    form.listed !== baseline.value.listed,
)

// The page refreshes the exchange every few seconds. Update the form from that only while the host has
// no unsaved edits, so typing is never overwritten.
watch(
  () => JSON.stringify(valuesOf(props.exchange)), // compare by content: the page hands over a new object every refresh
  () => {
    const fresh = valuesOf(props.exchange)
    const untouched = !dirty.value
    baseline.value = fresh
    if (untouched) Object.assign(form, fresh)
  },
)

const saving = ref(false)
const saved = ref(false)
const fieldErrors = ref<Record<string, string>>({})
const formError = ref('')

function edited() {
  saved.value = false
  formError.value = ''
}

function undo() {
  Object.assign(form, baseline.value)
  fieldErrors.value = {}
  formError.value = ''
  saved.value = false
}

async function save() {
  saving.value = true
  saved.value = false
  fieldErrors.value = {}
  formError.value = ''
  try {
    const updated = await updateExchange(props.exchange.code, {
      name: form.name,
      description: form.description, // '' removes it
      organizerName: form.organizerName,
      listed: form.listed,
    })
    baseline.value = valuesOf(updated)
    Object.assign(form, baseline.value) // shows the cleaned-up (trimmed) values
    saved.value = true
    emit('saved', updated)
  } catch (e) {
    if (e instanceof ApiError && Object.keys(e.fieldErrors).length > 0) {
      fieldErrors.value = e.fieldErrors
    } else if (e instanceof ApiError && e.status === 403) {
      formError.value = "You're not the host of this exchange on this device, so it can't be changed."
    } else {
      formError.value = e instanceof ApiError ? e.message : 'Something went wrong. Please try again.'
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="block" aria-labelledby="settings-title" data-test="host-settings">
    <h3 id="settings-title">Edit settings</h3>
    <form novalidate @submit.prevent="save" data-test="settings-form">
      <p v-if="formError" class="error" role="alert" data-test="settings-error">{{ formError }}</p>

      <label for="host-name">Name of the exchange</label>
      <input id="host-name" v-model="form.name" maxlength="200" :aria-invalid="!!fieldErrors.name" @input="edited" />
      <p v-if="fieldErrors.name" class="error" data-test="error-name">{{ fieldErrors.name }}</p>

      <label for="host-description">Description <span class="hint">(optional)</span></label>
      <textarea
        id="host-description"
        v-model="form.description"
        rows="3"
        maxlength="1000"
        :aria-invalid="!!fieldErrors.description"
        @input="edited"
      />
      <p v-if="fieldErrors.description" class="error" data-test="error-description">{{ fieldErrors.description }}</p>

      <label for="host-organizer">Host's name</label>
      <input
        id="host-organizer"
        v-model="form.organizerName"
        maxlength="100"
        :aria-invalid="!!fieldErrors.organizerName"
        @input="edited"
      />
      <p v-if="fieldErrors.organizerName" class="error" data-test="error-organizerName">{{ fieldErrors.organizerName }}</p>

      <label class="check">
        <input v-model="form.listed" type="checkbox" data-test="listed" @change="edited" />
        Show in the public list
      </label>
      <p class="hint" data-test="listed-hint">
        {{ form.listed ? 'Anyone can find this exchange in the public list.' : 'Only people with the link or code can join.' }}
      </p>

      <div class="actions">
        <button type="submit" :disabled="!dirty || saving" data-test="settings-save">
          {{ saving ? 'Saving…' : 'Save changes' }}
        </button>
        <button type="button" class="secondary" :disabled="!dirty || saving" data-test="settings-undo" @click="undo">
          Undo changes
        </button>
        <span v-if="saved && !dirty" class="saved" role="status" data-test="settings-saved">Saved</span>
      </div>
    </form>
  </section>
</template>

<style scoped>
h3 {
  margin-top: 0;
}
form {
  display: grid;
  gap: 0.35rem;
  max-width: 34rem;
}
label {
  font-weight: 600;
  margin-top: 0.5rem;
}
.check {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.check input {
  width: auto;
}
.hint {
  color: var(--color-muted);
  font-weight: normal;
  font-size: 0.9rem;
  margin: 0;
}
.error {
  margin: 0;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.75rem;
}
.saved {
  color: var(--color-green);
  font-weight: 600;
}
</style>
