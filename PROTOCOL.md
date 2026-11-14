# NetLotto Draw Office protocol

Transport: TCP, default port 6060. Every frame is one UTF-8 line ending in `\n`, at most 4096 characters:

```
TYPE name=value name=value ...
```

Values are percent-encoded (`URLEncoder`, UTF-8), so they may contain spaces or any symbol.

## Requests and replies

Every request from the desk carries `ref=<n>`. The server answers with zero or more `ROW` frames and
then exactly one `OK` or `FAIL`, all with the same `ref`. Requests may be pipelined.

| Request | Fields | Reply |
| --- | --- | --- |
| `SIGNUP` | `user`, `password` | `OK user account balance` |
| `SIGNIN` | `user`, `password` | `OK user account balance` |
| `BUY` | `numbers` (comma separated, 6 values) | `OK ticket draw numbers balance at` |
| `QUICKPICK` | `count` (1–5) | `ROW ticket draw numbers at` per ticket, then `OK count balance` |
| `TICKETS` | – | `ROW ticket draw numbers status matches prize at` per ticket, then `OK rows` |
| `STATEMENT` | – | `ROW entry type amount balance reference text at` per entry, then `OK rows balance` |
| `RESULTS` | – | `ROW draw numbers sold winners jackpotWon at` per draw, then `OK rows` |
| `PING` | – | `OK` |
| `SIGNOUT` | – | `OK`, then `CLOSING` and the connection closes |

`FAIL` carries `code` and a readable `message`:

| Code | Meaning |
| --- | --- |
| `INVALID` | malformed request, bad numbers, weak password or invalid username |
| `AUTH` | wrong username or password, or not signed in |
| `EXISTS` | username already registered |
| `IN_USE` | the account is signed in on another desk |
| `LOCKED` | too many failed sign-ins from this address |
| `TAKEN` | the combination is already sold for this draw (nothing is charged) |
| `FUNDS` | balance too low |
| `SALES_CLOSED` | sales are closed before the draw or paused by the operator |
| `UNKNOWN` | unknown request type |
| `SERVER` | database error |

## Events (server to desk, no `ref`)

| Event | Fields | When |
| --- | --- | --- |
| `HELLO` | `server version pick highest price maxQuick` | on connect |
| `CLOCK` | `draw phase secs jackpot sold online` | every second to signed-in desks; `phase` is `OPEN`, `CLOSED` or `PAUSED` |
| `RESULT` | `draw numbers sold winners jackpotWon at` | after each draw, and the latest one at sign-in |
| `SETTLED` | `ticket draw matches prize [balance]` | to the owner of each ticket in the draw |
| `NOTICE` | `text at` | desks coming online or going offline, operator notices |
| `CLOSING` | `reason` | before the server closes the connection |

## Example

```
S: HELLO server=NetLotto+Draw+Office version=2 pick=6 highest=40 price=20 maxQuick=5
D: SIGNIN ref=1 user=kasun password=ruhuna2026
S: OK ref=1 user=kasun account=3 balance=500
S: CLOCK draw=12 phase=OPEN secs=41 jackpot=5230 sold=14 online=3
D: BUY ref=2 numbers=3%2C7%2C12%2C20%2C33%2C40
S: OK ref=2 ticket=88 draw=12 numbers=3%2C7%2C12%2C20%2C33%2C40 balance=480 at=1794646800
D: BUY ref=3 numbers=40%2C33%2C20%2C12%2C7%2C3
S: FAIL ref=3 code=TAKEN message=The+combination+03+07+12+20+33+40+has+already+been+sold...
S: RESULT draw=12 numbers=3%2C9%2C12%2C21%2C33%2C40 sold=15 winners=2 jackpotWon=0 at=1794646830
S: SETTLED ticket=88 draw=12 matches=4 prize=100 balance=580
```

## UDP beacon

Port 6061. Every 2 seconds the server sends one datagram to the broadcast address of each active
interface and to 127.0.0.1:

```
NETLOTTO-DRAWOFFICE port=6060 name=LAB-PC-07 online=3
```

Desks list every server heard in the last 7 seconds. Desks never send UDP.
