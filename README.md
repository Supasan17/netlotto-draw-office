# NetLotto Draw Office — Real-Time Networked Lottery System

EC5205 Operating Systems & Network Programming, University of Ruhuna.

A client–server lottery system written in core Java 17: Swing desks (clients) connect to one draw
server over TCP, find it automatically through a UDP beacon, and receive the draw clock, sales,
results and prizes in real time. Accounts, tickets, draws and a full credit/debit ledger are kept in an
embedded H2 database. The draw office runs its own server window showing everything that happens,
including a read-only view of every database table. Amounts are in points, which are for
demonstration only and have no cash value.

## Build and run

Requires JDK 17 or newer. From PowerShell in the project folder:

```powershell
.\scripts\build.ps1
```

The script compiles the sources with `--release 17 -Xlint:all`, creates the runnable JARs in `dist\`,
copies the H2 driver to `dist\lib\` and runs all five test suites. Use `-SkipTests` to build only.
It uses `JAVA_HOME` if set, otherwise the `javac` on PATH.

Ready-built JARs are already in `dist\`. They can be started from PowerShell or by double-clicking:

```powershell
# Draw server with its window: TCP port 6060, a draw every 5 minutes (both optional)
java -jar dist/drawoffice-server.jar 6060 300

# Draw server without a window (text console only)
java -jar dist/drawoffice-server.jar --console 6060 300

# Desk (client): run one per computer, or several on one computer
java -jar dist/drawoffice-desk.jar

# Desk connecting to a known address instead of using discovery
java -jar dist/drawoffice-desk.jar 192.168.1.20 6060

