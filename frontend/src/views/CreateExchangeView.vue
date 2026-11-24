<script setup lang="ts">
import { computed, ref } from 'vue'
import { ApiError } from '../api/client'
import { createExchange, type Exchange } from '../api/exchanges'
import { saveHostToken } from '../lib/hostToken'
import { useParticipantStore } from '../stores/participant'

const participant = useParticipantStore()

const name = ref('')
const description = ref('')
const organizerName = ref(participant.name) // prefilled from the saved participant name (#8)
const listed = ref(true)

const submitting = ref(false)
const fieldErrors = ref<Record<string, string>>({})
const formError = ref('')
const created = ref<Exchange | null>(null)
const copyState = ref<'idle' | 'copied' | 'failed'>('idle')

const shareUrl = computed(() =>
  created.value ? `${window.location.origin}/exchange/${created.value.code}` : '',
)

async function submit() {
  submitting.value = true
  fieldErrors.value = {}
  formError.value = ''
  try {
    const exchange = await createExchange({
      name: name.value,
      description: description.value,
      organizerName: organizerName.value,
      listed: listed.value,
    })
    // The host is now "the current user" in this browser, exactly as if they had entered their name
    // to join: the exchange page won't ask again and the header shows who they are.
    participant.setName(exchange.organizerName ?? organizerName.value)
    // Shown once by the server: keep it so this browser stays the host of this exchange.
    if (exchange.hostToken) saveHostToken(exchange.code, exchange.hostToken)
    created.value = exchange
  } catch (e) {
    if (e instanceof ApiError && Object.keys(e.fieldErrors).length > 0) {
      fieldErrors.value = e.fieldErrors
    } else {
      formError.value = e instanceof ApiError ? e.message : 'Something went wrong. Please try again.'
    }
  } finally {
    submitting.value = false
  }
}

async function copyLink() {
  try {
    await navigator.clipboard.writeText(shareUrl.value)
    copyState.value = 'copied'
  } catch {
    copyState.value = 'failed' // clipboard blocked: the link is still shown for manual copying
  }
}
</script>

<template>
  <h1>Create an exchange</h1>

  <section v-if="created" class="card" data-test="created">
    <h2>{{ created.name }} is ready!</h2>
    <p>Share this link with your group:</p>
    <p class="share">
      <input :value="shareUrl" readonly aria-label="Shareable link" data-test="share-url" @focus="($event.target as HTMLInputElement).select()" />
      <button type="button" @click="copyLink" data-test="copy">Copy link</button>
    </p>
    <p v-if="copyState === 'copied'" role="status" data-test="copy-status">Copied!</p>
    <p v-else-if="copyState === 'failed'" role="status" class="error" data-test="copy-status">
      Couldn't copy automatically. Select the link above and copy it.
    </p>
    <p>
      Or give out the code: <strong class="code" data-test="code">{{ created.code }}</strong>
    </p>
    <RouterLink class="button" :to="{ name: 'exchange', params: { exchangeCode: created.code } }" data-test="open">
      Go to the exchange
    </RouterLink>
  </section>

  <form v-else class="card" novalidate @submit.prevent="submit" data-test="form">
    <p v-if="formError" class="error" role="alert" data-test="form-error">{{ formError }}</p>

    <label for="name">Name of the exchange</label>
    <input id="name" v-model="name" maxlength="200" required :aria-invalid="!!fieldErrors.name" />
    <p v-if="fieldErrors.name" class="error" data-test="error-name">{{ fieldErrors.name }}</p>

    <label for="description">Description <span class="hint">(optional)</span></label>
    <textarea id="description" v-model="description" rows="3" maxlength="1000" :aria-invalid="!!fieldErrors.description" />
    <p v-if="fieldErrors.description" class="error" data-test="error-description">{{ fieldErrors.description }}</p>

    <label for="organizer">Your name</label>
    <input id="organizer" v-model="organizerName" maxlength="100" required :aria-invalid="!!fieldErrors.organizerName" />
    <p v-if="fieldErrors.organizerName" class="error" data-test="error-organizerName">{{ fieldErrors.organizerName }}</p>

    <label class="check">
      <input v-model="listed" type="checkbox" data-test="listed" />
      Show in the public list
    </label>
    <p class="hint">Leave this unchecked for a private exchange that people can only join with the link or code.</p>

    <button type="submit" :disabled="submitting" data-test="submit">
      {{ submitting ? 'Creating…' : 'Create exchange' }}
    </button>
  </form>
</template>

<style scoped>
form {
  display: grid;
  gap: 0.35rem;
  max-width: 34rem;
}
label {
  font-weight: 600;
  margin-top: 0.5rem;
}
.hint {
  color: var(--color-muted);
  font-weight: normal;
  font-size: 0.9rem;
  margin: 0;
}
.check {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.check input {
  width: auto;
}
button[type='submit'] {
  margin-top: 1rem;
  justify-self: start;
}
.error {
  margin: 0;
}
.share {
  display: flex;
  gap: 0.5rem;
}
.code {
  font-family: ui-monospace, monospace;
  font-size: 1.25rem;
  letter-spacing: 0.1em;
}
</style>
