import { hostHeaders } from '../lib/hostToken'
import { api } from './client'

/** One person in the queue. */
export interface PartyEntry {
  name: string
  /** DONE: had their turn and claimed. CURRENT: it is their turn. WAITING: still in line. */
  status: 'DONE' | 'CURRENT' | 'WAITING'
  online: boolean
}

/** The party as everyone sees it: names and order only, nothing about any book. */
export interface PartyState {
  active: boolean
  endedReason?: string
  current?: string
  /** Seconds left of the current turn, by the server's clock, at the moment the answer was sent. */
  turnSecondsLeft?: number
  queue: PartyEntry[]
  /** When no party is running: the people online who could be queued. */
  eligible: string[]
}

export const getParty = (code: string) => api<PartyState>(`/api/exchanges/${encodeURIComponent(code)}/party`)

const hostCall = (code: string, action: 'start' | 'skip' | 'end') =>
  api<PartyState>(`/api/exchanges/${encodeURIComponent(code)}/party/${action}`, {
    method: 'POST',
    headers: hostHeaders(code),
  })

export const startParty = (code: string) => hostCall(code, 'start')
export const skipTurn = (code: string) => hostCall(code, 'skip')
export const endParty = (code: string) => hostCall(code, 'end')

/** Tolerates an answer that isn't a party (a mock, a proxy page): treated as "no party". */
export function asParty(value: unknown): PartyState | null {
  const p = value as PartyState | null
  return p && typeof p === 'object' && typeof p.active === 'boolean' && Array.isArray(p.queue) ? p : null
}
