package com.fiscaladmin.gam.enrichrows.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts ticker symbols from parenthesized text in transaction descriptions.
 * Shared utility used by BankAssetHintStep and TransactionPairingStep.
 */
public final class TickerExtractor {

    private static final Pattern PAREN_GROUP = Pattern.compile("\\(([^)]+)\\)");
    private static final Pattern VALID_TICKER = Pattern.compile("^[A-Za-z0-9]+$");
    private static final int MAX_TICKER_LENGTH = 10;

    private TickerExtractor() {}

    /**
     * Extract ticker symbol from parenthesized text in a description.
     * Finds all (...) groups and returns the first that looks like a valid ticker
     * (1-10 alphanumeric characters, no dots/slashes/spaces).
     *
     * Returns null if no valid ticker candidate found.
     * Result is trimmed and uppercased.
     */
    public static String extractFromDescription(String description) {
        if (description == null) return null;

        Matcher matcher = PAREN_GROUP.matcher(description);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (candidate.isEmpty()) continue;
            if (candidate.length() > MAX_TICKER_LENGTH) continue;
            if (VALID_TICKER.matcher(candidate).matches()) {
                return candidate.toUpperCase();
            }
        }
        return null;
    }
}
