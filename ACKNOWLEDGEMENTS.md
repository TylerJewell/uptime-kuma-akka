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

### The strings on the Java side

Every one of these was found by `copied_strings.py` and answered here. They divide into three
kinds, and the first is much the largest.

**Messages reproduced on purpose, because a person reads them.** A refusal, a heartbeat's message
and a badge's label all reach somebody through the interface, and the interface is the original's
own — a rebuild that reworded them would show a screen the original never shows. Each of these is
the original's exact string, taken from the file named beside it and checked by running both
systems side by side:

| String | Where in this project | Where in uptime-kuma |
|---|---|---|
| `Monitor under maintenance` | `domain/BeatDecision.java` | `server/model/monitor.js` |
| `Interval cannot be less than `, `Retry interval cannot be less than ` | `domain/MonitorConfig.java` | `server/model/monitor.js` `validate` |
| `Response max length cannot be less than 0`, `Response max length cannot be more than ` | `domain/MonitorConfig.java` | the same |
| `Packet size must be between `, `Per-ping timeout must be between `, `Echo requests count must be between `, `Timeout must be between `, ` (default: `, ` seconds (default: ` | `domain/MonitorConfig.java` | the same |
| `Screenshot delay must be a non-negative number`, `Screenshot delay must be less than ` | `domain/MonitorConfig.java` | the same |
| `Invalid service name. Please use the internal Service Name (no spaces).`, `Invalid PM2 process name.`, `PM2 process name is required.`, `Service Name is required.` | `domain/MonitorConfig.java` | the same |
| `Invalid start date`, `Invalid end date` | `api/SocketHandlers.java` | `server/model/maintenance.js` |
| `CronPattern: invalid configuration format`, `CronPattern: Invalid value for `, ` contains illegal characters.` | `domain/MaintenanceWindow.java` | `croner`, the library uptime-kuma hands its patterns to |
| `connect ECONNREFUSED `, `connect ETIMEDOUT `, `connect EHOSTUNREACH `, `getaddrinfo ENOTFOUND `, `self-signed certificate`, `self-signed certificate in certificate chain`, `unable to verify the first certificate`, `certificate has expired`, `Hostname/IP does not match certificate's altnames` | `checks/TransportErrors.java` | Node's own network and TLS layers, which uptime-kuma puts into a heartbeat unchanged |
| `Only allowed PNG logo.`, `Invalid analytics type`, `Invalid Slug` | `api/SocketHandlers.java` | `server/socket-handlers/status-page-socket-handler.js` |
| `No/Bad Cert`, `Bad Cert`, `N/A` | `api/ApiEndpoint.java` | `server/routers/api-router.js` |
| `Unknown Monitor Type`, `No heartbeat in the time window`, `Group empty`, `Manual monitoring - No status set` | `checks/`, `domain/` | `server/model/monitor.js` |
| every `successAdded`, `successDeleted`, `successPaused`, `successResumed`, `passwordTooWeak`, `authIncorrectCreds` and the rest | `api/SocketHandlers.java` | the original's own reply keys, which its interface looks up in its translation files |

**Field names, because the interface reads them one by one.** `httpBodyEncoding`,
`kafkaProducerBrokers`, `kafkaProducerSaslOptions`, `rabbitmqNodes`, `manual_status`,
`snmp_v3_username`, `down_count`, `publicSuffix`, `webpushPublicVapidKey`,
`webpushPrivateVapidKey` and the other hundred and twenty names a monitor, a window, a page or a
heartbeat carries are uptime-kuma's names for its own columns. The interface is shipped unchanged
and reads each of them by name, so these are not a choice.

**Ordinary English and protocol vocabulary, written here and coinciding.** `MAINTENANCE` and the
three other status names; `Content-Type` and `IP Address:`, which are the HTTP and X.509
vocabularies; `already running`, `under maintenance`, `interrupted`, `notification`,
`clear-old-data`, `unreachable`; `abcdefghijkl`, which is a password in a test; and `Invalid Date`,
which is what a browser prints for a date it cannot read and appears here only in a test's comment
about a screen that used to show it.

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
