# 16 – Unwrapping animation  *(upgrade — can wait)*

## Story
As a participant, I want presents to visibly unwrap when claimed so that the reveal is fun, both for the claimer and for everyone watching.

## Details
- Plays when a present transitions from unclaimed → claimed, whether triggered by my own claim (story 11) or a live event from someone else (story 15).
- Suggested sequence (~1 second): bow pops off, paper peels/fades away, cover and title fade in on the box, claimer's name appears on the tag.
- Implement with CSS transitions/keyframes or Vue's `<Transition>` on the SVG present (no animation library required). Optional: a light confetti/sparkle burst.
- Must respect `prefers-reduced-motion` by swapping to an instant state change.
- On initial page load, already-claimed presents render in their final state with **no** animation.

## Acceptance criteria
- [ ] Claiming shows the animation, then the final claimed state.
- [ ] Watching another browser claim shows the same animation.
- [ ] Loading/refreshing the page does not replay animations.
- [ ] Reduced-motion users see no animation.
