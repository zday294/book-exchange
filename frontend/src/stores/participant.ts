import { defineStore } from 'pinia'
import { ref } from 'vue'

export const PARTICIPANT_STORAGE_KEY = 'bookExchange.participantName'

function read(): string {
  try {
    return localStorage.getItem(PARTICIPANT_STORAGE_KEY) ?? ''
  } catch {
    return '' // storage blocked (private mode etc.): behave as "no name saved"
  }
}

export const MAX_NAME_LENGTH = 100

/** Returns an error message for an unusable name, or '' when it is fine. Names are trimmed first. */
export function validateName(value: string): string {
  const name = value.trim()
  if (name.length === 0) return 'Please enter your name.'
  if (name.length > MAX_NAME_LENGTH) return `Names can be at most ${MAX_NAME_LENGTH} characters.`
  return ''
}

/**
 * Who the visitor says they are. Name only, no authentication (see story 08).
 * Everything else asks this store, so a future sign-in can replace it without touching pages.
 * The name is saved exactly as typed (after trimming); the backend compares names
 * case-insensitively, so "alex" and "Alex" are the same person.
 */
export const useParticipantStore = defineStore('participant', () => {
  const name = ref(read())
  /** True while the "change your name" dialog is open (the first-visit gate doesn't use this). */
  const dialogOpen = ref(false)

  function setName(value: string) {
    name.value = value.trim()
    try {
      if (name.value) localStorage.setItem(PARTICIPANT_STORAGE_KEY, name.value)
      else localStorage.removeItem(PARTICIPANT_STORAGE_KEY)
    } catch {
      /* keep working in memory */
    }
  }

  function clear() {
    setName('')
  }

  const openDialog = () => (dialogOpen.value = true)
  const closeDialog = () => (dialogOpen.value = false)

  return { name, dialogOpen, setName, clear, openDialog, closeDialog }
})
