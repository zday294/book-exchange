import { hostHeaders } from '../lib/hostToken'
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
  /** True only when the host token saved in this browser is valid for this exchange (the server decides). */
  isHost: boolean
  /** Only in the answer to creating an exchange, and never again. */
  hostToken?: string
}

/** Sends the saved host token (if any) so the answer can say whether this browser is the host. */
export const getExchange = (code: string) =>
  api<Exchange>(`/api/exchanges/${encodeURIComponent(code)}`, { headers: hostHeaders(code) })

/** The settings a host can change; leave a field out to keep it, send '' as description to remove it. */
export interface UpdateExchangeRequest {
  name?: string
  description?: string
  organizerName?: string
  listed?: boolean
}

export const updateExchange = (code: string, changes: UpdateExchangeRequest) =>
  api<Exchange>(`/api/exchanges/${encodeURIComponent(code)}`, {
    method: 'PATCH',
    body: changes,
    headers: hostHeaders(code),
  })

export interface CreateExchangeRequest {
  name: string
  description?: string
  organizerName: string
  listed: boolean
}

export const createExchange = (request: CreateExchangeRequest) =>
  api<Exchange>('/api/exchanges', { method: 'POST', body: request })

/** One exchange in the public list: counts only, nothing about any book. */
export interface ExchangeSummary {
  code: string
  name: string
  description?: string
  status: 'OPEN' | 'CLOSED'
  bookCount: number
  claimedCount: number
  createdAt: string
}

export interface ExchangeList {
  items: ExchangeSummary[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface ListOptions {
  /** Case-insensitive "name contains" search; blank means no search. */
  query?: string
  /** Only this status; omit for both (open ones are listed first). */
  status?: 'OPEN' | 'CLOSED'
  page?: number
  size?: number
}

export function listExchanges(options: ListOptions = {}) {
  const params = new URLSearchParams()
  const query = options.query?.trim()
  if (query) params.set('query', query)
  if (options.status) params.set('status', options.status)
  params.set('page', String(options.page ?? 0))
  params.set('size', String(options.size ?? 12))
  return api<ExchangeList>(`/api/exchanges?${params.toString()}`)
}
