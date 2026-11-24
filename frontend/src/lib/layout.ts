/**
 * A loose, hand-placed look for the pile that doesn't jump around: each present's tilt and
 * nudge come only from its id, so the same book is always placed the same way on every refresh.
 */
export interface PresentLayout {
  rotateDeg: number
  offsetY: number
}

export function presentLayout(id: number): PresentLayout {
  // Small integer hashes, spread to roughly -3..3 degrees and 0..14 px.
  const rotateDeg = ((((id * 37) % 13) - 6) * 3) / 6
  const offsetY = (id * 53) % 15
  return { rotateDeg, offsetY }
}
