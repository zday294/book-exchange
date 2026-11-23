# 15 – Live updates on the exchange page

## Story
As a participant waiting for my turn, I want to see presents get claimed (and new ones appear) on my screen automatically, without refreshing, so I can follow the exchange as it happens.

## What this means technically (plain-language)
The browser normally only asks the server for data when you load the page; the server can't tell it "something changed". There are three ways to get automatic updates:

| Option | How it works | Feel | Effort |
|---|---|---|---|
| **Polling** | The page asks "anything new?" every N seconds | Delay of up to N seconds; simplest | Lowest |
| **Server-Sent Events (SSE)** | Page opens one long-lived connection; the server pushes events down it as they happen | Near-instant; one-way (server → browser), which is all we need | Low–medium; built into Spring (`SseEmitter`) and browsers (`EventSource`) |
| **WebSockets** | Two-way persistent connection | Near-instant | Highest; more than we need |

**Recommendation: SSE.** It gives the instant "a present just got unwrapped" behavior you described, without WebSocket complexity. Polling remains the fallback if the connection drops.

## Backend
- `GET /api/exchanges/{exchangeCode}/events` (`text/event-stream`) streams events for that exchange only.
- Events:
  - `book-added` – public wrapped view (id, submitter, paper).
  - `book-claimed` – id, `claimedByName`, `bookTitle`, `coverImageUrl`. **Never contains the Amazon link**; that stays in the claimer's own response (stories 04/05).
- Events are published after the corresponding DB transaction commits.
- Send a periodic heartbeat comment so proxies don't close idle connections.

## Front end
- Page opens an `EventSource` on load and closes it when leaving the page.
- On `book-claimed`/`book-added`, update the pile in place (this is where the unwrap animation from story 16 plugs in).
- If the stream disconnects, the browser auto-reconnects; on reconnect, re-fetch the full book list to catch anything missed.
- If the viewer is the claimer of an event, they already have their own link from the claim response; no special handling.

## Acceptance criteria
- [ ] With two browsers open on the same exchange, claiming in one updates the other within ~1 second without refresh.
- [ ] Newly submitted books appear in other open browsers.
- [ ] Events for exchange A never reach viewers of exchange B.
- [ ] No event payload ever contains an Amazon link or details of an unclaimed book.
- [ ] After a dropped connection, the page re-syncs.
- [ ] Documented limitation: SSE connections are held per viewer, fine for a group-sized exchange.
