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

/**
 * Who the visitor says they are. Name only, no authentication (see story 08).
 * Everything else asks this store, so a future sign-in can replace it without touching pages.
 * The name-entry gate/modal is story 08; this is the minimum the API client and forms need.
 */
export const useParticipantStore = defineStore('participant', () => {
  const name = ref(read())

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

  return { name, setName, clear }
})
