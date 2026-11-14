package lk.netlotto.server;

import lk.netlotto.core.Check;
import lk.netlotto.core.Frames;
import lk.netlotto.core.Rules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The database layer: accounts, purchases, the unique-combination rule, settlement, refunds and the ledger. */
public final class StoreTest {

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("drawoffice-store");
        try (Store store = new Store(dir)) {
            String hash = PasswordHasher.hash("secret123");
            Check.that(!hash.contains("secret123") && hash.startsWith("pbkdf2-sha256$"), "stored hash hides the password");
            Check.that(PasswordHasher.verify("secret123", hash), "correct password verifies");
            Check.that(!PasswordHasher.verify("secret124", hash), "wrong password is refused");
            Check.that(!hash.equals(PasswordHasher.hash("secret123")), "same password gets a different salt each time");

            Store.Account a = store.createAccount("Nimal", hash, 100);
            Check.that(a != null, "account created");
            Check.that(store.createAccount("NIMAL", hash, 100) == null, "username is unique ignoring case");
            Check.equal("Nimal", store.findLogin("nimal").account().name(), "sign-in lookup ignores case");
            Check.that(store.findLogin("nobody") == null, "unknown username finds nothing");

            int jackpot = store.jackpot(Rules.JACKPOT_SEED);
            Store.Purchase p = store.purchase(a.id(), 1, "01-02-03-04-05-06", 20, 10);
            Check.equal(80, p.balance(), "purchase charges the ticket price");
            Check.equal(jackpot + 10, store.jackpot(0), "purchase adds its share to the jackpot");
            try {
                store.purchase(a.id(), 1, "01-02-03-04-05-06", 20, 10);
                Check.that(false, "database refuses the same combination twice in a draw");
            } catch (TicketRefused e) {
                Check.equal(Frames.E_TAKEN, e.code(), "database refuses the same combination twice in a draw");
            }
            Check.equal(80, store.balance(a.id()), "a refused purchase charges nothing");
            store.purchase(a.id(), 2, "01-02-03-04-05-06", 20, 10);
            Check.equal(60, store.balance(a.id()), "the same combination is allowed in a different draw");

            store.purchase(a.id(), 2, "07-08-09-10-11-12", 20, 10);
            store.purchase(a.id(), 2, "13-14-15-16-17-18", 20, 10);
            store.purchase(a.id(), 2, "19-20-21-22-23-24", 20, 10);
            Check.equal(0, store.balance(a.id()), "the last credits can be spent");
            try {
                store.purchase(a.id(), 2, "25-26-27-28-29-30", 20, 10);
                Check.that(false, "purchase with too little balance is refused");
            } catch (TicketRefused e) {
                Check.equal(Frames.E_FUNDS, e.code(), "purchase with too little balance is refused");
            }
            Check.equal(0, store.balance(a.id()), "balance never goes negative");

            List<Store.Settled> settled = store.settle(1, "01-02-03-30-31-32",
                    List.of(new Store.Payout(p.ticketId(), a.id(), 3, 20)), 0, 7000);
            Check.equal(20, settled.get(0).balance(), "prize is credited in the settlement");
            Check.equal(7000, store.jackpot(0), "settlement saves the next jackpot");
            Check.equal(Frames.T_WON, store.tickets(a.id(), 10).get(4).status(), "winning ticket is marked WON");
            Check.equal(1, store.results(5).size(), "draw is recorded");
            Check.equal(3, store.nextDrawNo(), "draw numbering continues after the highest draw or ticket");

            int refunded = store.refundOpenTickets(20);
            Check.equal(4, refunded, "open tickets of an interrupted draw are refunded");
            Check.equal(100, store.balance(a.id()), "refunds restore the balance");

            List<Store.LedgerRow> ledger = store.statement(a.id(), 100);
            int sum = ledger.stream().mapToInt(Store.LedgerRow::amount).sum();
            Check.equal(store.balance(a.id()), sum, "statement entries add up to the balance");
            Check.equal(store.balance(a.id()), ledger.get(0).balanceAfter(), "latest statement entry shows the current balance");
            Check.equal(Frames.L_OPENING, ledger.get(ledger.size() - 1).type(), "first statement entry is the opening balance");
        }
        Check.done();
    }
}
