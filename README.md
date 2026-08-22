# uptime-kuma-akka

Checks whether a website is up, decides when it has gone down, and tells people about it.

A port of [louislam/uptime-kuma](https://github.com/louislam/uptime-kuma) onto **Akka**,
built with **Akka Specify**.

---

## Where it came from

louislam/uptime-kuma is a self-hosted tool that watches websites and services and raises an
alarm when one stops answering. It was ported to derive a specification format precise
enough to regenerate a system on a different stack — the port is the vehicle, the
specification is the deliverable.

Three parts of it were rebuilt: deciding when the next check happens, working out what a
check result means given everything the monitor has already seen, and delivering the alarm
to every place a monitor is set to alert. The screens that show that state came with them,
and they are the original's own screens — only the place they get their data from changed.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `uptime-kuma-port/`.

---

## louislam/uptime-kuma → this port

📉 234 JavaScript lines → **461 Java lines**<br>
📁 6 files → **17 files**<br>
⚡ 88 → **24** nanoseconds to answer forty questions about a change in state<br>
🎯 53 beats compared, 106 answers → **106 the same**<br>
🧪 0 → **52** tests<br>
🖥️ 1 screen file changed, 1 added → **the other 296 unchanged**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/uptime-kuma-port/bench/REPORT.md).

---

## What it took to build

⏱️ **3.5 hours** from the first command to the published repository, **3.4** of them active<br>
💬 **990** exchanges with the model<br>
✍️ **821,297** tokens written by the model, **225,078,367** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **52** tests

```bash
python toolkit/tokens.py --port uptime-kuma    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **A check result is read against everything the monitor has already seen, never on its
  own.** A site that fails once is not down — it is waiting, and only stays waiting for as
  many failures as the monitor allows.
- **Being worth recording and being worth telling somebody about are two different
  questions.** Eleven of the twenty possible changes of state are recorded; eight of them
  raise an alarm, and going into a planned maintenance window is one of the three that are
  recorded and stay silent.
- **A monitor's very first check never raises an alarm unless it comes back down.** Starting
  to watch something that is already fine is not news.
- **A site that stays down is reported again every so many checks**, counted in checks
  rather than in minutes, and the count restarts the moment anything else changes.
- **A monitor waiting to see whether a failure is real checks again sooner.** Once it has
  been declared down it goes back to its ordinary spacing, even though it is still failing.
- **One alert that cannot be delivered costs only itself.** The ones after it in the list
  still go out, and the check result is unaffected.
- **A check that has already been recorded is never recorded twice**, so a repeated delivery
  cannot advance the count that decides when the next alert goes out.

---

## Design decisions

**Event sourcing.** The count of consecutive failures has to survive the program being
restarted, because a site that has been failing for an hour must not look freshly broken
when the watcher comes back. Every check result is written down as it happens and the count
is worked out by replaying them, so restarting changes nothing.

**Server-sent events.** A screen showing whether a site is up has to change the moment it
changes, and asking the server again on a timer means the news is as old as the timer. The
screen keeps one connection open and the server pushes each result down it as it happens.

**Resuming from a position.** A browser that loses its connection and reconnects would
otherwise miss whatever happened while it was away. Every result carries a number, the
browser sends back the last one it saw, and the server replays exactly the gap.

**A sequence number on every check.** Delivery of a scheduled task can happen more than
once, and recording the same check twice would move the count that decides when to alert
again. Each check says which position it believes it is writing, and a position already
filled is refused.

**Refusing an impossible spacing.** A monitor told to check every zero seconds has been
misconfigured, and quietly substituting a value gives it a rate nobody chose. It is turned
away at the door with the reason.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/uptime-kuma-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9060.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once
- Node 20 or newer, to build the screens

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9060**.

### Start the screens

```bash
cd gui
npm install
npm run build
npx vite preview --config ./config/vite.config.js --port 3002
```

The screens read from `http://localhost:9060` by default. Set `window.AKKA_BASE` before the
page loads to point them somewhere else.

### Try it

