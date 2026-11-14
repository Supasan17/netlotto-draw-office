package lk.netlotto.core;

/**
 * Names of every frame type and failure code. See PROTOCOL.md for the full conversation.
 *
 * <p>Requests from the desk carry a {@code ref} number. The server answers each request with any
 * number of {@code ROW} frames followed by exactly one {@code OK} or {@code FAIL} frame carrying the
 * same {@code ref}. Frames without a {@code ref} are events that the server pushes on its own.
 */
public final class Frames {

    private Frames() {
    }

    // requests (desk -> server)
    public static final String SIGN_IN = "SIGNIN";
    public static final String SIGN_UP = "SIGNUP";
    public static final String BUY = "BUY";
    public static final String QUICK_PICK = "QUICKPICK";
    public static final String TICKETS = "TICKETS";
    public static final String STATEMENT = "STATEMENT";
    public static final String RESULTS = "RESULTS";
    public static final String PING = "PING";
    public static final String SIGN_OUT = "SIGNOUT";

    // replies (server -> desk, carry ref)
    public static final String OK = "OK";
    public static final String FAIL = "FAIL";
    public static final String ROW = "ROW";

    // events (server -> desk, no ref)
    public static final String HELLO = "HELLO";
    public static final String CLOCK = "CLOCK";
    public static final String RESULT = "RESULT";
    public static final String SETTLED = "SETTLED";
    public static final String NOTICE = "NOTICE";
    public static final String CLOSING = "CLOSING";

    // draw phases sent in CLOCK
    public static final String PHASE_OPEN = "OPEN";
    public static final String PHASE_CLOSED = "CLOSED";
    public static final String PHASE_PAUSED = "PAUSED";

    // failure codes sent in FAIL
    public static final String E_INVALID = "INVALID";
    public static final String E_AUTH = "AUTH";
    public static final String E_EXISTS = "EXISTS";
    public static final String E_IN_USE = "IN_USE";
    public static final String E_LOCKED = "LOCKED";
    public static final String E_TAKEN = "TAKEN";
    public static final String E_FUNDS = "FUNDS";
    public static final String E_CLOSED = "SALES_CLOSED";
    public static final String E_UNKNOWN = "UNKNOWN";
    public static final String E_SERVER = "SERVER";

    // ticket statuses
    public static final String T_OPEN = "OPEN";
    public static final String T_WON = "WON";
    public static final String T_NO_PRIZE = "NO_PRIZE";
    public static final String T_REFUNDED = "REFUNDED";

    // statement entry types
    public static final String L_OPENING = "OPENING";
    public static final String L_PURCHASE = "PURCHASE";
    public static final String L_PRIZE = "PRIZE";
    public static final String L_REFUND = "REFUND";
}
