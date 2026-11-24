import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { PARTICIPANT_STORAGE_KEY, useParticipantStore } from '../stores/participant'

describe('participant store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('starts empty, saves a trimmed name, and survives a reload', () => {
    expect(useParticipantStore().name).toBe('')
    useParticipantStore().setName('  Alex  ')
    expect(useParticipantStore().name).toBe('Alex')
    expect(localStorage.getItem(PARTICIPANT_STORAGE_KEY)).toBe('Alex')

    setActivePinia(createPinia()) // simulates a page reload
    expect(useParticipantStore().name).toBe('Alex')
  })

  it('clears the saved name', () => {
    useParticipantStore().setName('Alex')
    useParticipantStore().clear()
    expect(useParticipantStore().name).toBe('')
    expect(localStorage.getItem(PARTICIPANT_STORAGE_KEY)).toBeNull()
  })
})
it('deliberately fails', () => { expect(1).toBe(2) })
