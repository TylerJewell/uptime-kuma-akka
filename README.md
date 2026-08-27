# uptime-kuma-akka

Watches a set of services, decides when one has gone down, and tells people about it.

A port of [louislam/uptime-kuma](https://github.com/louislam/uptime-kuma) onto **Akka**,
built with **Akka Specify**.

---

## Where it came from

louislam/uptime-kuma is a self-hosted tool that watches websites and services and raises an
alarm when one stops answering. It was ported to derive a specification format precise
enough to regenerate a system on a different stack — the port is the vehicle, the
specification is the deliverable.

This is a rebuild of the whole of it, not a slice: thirty-three kinds of check, a hundred
and five places an alert can be sent, six kinds of planned-outage schedule, the statistics,
the badges, the public status pages, the scrapeable figures, the accounts and second
factors and keys, the eighty-five calls the screens make, and a command line. The screens
themselves came across unchanged — they are the original's own, and only the place they get
their data from was replaced.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `uptime-kuma-port/`.

---

## louislam/uptime-kuma → this port

📉 20,770 JavaScript lines → **19,192 Java lines**<br>
📁 206 files → **105 files**<br>
🎯 133 calls, 2,769 answers compared → **2,769 the same, 9 differences declared**<br>
🌐 35 web addresses compared → **35 the same, 4 differences declared**<br>
⚡ 100.1 → **20.4** nanoseconds to answer forty questions about a change of state<br>
🖼️ 10 screens compared against the original → **10 the same**<br>
🧪 52 → **455** tests<br>
📄 1 screen file changed, 1 added → **the other 192 unchanged**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/uptime-kuma-port/bench/REPORT.md).

---

## What it took to build

⏱️ **125.0 hours** from the first command to the published repository, **13.4** of them active<br>
💬 **3,101** exchanges with the model<br>
✍️ **2,722,990** tokens written by the model, **1,174,961,755** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **455** tests

```bash
python toolkit/tokens.py --port uptime-kuma    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

You give it a list of things to watch. It checks each one on its own timetable, works out
from that check and everything that monitor has already seen whether the thing is up, down,
waiting, or inside a planned outage, and tells whoever the monitor is set to tell.

Thirty-three kinds of check: fetching a web address and reading the answer, looking for a
word in the reply, running a query over the reply, sending a ping, waiting to be pushed to,
opening a plain connection to a port, greeting a mail server, asking a time server, asking a
name server, asking a message broker, asking a container host, asking six kinds of database,
asking a game server, asking somebody else's network of probes, driving a real browser, and
more.

A hundred and five places to send an alert, each addressed the way its own service expects.

From the specification, the rules a reader is most likely to be surprised by:

- **A check result is read against everything the monitor has already seen, never on its
  own.** A site that fails once is not down — it is waiting, and only stays waiting for as
  many failures as the monitor allows.
- **Being worth recording and being worth telling somebody about are two different
  questions.** Seven changes of state are recorded; four of them raise an alarm, and going
  into a planned outage is one of the three that are recorded and stay silent.
- **A monitor's very first check never raises an alarm unless it comes back down.** Starting
  to watch something that is already fine is not news.
- **A site that stays down is reported again every so many checks**, counted in checks
  rather than in minutes, and the count restarts the moment anything else changes.
- **A monitor waiting to see whether a failure is real checks again sooner.** Once it has
  been declared down it goes back to its ordinary spacing, even though it is still failing.
- **A monitor that waits to be pushed to takes no reading at all until its first interval
  has passed.** Every other kind reads the moment it starts.
- **A badge says nothing about a monitor nobody has published.** A badge address takes no
  password, so it answers only for a monitor somebody has put on a public status page.
- **A password of four ordinary words is refused.** What counts is how many kinds of
  character it holds, not how long it is, and a space is not one of the kinds.
- **A tolerated spacing of zero means no waiting state at all**, not one retry, and a
  re-alert spacing of zero switches re-alerting off.

---

## Design decisions

**Event sourcing.** The count of consecutive failures has to survive the program being
restarted, because a site that has been failing for an hour must not look freshly broken
when the watcher comes back. Every check result is written down as it happens and the count
is worked out by replaying them, so restarting changes nothing.

**A separate list to read from.** Every list the screens draw — monitors, tags, alert
targets, planned outages, status pages, keys — is kept apart from the thing that writes it,
so a screen listing two hundred monitors does not wake two hundred of them. The cost is that
a list takes a moment to show something just added; that moment is measured in
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/uptime-kuma-port/bench/REPORT.md)
and listed below.

**Server-sent events.** A screen showing whether a site is up has to change the moment it
changes, and asking the server again on a timer means the news is as old as the timer. The
screen keeps one connection open and the server pushes each result down it as it happens.

**Resuming from a position.** A browser that loses its connection and reconnects would
otherwise miss whatever happened while it was away. Every result carries a number, the
browser sends back the last one it saw, and the server replays exactly the gap.

**A number on every check that never restarts.** Delivery of a scheduled task can happen
more than once, and recording the same check twice would move the count that decides when to
alert again. Each check says which position it believes it is writing, a position already
filled is refused, and the numbering keeps climbing even when a monitor's history is thrown
away — because each result is stored under that number, and a number handed out twice would
land on a record that has been deleted.

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

**3. Open** http://localhost:9158.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start it

```bash
mvn compile exec:java
```

The service starts on **port 9158** and serves the screens itself — the built form of the
interface is inside the project, so there is nothing else to start.

### Try it

```bash
# create the first account
curl -X POST http://localhost:9158/socket/setup \
  -H 'content-type: application/json' \
  -d '{"args":["admin","Tr0ub4dor&3!"]}'

