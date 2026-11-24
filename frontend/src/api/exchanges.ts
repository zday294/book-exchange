import { api } from './client'

export interface Exchange {
  code: string
  name: string
  description?: string
  organizerName?: string
  listed: boolean
  status: 'OPEN' | 'CLOSED'
  bookCount: number
  claimedCount: number
  createdAt: string
}

export const getExchange = (code: string) => api<Exchange>(`/api/exchanges/${encodeURIComponent(code)}`)

export interface CreateExchangeRequest {
  name: string
  description?: string
  organizerName: string
  listed: boolean
}

export const createExchange = (request: CreateExchangeRequest) =>
  api<Exchange>('/api/exchanges', { method: 'POST', body: request })
