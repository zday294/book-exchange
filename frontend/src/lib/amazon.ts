/**
 * Mirrors the backend's @AmazonUrl rule so people get an instant, friendly message. The backend
 * is still the authority (it reads its list from AMAZON_ALLOWED_HOSTS); if that list is ever
 * changed, change this one too.
 */
export const ALLOWED_AMAZON_HOSTS = ['amazon.com', 'amzn.to', 'a.co'] as const

/** True for an https link on Amazon (or a subdomain such as www.amazon.com): no credentials, default port. */
export function isAmazonUrl(value: string): boolean {
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
  return ALLOWED_AMAZON_HOSTS.some((allowed) => host === allowed || host.endsWith('.' + allowed))
}