# Print the database as text (works while the server is running)
java -jar dist/drawoffice-server.jar --report
```

The database is always `data\drawoffice.mv.db` in the project folder, however the server is
started. On a LAN, allow Java through the Windows firewall on the server computer (TCP 6060, UDP 6061).

## Server window

The server opens a window for the draw office (the console commands below keep working as well):

| Page | Shows |
| --- | --- |
| Activity | every sign-in, purchase, draw, notice and disconnect as it happens |
| Connected desks | each connected desk, its account and when it connected; disconnect a selected desk |
| Current draw | every ticket sold for the open draw, with its owner and numbers |
| Accounts | every account with balance, tickets bought and prizes won |
| Draw history | winning numbers, tickets sold and winners of past draws |
| Database | read-only view of the accounts, tickets, statement (ledger), draws and system tables, plus a check that every balance equals its statement total |

Above every page: the current draw, countdown, tickets sold, jackpot, desks online and accounts,
with **Draw now**, **Pause sales**, **Resume sales** and **Send notice**. The sidebar lists the IP
addresses desks can use to reach the server.

Optional: `.\scripts\package-windows.ps1` builds a desk with its own Java runtime for computers
without Java (`packages\DrawOffice-desk-windows.zip`).

## Using the desk

1. The sign-in screen lists every server heard on the network. Pick one, or type an address.
2. **Create account** opens an account with 500 points. Usernames are 3–20 characters starting with
   a letter; passwords are 8–64 characters with at least one letter and one digit.
3. **Overview** shows the current draw, countdown, jackpot, tickets sold, desks online, the latest
   result, recent tickets and a live activity feed.
4. **Buy tickets**: choose 6 numbers from 1–40 and buy (20 points), or buy 1–100 quick pick tickets
   with random unsold combinations.
5. **My tickets**, **Statement** and **Draw results** show the stored history.

## Draw rules

| Setting | Value |
| --- | --- |
| Numbers per ticket | 6 different numbers from 1 to 40 |
| Ticket price | 20 points (10 of which go to the jackpot) |
| Opening balance | 500 points |
| Draw cycle | every 5 minutes (300 s) by default; sales close 5 s before the draw |
| Prize by numbers matched | 1 = 20, 2 = 50, 3 = 100, 4 = 1,000, 5 = 5,000 points: each extra match pays more |
| 6 matches | share the jackpot (starts at 50,000 points, grows by 10 per ticket, carried forward when not won) |
| Uniqueness | a combination can be sold only once per draw |

## Operator console commands

| Command | Effect |
| --- | --- |
| `status` | current draw, phase, seconds left, tickets and jackpot |
| `sessions` | connected desks and who is signed in |
| `draw` | run the draw now |
| `pause` / `resume` | stop or restart ticket sales |
| `notice <text>` | send a notice to every signed-in desk |
| `kick <username>` | disconnect a signed-in account |
| `stop` | shut down (open tickets are refunded at the next start) |

## Networking

- **TCP 6060** carries every request and every live update. Frames are single UTF-8 lines:
  a type followed by `name=value` fields with percent-encoded values, e.g.
  `BUY ref=7 numbers=3%2C7%2C12%2C20%2C33%2C40`. Requests carry a `ref`; the server answers with
  `ROW` frames and one `OK` or `FAIL` carrying the same `ref`. Frames without `ref` are pushed events
  (`CLOCK` every second, `RESULT`, `SETTLED`, `NOTICE`, `CLOSING`). Full list in `PROTOCOL.md`.
- **UDP 6061** beacon: every 2 s the server sends `NETLOTTO-DRAWOFFICE port=6060 name=<host> online=<n>`
  to the broadcast address of each network interface and to 127.0.0.1. Desks only listen.
- **Heartbeat**: desks send `PING` every 10 s; the server closes a connection that is silent for
  35 s (socket read timeout), so a crashed or unplugged desk frees its account.

## Concurrency design

- **Threads**: an acceptor thread; for each desk a reader and a writer thread from two fixed pools
  (64 desks at most); a single draw-clock thread; the beacon thread; and the console thread.
- **Outbox per desk**: other threads never write to a socket. They put frames into the desk's
  bounded queue, which its writer thread drains. A desk that stops reading fills its queue and is
  disconnected, so one slow computer cannot delay the clock or other desks.
- **Draw cycle**: all phase changes, the countdown and the draw run on the clock thread. Operator
  commands are queued onto the same thread.
- **Read/write lock**: purchases hold the read lock (many desks can buy at once); closing sales and
  running the draw take the write lock, which waits for purchases in progress and blocks new ones
  until the draw is saved.
- **One owner per combination**: a purchase first reserves the combination with
  `ConcurrentHashMap.putIfAbsent`, which is atomic, so of two simultaneous buyers exactly one wins.
  The database `UNIQUE (draw_no, numbers)` constraint is an independent second check. The losing
  desk receives `FAIL code=TAKEN` and is not charged.
- **One session per account**: `ConcurrentHashMap.putIfAbsent` on the account id.

## Database and security

H2 embedded database in `data\drawoffice.mv.db`: tables `accounts`, `tickets`, `draws`, `ledger` and
`system_state`. Each purchase, settlement and refund runs in one transaction that also writes the
ledger entry with the balance afterwards, so every balance equals the sum of its statement entries
(`--report` checks this). Tickets left open by a shutdown are refunded at the next start and draw
numbering continues.

Passwords are stored as salted PBKDF2-HMAC-SHA256 hashes (150,000 iterations) in the form
`pbkdf2-sha256$iterations$salt$hash`, never in plain text. An unknown username costs the same time
and gets the same message as a wrong password. Five failed sign-ins from one address lock sign-in
from that address for 60 seconds. The TCP connection is not encrypted; use a trusted LAN.

## Tests

`scripts\build.ps1` runs five plain-Java test programs (no external test framework):

| Suite | Covers |
| --- | --- |
| `RulesTest` | number validation, account rules, prize table, frame encoding |
| `StoreTest` | accounts, purchases, unique combinations, funds, settlement, refunds, ledger totals |
| `ServerIntegrationTest` | real server over TCP: sign-up and sign-in rules, one session per account, 10 simultaneous buyers of one combination (exactly one succeeds), quick pick, draw events, statement, lockout, restart with refund |
| `DeskScreenTest` | renders the real desk offscreen against a real server and saves every screen to `test-results\` |
| `ServerWindowTest` | renders the server window with two live desks, saves every page and checks desks, tickets, accounts, draws and database tables |

## Project structure

```
src/lk/netlotto/core/     Rules, Message (frame format), Frames (frame names and codes)
src/lk/netlotto/server/   DrawServer, ServerWindow, Session, DrawOffice, Store, Beacon, PasswordHasher, DbReport, Log
src/lk/netlotto/desk/     DeskApp, DeskWindow, SignInView, Workspace, the five pages, Link, BeaconListener
src/lk/netlotto/ui/       shared Swing components (colours, buttons, cards, tables, status pills)
tests/                    test programs
scripts/                  build.ps1, package-windows.ps1
lib/                      h2-2.2.224.jar (H2 database engine)
dist/                     runnable JARs
```
