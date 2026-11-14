package lk.netlotto.server;

import lk.netlotto.core.Frames;
import lk.netlotto.core.Message;
import lk.netlotto.core.Rules;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Runs the draw cycle and sells tickets.
 *
 * <h2>Concurrency</h2>
 * <ul>
 *   <li>One clock thread owns the cycle. Every phase change, the countdown and the draw itself run on
 *       it, so they never race each other. Operator commands are queued onto the same thread.</li>
 *   <li>Purchases run on the connection threads and hold the <em>read</em> lock, so many terminals can
 *       buy at the same time. Closing sales and running the draw take the <em>write</em> lock, which
 *       waits for purchases in progress to finish and blocks new ones until the draw is saved.</li>
 *   <li>A combination is reserved with {@link ConcurrentHashMap#putIfAbsent}, an atomic operation:
 *       when two terminals send the same numbers at the same moment exactly one reservation
 *       succeeds. The UNIQUE constraint in the database is a second, independent check.</li>
 * </ul>
 */
final class DrawOffice {

    /** Where events go: implemented by the server. */
    interface Hub {
        void broadcast(Message event);

        void sendTo(int accountId, Message event);

        int online();
    }

    record Sale(int ticketId, int drawNo, String numbers, int balance, long at) {}

    /** A ticket sold for the current draw. */
    record Entry(int ticketId, int accountId, String accountName, int[] numbers, long at) {}

    private static final int RESERVED = -1;

    private final Store store;
    private final Hub hub;
    private final int secondsPerDraw;
    private final SecureRandom random = new SecureRandom();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "draw-clock");
        t.setDaemon(true);
        return t;
    });

    /** Combination key -> ticket id (or RESERVED while the purchase is being saved). */
    private final ConcurrentHashMap<String, Integer> sold = new ConcurrentHashMap<>();
    private final Queue<Entry> entries = new ConcurrentLinkedQueue<>();
    private final AtomicInteger jackpot;

    private volatile int drawNo;
    private volatile int secondsLeft;
    private volatile String phase = Frames.PHASE_OPEN;
    private volatile Message lastResult;

    DrawOffice(Store store, Hub hub, int secondsPerDraw) {
        this.store = store;
        this.hub = hub;
        this.secondsPerDraw = secondsPerDraw;
        this.drawNo = store.nextDrawNo();
        this.secondsLeft = secondsPerDraw;
        this.jackpot = new AtomicInteger(store.jackpot(Rules.JACKPOT_SEED));
        List<Store.DrawRow> last = store.results(1);
        if (!last.isEmpty()) {
            lastResult = resultEvent(last.get(0));
        }
    }

    void start() {
        clock.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    void stop() {
        clock.shutdownNow();
    }

    // ================================================================ sales

    Sale buy(Store.Account account, int[] numbers) {
        lock.readLock().lock();
        try {
            ensureOpen();
            return sell(account, Rules.validate(numbers));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Buys {@code count} tickets with random, unsold combinations. */
    List<Sale> quickPick(Store.Account account, int count) {
        if (count < 1 || count > Rules.MAX_QUICK_PICKS) {
            throw new TicketRefused(Frames.E_INVALID, "Quick pick buys 1 to " + Rules.MAX_QUICK_PICKS + " tickets.");
        }
        lock.readLock().lock();
        try {
            ensureOpen();
            int balance = store.balance(account.id());
            if (balance < count * Rules.TICKET_PRICE) {
                throw new TicketRefused(Frames.E_FUNDS, count + " tickets cost " + count * Rules.TICKET_PRICE
                        + " points and you have " + balance + ".");
            }
            List<Sale> sales = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                for (int attempt = 0; ; attempt++) {
                    int[] pick = randomSelection();
                    if (sold.containsKey(Rules.key(pick)) && attempt < 100) {
                        continue;
                    }
                    try {
                        sales.add(sell(account, pick));
                        break;
                    } catch (TicketRefused e) {
                        if (!Frames.E_TAKEN.equals(e.code()) || attempt >= 100) {
                            throw e;
                        }
                    }
                }
            }
            return sales;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Caller holds the read lock. */
    private Sale sell(Store.Account account, int[] sortedNumbers) {
        String key = Rules.key(sortedNumbers);
        int draw = drawNo;
        if (sold.putIfAbsent(key, RESERVED) != null) {
            throw TicketRefused.taken(key, draw);
        }
        try {
            Store.Purchase p = store.purchase(account.id(), draw, key, Rules.TICKET_PRICE, Rules.JACKPOT_SHARE);
            sold.put(key, p.ticketId());
            entries.add(new Entry(p.ticketId(), account.id(), account.name(), sortedNumbers, p.boughtAt()));
            jackpot.addAndGet(Rules.JACKPOT_SHARE);
            return new Sale(p.ticketId(), draw, key, p.balance(), p.boughtAt());
        } catch (RuntimeException e) {
            sold.remove(key, RESERVED);
            throw e;
        }
    }

    private void ensureOpen() {
        if (Frames.PHASE_PAUSED.equals(phase)) {
            throw new TicketRefused(Frames.E_CLOSED, "Sales are paused by the draw office. Please wait.");
        }
        if (!Frames.PHASE_OPEN.equals(phase)) {
            throw new TicketRefused(Frames.E_CLOSED, "Sales for " + Rules.drawRef(drawNo)
                    + " are closed. The next draw opens in a few seconds.");
        }
    }

    private int[] randomSelection() {
        int[] pool = new int[Rules.HIGHEST];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = i + 1;
        }
        for (int i = 0; i < Rules.PICK; i++) {
            int j = i + random.nextInt(pool.length - i);
            int t = pool[i];
            pool[i] = pool[j];
            pool[j] = t;
        }
        int[] pick = new int[Rules.PICK];
        System.arraycopy(pool, 0, pick, 0, Rules.PICK);
        return Rules.validate(pick);
    }

    // ================================================================ clock

    private void tick() {
        try {
            if (!Frames.PHASE_PAUSED.equals(phase)) {
                secondsLeft--;
                if (secondsLeft <= 0) {
                    runDraw();
                } else if (secondsLeft <= Rules.SALES_CLOSE_SECONDS && Frames.PHASE_OPEN.equals(phase)) {
                    setPhase(Frames.PHASE_CLOSED);
                    Log.info("Sales closed for " + Rules.drawRef(drawNo) + " (" + entries.size() + " tickets).");
                }
            }
            hub.broadcast(clockEvent());
        } catch (RuntimeException e) {
            // Never let one failure stop the clock.
            Log.error("Draw clock error: " + e);
        }
    }

    private void setPhase(String next) {
        lock.writeLock().lock();
        try {
            phase = next;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void runDraw() {
        List<Store.Settled> settled;
        Message result;
        lock.writeLock().lock();
        try {
            int[] drawn = randomSelection();
            List<Entry> tickets = new ArrayList<>(entries);
            List<Store.Payout> payouts = new ArrayList<>();
            List<Entry> jackpotTickets = new ArrayList<>();
            for (Entry e : tickets) {
                if (Rules.matches(e.numbers(), drawn) == Rules.PICK) {
                    jackpotTickets.add(e);
                }
            }
            int pot = jackpot.get();
            int share = jackpotTickets.isEmpty() ? 0 : pot / jackpotTickets.size();
            for (Entry e : tickets) {
                int m = Rules.matches(e.numbers(), drawn);
                payouts.add(new Store.Payout(e.ticketId(), e.accountId(), m, m == Rules.PICK ? share : Rules.fixedPrize(m)));
            }
            int jackpotWon = share * jackpotTickets.size();
            int nextJackpot = jackpotTickets.isEmpty() ? pot : Rules.JACKPOT_SEED + (pot - jackpotWon);

            settled = store.settle(drawNo, Rules.key(drawn), payouts, jackpotWon, nextJackpot);
            jackpot.set(nextJackpot);
            long winners = settled.stream().filter(s -> s.prize() > 0).count();
            result = Message.of(Frames.RESULT, "draw", drawNo, "numbers", Rules.csv(drawn), "sold", tickets.size(),
                    "winners", winners, "jackpotWon", jackpotWon, "at", System.currentTimeMillis() / 1000);
            lastResult = result;
            Log.info("Draw " + Rules.drawRef(drawNo) + ": " + Rules.key(drawn).replace('-', ' ') + " | tickets "
                    + tickets.size() + " | winners " + winners + (jackpotWon > 0 ? " | JACKPOT " + jackpotWon : ""));

            sold.clear();
            entries.clear();
            drawNo++;
            secondsLeft = secondsPerDraw;
            phase = Frames.PHASE_OPEN;
        } catch (RuntimeException e) {
            Log.error("Draw " + Rules.drawRef(drawNo) + " could not be saved: " + e.getMessage()
                    + ". Its tickets stay open and are refunded at the next start.");
            sold.clear();
            entries.clear();
            drawNo++;
            secondsLeft = secondsPerDraw;
            phase = Frames.PHASE_OPEN;
            return;
        } finally {
            lock.writeLock().unlock();
        }
        // Events are sent after the lock is released, so a slow network never delays sales.
        hub.broadcast(result);
        for (Store.Settled s : settled) {
            Message m = Message.of(Frames.SETTLED, "ticket", s.ticketId(), "draw", result.get("draw"),
                    "matches", s.matches(), "prize", s.prize());
            hub.sendTo(s.accountId(), s.balance() >= 0 ? m.with("balance", s.balance()) : m);
        }
    }

    // ================================================================ operator commands

    void drawNow() {
        clock.execute(() -> {
            runDraw();
            hub.broadcast(clockEvent());
        });
    }

    void pause() {
        clock.execute(() -> {
            setPhase(Frames.PHASE_PAUSED);
            Log.info("Sales paused.");
            hub.broadcast(clockEvent());
        });
    }

    void resume() {
        clock.execute(() -> {
            if (Frames.PHASE_PAUSED.equals(phase)) {
                setPhase(secondsLeft <= Rules.SALES_CLOSE_SECONDS ? Frames.PHASE_CLOSED : Frames.PHASE_OPEN);
                Log.info("Sales resumed.");
                hub.broadcast(clockEvent());
            }
        });
    }

    // ================================================================ state for events

    Message clockEvent() {
        return Message.of(Frames.CLOCK, "draw", drawNo, "phase", phase, "secs", secondsLeft,
                "jackpot", jackpot.get(), "sold", entries.size(), "online", hub.online());
    }

    // ================================================================ read-only views for the server window

    int drawNo() {
        return drawNo;
    }

    String phase() {
        return phase;
    }

    int secondsLeft() {
        return secondsLeft;
    }

    int jackpot() {
        return jackpot.get();
    }

    int ticketsSold() {
        return entries.size();
    }

    /** Tickets sold so far for the current draw, newest first. */
    List<Entry> currentTickets() {
        List<Entry> list = new ArrayList<>(entries);
        java.util.Collections.reverse(list);
        return list;
    }

    /** The most recent draw result, or null before the first draw. */
    Message lastResult() {
        return lastResult;
    }

    String summary() {
        return Rules.drawRef(drawNo) + " | " + phase + " | " + secondsLeft + "s left | tickets " + entries.size()
                + " | jackpot " + jackpot.get();
    }

    static Message resultEvent(Store.DrawRow d) {
        return Message.of(Frames.RESULT, "draw", d.drawNo(), "numbers", Rules.csv(Rules.fromKey(d.numbers())),
                "sold", d.ticketsSold(), "winners", d.winners(), "jackpotWon", d.jackpotWon(), "at", d.drawnAt());
    }
}
