package com.fiscaladmin.gam.enrichrows.framework;

import com.fiscaladmin.gam.enrichrows.helpers.TestDataFactory;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class DataContextTest {

    @Test
    public void testCoreFieldsBank() {
        DataContext ctx = TestDataFactory.bankContext("TX-1", "ST-1");

        assertEquals("bank", ctx.getSourceType());
        assertEquals("TX-1", ctx.getTransactionId());
        assertEquals("ST-1", ctx.getStatementId());
        assertEquals("EUR", ctx.getCurrency());
        assertEquals("1000.00", ctx.getAmount());
        assertEquals("D", ctx.getDebitCredit());
        assertEquals("DEUTDEFF", ctx.getOtherSideBic());
        assertEquals("CUST-001", ctx.getCustomerId());
        assertNotNull(ctx.getTransactionRow());
        assertNotNull(ctx.getStatementRow());
    }

    @Test
    public void testCoreFieldsSecu() {
        DataContext ctx = TestDataFactory.secuContext("TX-2", "ST-2");

        assertEquals("secu", ctx.getSourceType());
        assertEquals("TX-2", ctx.getTransactionId());
        assertEquals("BUY", ctx.getType());
        assertEquals("AAPL", ctx.getTicker());
        assertEquals("100", ctx.getQuantity());
        assertEquals("500.00", ctx.getPrice());
        assertEquals("25.00", ctx.getFee());
    }

    @Test
    public void testAdditionalData() {
        DataContext ctx = new DataContext();

        ctx.setAdditionalDataValue("key1", "value1");
        ctx.setAdditionalDataValue("key2", 42);

        assertEquals("value1", ctx.getAdditionalDataValue("key1"));
        assertEquals(42, ctx.getAdditionalDataValue("key2"));
        assertNull(ctx.getAdditionalDataValue("nonexistent"));
    }

    @Test
    public void testAdditionalDataNullMap() {
        DataContext ctx = new DataContext();
        ctx.setAdditionalData(null);

        // Should not throw
        assertNull(ctx.getAdditionalDataValue("key"));

        // Should auto-create map
        ctx.setAdditionalDataValue("key", "value");
        assertEquals("value", ctx.getAdditionalDataValue("key"));
    }

    @Test
    public void testProcessedSteps() {
        DataContext ctx = new DataContext();

        assertTrue(ctx.getProcessedSteps().isEmpty());

        ctx.addProcessedStep("step1");
        ctx.addProcessedStep("step2");
        ctx.addProcessedStep("step1"); // duplicate

        assertEquals(2, ctx.getProcessedSteps().size());
        assertTrue(ctx.getProcessedSteps().contains("step1"));
        assertTrue(ctx.getProcessedSteps().contains("step2"));
    }

    @Test
    public void testHasError() {
        DataContext ctx = new DataContext();

        assertFalse(ctx.hasError());

        ctx.setErrorMessage("");
        assertFalse(ctx.hasError());

        ctx.setErrorMessage("Something went wrong");
        assertTrue(ctx.hasError());
    }

    @Test
    public void testIsEnriched() {
        DataContext ctx = new DataContext();

        assertFalse(ctx.isEnriched());

        ctx.setProcessingStatus("processing");
        assertFalse(ctx.isEnriched());

        ctx.setProcessingStatus("enriched");
        assertTrue(ctx.isEnriched());

        ctx.setProcessingStatus("complete");
        assertTrue(ctx.isEnriched());
    }

    @Test
    public void testToString() {
        DataContext ctx = TestDataFactory.bankContext();
        String str = ctx.toString();

        assertTrue(str.contains("bank"));
        assertTrue(str.contains("EUR"));
        assertTrue(str.contains("TRX-001"));
    }
}
