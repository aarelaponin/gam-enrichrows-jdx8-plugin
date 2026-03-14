package com.fiscaladmin.gam.enrichrows.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class TickerExtractorTest {

    @Test
    public void testDividendsSimple() {
        assertEquals("SBLK", TickerExtractor.extractFromDescription("Dividends (SBLK)"));
    }

    @Test
    public void testDividendsMSFT() {
        assertEquals("MSFT", TickerExtractor.extractFromDescription("Dividends (MSFT)"));
    }

    @Test
    public void testIncomeTaxWithDate() {
        // NVDA is the first valid ticker; (01.07.2024) contains dots so is skipped
        assertEquals("NVDA", TickerExtractor.extractFromDescription("Income tax withheld (NVDA) (01.07.2024)"));
    }

    @Test
    public void testSecuritiesBuy() {
        assertEquals("AAPL", TickerExtractor.extractFromDescription("Securities buy (AAPL)"));
    }

    @Test
    public void testSecuritiesFee() {
        assertEquals("AAPL", TickerExtractor.extractFromDescription("Securities commission fee (AAPL)"));
    }

    @Test
    public void testNullInput() {
        assertNull(TickerExtractor.extractFromDescription(null));
    }

    @Test
    public void testNoParentheses() {
        assertNull(TickerExtractor.extractFromDescription("Wire transfer to account"));
    }

    @Test
    public void testEmptyParentheses() {
        assertNull(TickerExtractor.extractFromDescription("Securities buy ()"));
    }

    @Test
    public void testWhitespaceInParens() {
        assertEquals("TSLA", TickerExtractor.extractFromDescription("Securities buy ( TSLA )"));
    }

    @Test
    public void testDateOnlyInParens() {
        // Date contains dots — not alphanumeric, rejected
        assertNull(TickerExtractor.extractFromDescription("Payment (01.07.2024)"));
    }

    @Test
    public void testLongTickerRejected() {
        assertNull(TickerExtractor.extractFromDescription("Something (THIS_IS_TOO_LONG_FOR_TICKER)"));
    }

    @Test
    public void testEstonianDividend() {
        // No parentheses
        assertNull(TickerExtractor.extractFromDescription("Hüpoteeklaen AS Dividendid"));
    }

    @Test
    public void testUppercased() {
        assertEquals("SBLK", TickerExtractor.extractFromDescription("Dividends (sblk)"));
    }

    @Test
    public void testNestedParensBuyOrder() {
        // "buy" is the first valid paren group (3 chars, alphanumeric) — returns uppercased
        assertEquals("BUY", TickerExtractor.extractFromDescription("Securities (buy) order (MSFT)"));
    }
}
