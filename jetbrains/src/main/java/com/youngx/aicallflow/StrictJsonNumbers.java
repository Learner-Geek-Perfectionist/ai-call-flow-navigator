package com.youngx.aicallflow;

import java.util.regex.Pattern;

final class StrictJsonNumbers {
    private static final Pattern INTEGER_LITERAL = Pattern.compile("-?(?:0|[1-9][0-9]*)");

    private StrictJsonNumbers() {
    }

    static int parseInt(String literal) {
        requireIntegerLiteral(literal);
        return Integer.parseInt(literal);
    }

    static long parseLong(String literal) {
        requireIntegerLiteral(literal);
        return Long.parseLong(literal);
    }

    private static void requireIntegerLiteral(String literal) {
        if (!INTEGER_LITERAL.matcher(literal).matches()) {
            throw new NumberFormatException("Not a JSON integer literal: " + literal);
        }
    }
}
