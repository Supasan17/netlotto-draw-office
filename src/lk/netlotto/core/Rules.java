package lk.netlotto.core;

import java.util.Arrays;
import java.util.regex.Pattern;

/** Draw rules, network settings and input validation shared by the server and the desk. */
public final class Rules {

    private Rules() {
    }

    // ---------------------------------------------------------------- network
    public static final int TCP_PORT = 6060;
    /** The server announces itself on this UDP port every two seconds. */
    public static final int BEACON_PORT = 6061;
    public static final String BEACON_TAG = "NETLOTTO-DRAWOFFICE";
    public static final int BEACON_INTERVAL_MS = 2000;
    /** The desk sends PING this often; the server closes a connection that is silent for IDLE_TIMEOUT. */
    public static final int HEARTBEAT_SECONDS = 10;
    public static final int IDLE_TIMEOUT_SECONDS = 35;
    public static final int MAX_SESSIONS = 64;

    // ---------------------------------------------------------------- draw
    public static final int PICK = 6;
    public static final int HIGHEST = 40;
    public static final int TICKET_PRICE = 20;
    public static final int OPENING_BALANCE = 500;
    public static final int JACKPOT_SEED = 50_000;
    /** Part of every ticket price that is added to the jackpot. */
    public static final int JACKPOT_SHARE = 10;
    /** Sales close this many seconds before each draw. */
    public static final int SALES_CLOSE_SECONDS = 5;
    public static final int DEFAULT_DRAW_SECONDS = 300;
    public static final int MAX_QUICK_PICKS = 45000;
    public static final int LIST_LIMIT = 200;

    // ---------------------------------------------------------------- accounts
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.]{2,19}");
    public static final String USERNAME_RULE =
            "Username: 3 to 20 characters, starting with a letter; letters, digits, '.' and '_' only.";
    public static final String PASSWORD_RULE =
            "Password: 8 to 64 characters with at least one letter and one digit.";

    public static boolean validUsername(String name) {
        return name != null && USERNAME.matcher(name).matches();
    }

    public static boolean validPassword(String pw) {
        if (pw == null || pw.length() < 8 || pw.length() > 64) {
            return false;
        }
        boolean letter = false;
        boolean digit = false;
        for (char c : pw.toCharArray()) {
            if (Character.isISOControl(c)) {
                return false;
            }
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
        }
        return letter && digit;
    }

    // ---------------------------------------------------------------- numbers

    /** Parses "3,7,12,20,33,40" into a sorted, validated selection. */
    public static int[] parseSelection(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new IllegalArgumentException("Choose " + PICK + " numbers.");
        }
        String[] parts = csv.split(",");
        int[] nums = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                nums[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Numbers must be whole numbers.");
        }
        return validate(nums);
    }

    /** Returns a sorted copy, or throws IllegalArgumentException with a readable reason. */
    public static int[] validate(int[] nums) {
        if (nums.length != PICK) {
            throw new IllegalArgumentException("A ticket needs exactly " + PICK + " numbers.");
        }
        int[] sorted = nums.clone();
        Arrays.sort(sorted);
        for (int i = 0; i < sorted.length; i++) {
            if (sorted[i] < 1 || sorted[i] > HIGHEST) {
                throw new IllegalArgumentException("Numbers must be between 1 and " + HIGHEST + ".");
            }
            if (i > 0 && sorted[i] == sorted[i - 1]) {
                throw new IllegalArgumentException("Each number can be chosen only once.");
            }
        }
        return sorted;
    }

    /** Canonical key for a selection, e.g. "03-07-12-20-33-40". Equal selections give equal keys. */
    public static String key(int[] sortedNums) {
        StringBuilder sb = new StringBuilder();
        for (int n : sortedNums) {
            if (sb.length() > 0) {
                sb.append('-');
            }
            if (n < 10) {
                sb.append('0');
            }
            sb.append(n);
        }
        return sb.toString();
    }

    public static int[] fromKey(String key) {
        String[] parts = key.split("-");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            nums[i] = Integer.parseInt(parts[i]);
        }
        return nums;
    }

    public static String csv(int[] nums) {
        StringBuilder sb = new StringBuilder();
        for (int n : nums) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(n);
        }
        return sb.toString();
    }

    public static int matches(int[] ticket, int[] drawn) {
        int count = 0;
        for (int t : ticket) {
            for (int d : drawn) {
                if (t == d) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /**
     * Fixed prize in points by numbers matched (index = matches). Every match earns something and each
     * extra match earns more. Six matches share the jackpot instead and are handled separately.
     */
    private static final int[] PRIZES = {0, 20, 50, 100, 1000, 5000};

    public static int fixedPrize(int matches) {
        return matches >= 0 && matches < PRIZES.length ? PRIZES[matches] : 0;
    }

    /** "1 = 20, 2 = 50, 3 = 100, 4 = 1,000, 5 = 5,000 points" for screens and documents. */
    public static String prizeTable() {
        StringBuilder sb = new StringBuilder();
        for (int m = 1; m < PRIZES.length; m++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(m).append(" = ").append(String.format("%,d", PRIZES[m]));
        }
        return sb.append(" points").toString();
    }

    // ---------------------------------------------------------------- references
    public static String ticketRef(long id) {
        return String.format("TK-%06d", id);
    }

    public static String drawRef(long no) {
        return String.format("DR-%04d", no);
    }
}
