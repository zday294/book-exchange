<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ApiError } from '../api/client'
import { submitBook, type SubmittedBook } from '../api/books'
import { getExchange, type Exchange } from '../api/exchanges'
import { validateSubmit, type SubmitErrors, type SubmitFields } from '../lib/submitValidation'
import { useParticipantStore } from '../stores/participant'
import WrappedPresent from '../wrapping/WrappedPresent.vue'
import WrappingPaperPicker from '../wrapping/WrappingPaperPicker.vue'

const props = defineProps<{ exchangeCode: string }>()
const participant = useParticipantStore()

const load = ref<'loading' | 'ready' | 'notfound' | 'error'>('loading')
const exchange = ref<Exchange | null>(null)

const form = reactive<SubmitFields>({
  submitterName: participant.name, // prefilled from the saved name (#8); editable
  bookTitle: '',
  amazonUrl: '',
  wrappingPaper: '',
})
const errors = ref<SubmitErrors>({})
const formError = ref('')
const submitting = ref(false)
const submitted = ref<SubmittedBook | null>(null)

const FIELD_IDS: Record<keyof SubmitFields, string> = {
  submitterName: 'submitter',
  bookTitle: 'title',
  amazonUrl: 'url',
  wrappingPaper: 'paper-picker',
}

async function loadExchange() {
  load.value = 'loading'
  try {
    exchange.value = await getExchange(props.exchangeCode)
    load.value = 'ready'
  } catch (e) {
    load.value = e instanceof ApiError && e.status === 404 ? 'notfound' : 'error'
  }
}
onMounted(loadExchange)

/** Once something is fixed, its message goes away. */
function clear(field: keyof SubmitFields) {
  if (errors.value[field]) errors.value = { ...errors.value, [field]: undefined }
}

async function focusFirstError() {
  await nextTick()
  const first = (Object.keys(FIELD_IDS) as (keyof SubmitFields)[]).find((f) => errors.value[f])
  if (first) document.getElementById(FIELD_IDS[first])?.focus()
}

async function submit() {
  formError.value = ''
  errors.value = validateSubmit(form)
  if (Object.values(errors.value).some(Boolean)) return focusFirstError()

  submitting.value = true
  try {
    const book = await submitBook(props.exchangeCode, {
      submitterName: form.submitterName.trim(),
      bookTitle: form.bookTitle.trim(),
      amazonUrl: form.amazonUrl.trim(),
      wrappingPaper: form.wrappingPaper,
    })
    // Like joining or hosting: whoever just submitted is the current user in this browser.
    participant.setName(book.submitterName)
    submitted.value = book
    form.bookTitle = ''
    form.amazonUrl = ''
    form.wrappingPaper = '' // the name stays
  } catch (e) {
    if (e instanceof ApiError && Object.keys(e.fieldErrors).length > 0) {
      errors.value = e.fieldErrors as SubmitErrors // 400: shown next to the fields; input is kept
      await focusFirstError()
    } else if (e instanceof ApiError && e.status === 409 && /already submitted/i.test(e.message)) {
      errors.value = { submitterName: e.message } // one book per person: shown by the name
      await focusFirstError()
    } else {
      formError.value = e instanceof ApiError ? e.message : 'Something went wrong. Please try again.'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <p v-if="load === 'loading'" role="status" data-test="loading">Loading…</p>

  <section v-else-if="load === 'notfound'" class="card" data-test="notfound">
    <h1>Exchange not found</h1>
    <p>We couldn't find an exchange with the code <strong>{{ exchangeCode }}</strong>.</p>
    <RouterLink class="button" to="/">Back to start</RouterLink>
  </section>

  <section v-else-if="load === 'error'" class="card" data-test="error">
    <h1>Something went wrong</h1>
    <p>We couldn't load this exchange. Please try again.</p>
    <button type="button" data-test="retry" @click="loadExchange">Try again</button>
  </section>

  <section v-else-if="exchange && exchange.status !== 'OPEN'" class="card" data-test="closed">
    <h1>{{ exchange.name }}</h1>
    <p>This exchange is closed and no longer accepts books.</p>
    <RouterLink class="button" :to="{ name: 'exchange', params: { exchangeCode } }">Back to the exchange</RouterLink>
  </section>

  <section v-else-if="submitted" class="card done" data-test="success">
    <h1>Your book is under the tree!</h1>
    <div class="preview">
      <WrappedPresent :paper="submitted.wrappingPaper" :tag="`From: ${submitted.submitterName}`" />
    </div>
    <p>Nobody will know what's inside until someone claims it.</p>
    <RouterLink class="button" :to="{ name: 'exchange', params: { exchangeCode } }" data-test="to-exchange">
      See the pile
    </RouterLink>
  </section>

  <template v-else-if="exchange">
    <h1>Add a book to {{ exchange.name }}</h1>
    <form class="card" novalidate @submit.prevent="submit" data-test="form">
      <p v-if="formError" class="error" role="alert" data-test="form-error">{{ formError }}</p>

      <label for="submitter">Your name</label>
      <input
        id="submitter"
        v-model="form.submitterName"
        :aria-invalid="!!errors.submitterName"
        aria-describedby="submitter-error"
        @input="clear('submitterName')"
      />
      <p id="submitter-error" class="error" data-test="error-submitterName">{{ errors.submitterName }}</p>

      <label for="title">Book name</label>
      <input
        id="title"
        v-model="form.bookTitle"
        :aria-invalid="!!errors.bookTitle"
        aria-describedby="title-error"
        @input="clear('bookTitle')"
      />
      <p id="title-error" class="error" data-test="error-bookTitle">{{ errors.bookTitle }}</p>

      <label for="url">Amazon link</label>
      <input
        id="url"
        v-model="form.amazonUrl"
        type="url"
        inputmode="url"
        autocomplete="off"
        placeholder="https://www.amazon.com/dp/..."
        :aria-invalid="!!errors.amazonUrl"
        aria-describedby="url-error url-hint"
        @input="clear('amazonUrl')"
      />
      <p id="url-hint" class="hint">Only the person who claims the book will see this link.</p>
      <p id="url-error" class="error" data-test="error-amazonUrl">{{ errors.amazonUrl }}</p>

      <div id="paper-picker" tabindex="-1">
        <WrappingPaperPicker v-model="form.wrappingPaper" @update:model-value="clear('wrappingPaper')" />
      </div>
      <p class="error" role="alert" data-test="error-wrappingPaper">{{ errors.wrappingPaper }}</p>

      <button type="submit" :disabled="submitting" data-test="submit">
        {{ submitting ? 'Adding…' : 'Add my book' }}
      </button>
    </form>
    <p><RouterLink :to="{ name: 'exchange', params: { exchangeCode } }">Back to the exchange</RouterLink></p>
  </template>
</template>

<style scoped>
form {
  display: grid;
  gap: 0.25rem;
  max-width: 44rem;
}
label {
  font-weight: 600;
  margin-top: 0.5rem;
}
.hint {
  color: var(--color-muted);
  font-size: 0.9rem;
  margin: 0;
}
.error {
  margin: 0;
  min-height: 1.25em;
}
#paper-picker {
  margin-top: 0.75rem;
  outline: none;
}
button[type='submit'] {
  margin-top: 0.75rem;
  justify-self: start;
}
.done {
  text-align: center;
}
.preview {
  width: min(12rem, 60%);
  margin: 1rem auto;
}
</style>