# sign in, and keep the token it answers with
curl -X POST http://localhost:9158/socket/login \
  -H 'content-type: application/json' \
  -d '{"args":[{"username":"admin","password":"Tr0ub4dor&3!","token":""}]}'

# watch something
curl -X POST http://localhost:9158/socket/add \
  -H 'content-type: application/json' \
  -d '{"token":"<the token>","args":[{"type":"http","name":"example",
       "url":"https://example.com/","interval":60,"retryInterval":60,"maxretries":0,
       "method":"GET","accepted_statuscodes":["200-299"],"active":true}]}'
```

Or open http://localhost:9158 and use the screens.

### The command line

```bash
curl -X POST http://localhost:9158/cli/reset-password \
  -H 'content-type: application/json' -d '{"password":"Zx9#quiet-Harbour"}'
curl -X POST http://localhost:9158/cli/remove-2fa \
  -H 'content-type: application/json' -d '{"username":"admin"}'
```

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `akka.javasdk.dev-mode.http-port` | `9158` | In `src/main/resources/application.conf` |

Everything else — what is watched, how often, how many failures it tolerates, how often to
repeat an alert, where alerts go, who may sign in, what the public pages show — is set
through the screens or the calls behind them, not by configuration.

---

## Where it differs from louislam/uptime-kuma

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

**Timing, not answers**

- **A list takes about three seconds to show something just added, where the original takes
  one millisecond.** The original keeps one database and reads the list straight out of it.
  This port keeps the lists apart from the things that write them, so a list is caught up
  shortly after rather than immediately. Measured over eight writes on each side: 1
  millisecond every time on the original, 2,894 to 3,153 milliseconds here. What is added is
  stored immediately and every direct read of it is immediate; it is the *lists* that lag.

**Identifiers**

- **A status page is identified by its short name rather than by a number.** The original
  numbers its pages; this port uses the short name that already appears in every public
  address for the page. Anything that reports a page's identifier reports the short name.
- **A check result's own row number counts that monitor's results, where the original counts
  every result on the server.** Nothing on screen reads it.

**Refusals and messages**

- **Asked for a monitor that is not there, this port says "Monitor not found".** The original
  reports the failure its own code hit while looking — a message about reading a property of
  nothing. It has no considered answer here; this one is this port's.
- **Asked to make a status page whose short name is already taken, this port says "Invalid
  Slug".** The original reports its database's own complaint, with the whole insert statement
  in it.
- **A remote browser that is not there is reported in one line.** The original reports the
  whole of its browser library's connection log.
- **A window of zero hours of history returns nothing here and everything on the original.**
  The original's query compares a stored instant against the current time in one timezone
  while storing that instant in another, so on a machine behind UTC a zero-hour window still
  matches everything. Reproducing that would mean reproducing an offset that is a property of
  the machine.

**Answering at all**

- **Four calls that the original leaves unanswered are answered here.** Asking whether setup
  is needed before the connection has finished opening; editing a group to be its own parent;
  pausing a group; and any call made after a password change that dropped the connection. In
  each of those the original never calls back, which a screen waiting on it cannot tell from
  a slow answer.
- **A check that fails for an internal reason is tried again straight away, several times.**
  louislam/uptime-kuma catches the failure, writes it to the log, throws that check's result
  away and waits the monitor's ordinary spacing before trying again. This port lets the
  platform retry the failed check on a lengthening delay — about three seconds, then six —
  until it succeeds, because a thrown-away check is a hole in the history that nothing can
  see or report. A site that is simply refusing connections is not an internal failure and
  never reaches this path.
- **The same check delivered twice is recorded once.** louislam/uptime-kuma cannot deliver a
  check twice — it schedules the next one only at the end of the previous one, inside a
  single program — so it has no rule here. This port's checks are scheduled by a platform
  that guarantees delivery at least once, so each check carries the position it believes it
  is writing and a position already filled is refused.

**What the screens are fed by**

- **The screens are fed by a connection the server pushes down, rather than by a two-way
  socket.** louislam/uptime-kuma uses a socket its own server pushes on, which is the same
  idea; this port uses a one-way stream the browser reconnects by itself. What a reader can
  see differs across a dropped connection: this port refills the exact gap from the number
  the browser last saw, where the original's socket re-sends the recent history.

**Figures**

- **The scrapeable figures carry the same six readings per monitor and about ninety fewer
  besides.** The original's scrape also describes the program it runs in — its memory, its
  garbage collector, its web server's request sizes, the version of its own package file —
  and those describe a runtime this port does not have.
- **Refused a scrape without a password, this port sends no page with the refusal.** The
  original sends its web framework's own challenge page.

**Not delivered**

- **Web push notifications and nostr are not delivered.** Both are registered by name and
  both say so when asked to send, rather than appearing to work. The signing key a browser
  needs to subscribe for web push *is* made and served, so the screens can offer it.
- **The tunnel process is not started.** louislam/uptime-kuma can start and supervise a
  second, separate program beside itself; the calls for it answer plainly rather than
  appearing to work.
- **No database server is started.** louislam/uptime-kuma can start one inside its own
  container; this port has no database of its own to start.
- **A 1.x database file is not read.** There is no file here to read. A password stored in
  the older form is still accepted, which is the part of that migration a person notices.

**Not checked**

- **Twelve kinds of check were never run against the original**, because each needs somebody
  else's service to answer — a message broker, six kinds of database, a container host, a
  game server, a name server, a mail server, a time server, a browser, or a network of probes
  somebody else runs. Each is checked here against a server this project starts itself, and
  what it does was derived from the original's code rather than from watching it. They are
  named one by one in
  [`bench/rules-not-compared.json`](https://github.com/TylerJewell/akka-specify-harness/blob/main/uptime-kuma-port/bench/rules-not-compared.json).
- **Sending a ping was never run on either side** — it needs a privilege this machine does
  not grant.
- **Certificate and domain expiry were never compared**, because doing so needs a certificate
  about to expire and a registered domain, and neither system can be asked to pretend.
- **The second factor was never compared**, because its answer is a function of the clock and
  comparing it would compare two readings of the same clock.
- **How the two systems behave when the clock changes, and when a monitor is reconfigured
  while a check is in flight** — `not checked`. Neither was driven on either side.

---

## Licence

louislam/uptime-kuma is MIT, © 2021 Louis Lam. This port is a derived work and ships a
large amount of that project's code unchanged — its entire web interface — so it is MIT
too; see [`ACKNOWLEDGEMENTS.md`](ACKNOWLEDGEMENTS.md).
