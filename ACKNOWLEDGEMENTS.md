# Acknowledgements

This project is a rebuild of part of
**[louislam/uptime-kuma](https://github.com/louislam/uptime-kuma)** on Akka, and it ships a
substantial amount of that project's code unchanged. Everything below was established by
running `python toolkit/copied_strings.py uptime-kuma` over the rebuild against a clone of
the original and answering what it found, rather than from memory.

## The licence, and who holds the copyright

uptime-kuma is **MIT**, `Copyright (c) 2021 Louis Lam`, read from its own `LICENSE` file at
the tip of `master` on 2026-08-22. This project is MIT for that reason and carries the same
copyright line alongside its own in `LICENSE`. Copied material carries its licence with it;
the licence here is not a choice, it is a consequence.

## What was copied verbatim, and why

### The whole web interface — `gui/`

Three directories are declared as wholesale copies below rather than string by string. A
declaration covers a string only when **every** file it occurs in is inside a declared
directory, so anything this project wrote still has to be named on its own.

Verbatim-copy: gui/src
Verbatim-copy: gui/config
Verbatim-copy: gui/public

**`gui/` is uptime-kuma's `src/`, `config/` and `public/` directories and its
`index.html`, copied file for file.** `diff -rq` reports the three directories identical
but for the two files named in the table below, and `index.html` identical outright. Every Vue component,
Every Vue component, page, layout, mixin, style sheet, icon, router table, all eighty
translation files, the Vite and Playwright configuration and the service worker are Louis
Lam's and the uptime-kuma contributors', unmodified. That is deliberate and is the
point: this project replaces where the interface gets its data and nothing else, so that a
comparison of the two screens has a subject. The rules are in `RENDERING.md` R3 and R4 in
the harness repository, and the reasoning is in `specs/RENDER-001-uptime-kuma.md`.

The complete list of what differs is two files:

| File | What changed |
|---|---|
| `gui/src/mixins/socket.js` | the socket.io wiring inside `initSocketIO` is replaced by a subscription to this project's event stream, and `getSocket()` answers two queries from the beats already held. Everything else in the file is the original's |
| `gui/src/mixins/akka-feed.js` | new, written for this project |

`diff -rq uptime-kuma/src uptime-kuma-akka/gui/src` names those two and nothing else. The
286 shared strings `copied_strings.py` reports across `index.js`, `router.js`, `util.js`,
`main.js`, `i18n.js` and the rest are all inside this copy and are all accounted for by it.

### Seven strings on the Java side

| String | Where | Why it is the same |
|---|---|---|
| `Monitor under maintenance` | `domain/BeatDecision.java` | **A deliberate reproduction of the original's own message** (`server/model/monitor.js:471`), so that a heartbeat carried across from uptime-kuma reads identically. This is the one Java string copied on purpose |
| `MAINTENANCE` | four test files, `domain/Status.java` | A status name. The four statuses are the vocabulary of the thing being rebuilt; a port that renamed them would be describing a different system |
| `Content-Type` | `application/NotificationFanOut.java` | An HTTP header, from the protocol rather than from uptime-kuma |
| `already running`, `under maintenance` | `application/MonitorEntity.java` | Ordinary English, written here and coinciding. Both are this project's own reply strings; neither has a counterpart in the original's replies |
| `interrupted` | `application/HttpProbe.java` | Ordinary English, written here |
| `notification` | `application/NotificationEntity.java` | A component id, written here |

No prompt, fixture, schema or test corpus was copied.

## Behaviour, where no text was copied

**Derived throughout, and not coyly.** The entire behavioural contract in
`specs/SPEC-001-uptime-kuma.md` — nineteen rules covering the retry ladder, the two
transition predicates over all twenty status transitions, the first-beat gate, the re-send
counter and the delay the loop asks for — is uptime-kuma's behaviour, established by
running uptime-kuma and written down. Four decisions where the original had no settled
answer are marked as this project's own in that document's *Open decisions* section and in
the README's list of differences.

## Also used

- **Akka** — the Akka Java SDK and runtime, on which this is built.
- **Playwright**, **Vite** and **Vue** — through uptime-kuma's own dependency set, used to
  build and photograph the interface. Their licences are their own.
