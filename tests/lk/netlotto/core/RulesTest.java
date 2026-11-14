package lk.netlotto.core;

import java.util.Arrays;

/** Number validation, account rules and the frame format. */
public final class RulesTest {

    public static void main(String[] args) {
        Check.equal("03-07-12-20-33-40", Rules.key(Rules.parseSelection("40,3,12,7,33,20")),
                "selection is sorted and keyed with two digits");
        Check.equal(Rules.key(Rules.parseSelection("1,2,3,4,5,6")), Rules.key(Rules.parseSelection("6,5,4,3,2,1")),
                "the same numbers in any order give the same key");
        Check.that(Arrays.equals(new int[]{3, 7, 12, 20, 33, 40}, Rules.fromKey("03-07-12-20-33-40")), "key parses back");
        Check.throwsIllegal(() -> Rules.parseSelection("1,2,3,4,5"), "five numbers are refused");
        Check.throwsIllegal(() -> Rules.parseSelection("1,2,3,4,5,6,7"), "seven numbers are refused");
        Check.throwsIllegal(() -> Rules.parseSelection("1,2,3,4,5,5"), "a repeated number is refused");
        Check.throwsIllegal(() -> Rules.parseSelection("0,2,3,4,5,6"), "0 is refused");
        Check.throwsIllegal(() -> Rules.parseSelection("1,2,3,4,5,41"), "41 is refused");
        Check.throwsIllegal(() -> Rules.parseSelection("1,2,x,4,5,6"), "a non-number is refused");
        Check.throwsIllegal(() -> Rules.parseSelection(""), "an empty selection is refused");

        Check.equal(6, Rules.matches(new int[]{1, 2, 3, 4, 5, 6}, new int[]{6, 5, 4, 3, 2, 1}), "six matches");
        Check.equal(3, Rules.matches(new int[]{1, 2, 3, 10, 11, 12}, new int[]{1, 2, 3, 4, 5, 6}), "three matches");
        Check.equal(0, Rules.fixedPrize(0), "no match pays nothing");
        Check.equal(20, Rules.fixedPrize(1), "one match pays 20");
        Check.equal(50, Rules.fixedPrize(2), "two matches pay 50");
        Check.equal(100, Rules.fixedPrize(3), "three matches pay 100");
        Check.equal(1000, Rules.fixedPrize(4), "four matches pay 1000");
        Check.equal(5000, Rules.fixedPrize(5), "five matches pay 5000");
        boolean rising = true;
        for (int m = 1; m < Rules.PICK - 1; m++) {
            rising &= Rules.fixedPrize(m + 1) > Rules.fixedPrize(m);
        }
        Check.that(rising, "every extra matching number pays more");

        Check.that(Rules.validUsername("kasun.p"), "username with a dot is valid");
        Check.that(!Rules.validUsername("1kasun"), "username starting with a digit is refused");
        Check.that(!Rules.validUsername("ab"), "two-character username is refused");
        Check.that(!Rules.validUsername("has space"), "username with a space is refused");
        Check.that(Rules.validPassword("ruhuna2026"), "letters and digits make a valid password");
        Check.that(!Rules.validPassword("onlyletters"), "password without a digit is refused");
        Check.that(!Rules.validPassword("12345678"), "password without a letter is refused");
        Check.that(!Rules.validPassword("abc12"), "short password is refused");

        Message m = Message.of("NOTICE", "ref", 4, "text", "Draw office: back in 5 min = soon & 100% sure");
        Message back = Message.decode(m.encode());
        Check.that(!m.encode().contains("\n") && m.encode().split(" ").length == 3,
                "spaces and symbols in a value do not break the framing");
        Check.equal("Draw office: back in 5 min = soon & 100% sure", back.get("text"), "value survives encode/decode");
        Check.equal(4, back.getInt("ref", 0), "number field survives encode/decode");
        Check.equal("ROW", m.withType("ROW").type(), "withType keeps the fields");
        Check.throwsIllegal(() -> Message.decode("BUY numbers"), "a field without '=' is refused");
        Check.throwsIllegal(() -> Message.decode("   "), "an empty line is refused");
        Check.done();
    }
}
