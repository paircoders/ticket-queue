/**
 * Navigation utility for client-side redirects.
 * Extracted to a separate module for testability (jsdom does not support navigation).
 */
export function redirectTo(path: string): void {
  window.location.href = path
}