```bash
curl -X PUT http://localhost:9060/monitors/example \
  -H 'content-type: application/json' \
  -d '{"url":"https://example.com/","intervalSeconds":60,"retryIntervalSeconds":20,
       "maxRetries":2,"resendIntervalSeconds":0,"notificationIds":[]}'
curl -X POST http://localhost:9060/monitors/example/start
curl "http://localhost:9060/monitors/example/heartbeats?since=0"
```

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `AKKA_BASE` (browser global, not an environment variable) | `http://localhost:9060` | Where the screens look for the service |

Everything else about a monitor — what it checks, how often, how many failures it tolerates,
how often to repeat an alert, and where alerts go — is set per monitor when it is created,
not by configuration.

---

## Where it differs from louislam/uptime-kuma

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **A check that fails for an internal reason is tried again straight away, several times.**
  louislam/uptime-kuma catches the failure, writes it to the log, throws that check's result
  away and waits the monitor's ordinary spacing before trying again. This port lets the
  platform retry the failed check on a lengthening delay — about three seconds, then six —
  until it succeeds, because a thrown-away check is a hole in the history that nothing can
  see or report, and the whole record of what a site did assumes an unbroken chain. A site
  that is simply refusing connections is not an internal failure and never reaches this
  path; it is an answer, and it is recorded as one.
- **A monitor told to check every zero seconds is refused rather than given one second.**
  louislam/uptime-kuma reads a spacing of zero as one second, on a field its own form
  requires to be at least twenty. This port turns the monitor away with the reason, because
  a substituted spacing is a site being checked at a rate nobody chose.
- **The same check delivered twice is recorded once.** louislam/uptime-kuma cannot deliver a
  check twice — it schedules the next one only at the end of the previous one, inside a
  single program — so it has no rule here. This port's checks are scheduled by a platform
  that guarantees delivery at least once, so each check carries the position it believes it
  is writing and a position already filled is refused; the alternative would let one check
  advance the count that decides when to repeat an alert by two.
- **The screens are fed by a connection the server pushes down, rather than by the browser
  asking again.** louislam/uptime-kuma uses a two-way socket that its own server pushes on,
  which is the same idea; this port uses a one-way stream the browser reconnects by itself,
  and every result carries a number so a reconnecting browser can say where it got to. What
  a reader can see differs across a dropped connection: this port refills the exact gap,
  where the original's socket re-sends the recent history.
- **How long a site has been up is worked out in the browser, over the results it holds.**
  louislam/uptime-kuma works it out on the server across its whole stored history and
  reports it for the last day, the last month and the last year separately. This port
  reports one figure over the results the screen is holding, and shows it in all three
  places, because the stored history is not part of what was rebuilt.
- **There is no sign-in.** louislam/uptime-kuma requires an account and hides everything
  behind it. This port has no accounts at all and its service accepts requests from anyone
  who can reach it, because authentication was not part of what was rebuilt — which means it
  is safe on a workstation and not safe anywhere else.
- **A refused connection is described in different words.** louislam/uptime-kuma reports the
  operating system's own message; this port reports the name of the failure its check
  raised. Both appear on screen and in the alert.
- **A monitor is named, not numbered.** louislam/uptime-kuma gives each monitor a number
  from its database; this port uses the name the caller chose, which is what appears beside
  the monitor's title.
- **One check type and one way of alerting, out of about thirty and 106.** This port carries
  a plain web request and a web address to post an alert to.
- **Whether a maintenance window is open is told to this port rather than worked out by
  it.** louislam/uptime-kuma computes it from schedules attached to the monitor and to
  whatever the monitor belongs to. Everything that follows from being in a window behaves
  the same way.
- **How the two systems behave when the clock changes, when a monitor is reconfigured while
  a check is in flight, and when a monitor belongs to a group** — `not checked`. None of the
  three was driven on either side.

---

## Licence

louislam/uptime-kuma is MIT, © 2021 Louis Lam. This port is a derived work and ships a
large amount of that project's code unchanged — its entire web interface — so it is MIT
too; see [`ACKNOWLEDGEMENTS.md`](ACKNOWLEDGEMENTS.md).
