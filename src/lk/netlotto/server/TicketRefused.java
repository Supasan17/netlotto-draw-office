package lk.netlotto.server;

import lk.netlotto.core.Frames;
import lk.netlotto.core.Rules;

/** A request the server refuses for a business reason. The code is sent to the desk in a FAIL frame. */
final class TicketRefused extends RuntimeException {

    private final String code;

    TicketRefused(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    String code() {
        return code;
    }

    static TicketRefused taken(String numbersKey, int drawNo) {
        return new TicketRefused(Frames.E_TAKEN, "The combination " + numbersKey.replace('-', ' ')
                + " has already been sold for draw " + Rules.drawRef(drawNo) + ". Choose different numbers.");
    }
}
