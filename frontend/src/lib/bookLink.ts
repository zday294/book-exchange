/**
 * Mirrors the backend's @BookUrl rule so people get an instant, friendly message. The backend
 * is still the authority (it reads its list from BOOK_LINK_ALLOWED_HOSTS); if that list is ever
 * changed, change this one too.
 */
const RETAILERS = [
  { name: 'Amazon', hosts: ['amazon.com', 'amzn.to', 'a.co'] },
  { name: 'Barnes & Noble', hosts: ['barnesandnoble.com', 'bn.com'] },
] as const

export const ALLOWED_BOOK_HOSTS: readonly string[] = RETAILERS.flatMap((r) => r.hosts)

const matches = (host: string, allowed: string) => host === allowed || host.endsWith('.' + allowed)

/** The link's host, lower-cased, or undefined if it isn't a usable web address. */
function hostOf(value: string): string | undefined {
  try {
    return new URL(value.trim()).hostname.toLowerCase()
  } catch {
    return undefined
  }
}

/** True for an https link on Amazon or Barnes & Noble (or a subdomain such as www.amazon.com): no credentials, default port. */
export function isBookUrl(value: string): boolean {
  const text = value.trim()
  if (!text || /\s/.test(text)) return false
  let url: URL
  try {
    url = new URL(text)
  } catch {
    return false
  }
  if (url.protocol !== 'https:') return false
  if (url.username !== '' || url.password !== '') return false
  if (url.port !== '' && url.port !== '443') return false
  const host = url.hostname.toLowerCase()
  return ALLOWED_BOOK_HOSTS.some((allowed) => matches(host, allowed))
}

/** Which store a link goes to ("Amazon", "Barnes & Noble"), worked out from its host; undefined for anything else. */
export function retailerName(value: string): string | undefined {
  const host = hostOf(value)
  if (!host) return undefined
  return RETAILERS.find((r) => r.hosts.some((allowed) => matches(host, allowed)))?.name
}

/** The words on the button that opens the book's page: "View on Barnes & Noble", or "View the book" if the store is unknown. */
export function viewLinkLabel(value: string): string {
  const store = retailerName(value)
  return store ? `View on ${store}` : 'View the book'
}
