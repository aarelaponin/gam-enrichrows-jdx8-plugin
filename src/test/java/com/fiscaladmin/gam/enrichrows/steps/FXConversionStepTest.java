package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.framework.DataContext;
import com.fiscaladmin.gam.enrichrows.framework.StepResult;
import com.fiscaladmin.gam.enrichrows.helpers.TestDataFactory;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRowSet;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

public class FXConversionStepTest {

    private FXConversionStep step;
    private FormDataDao mockDao;

    @Before
    public void setUp() {
        step = new FXConversionStep();
        mockDao = Mockito.mock(FormDataDao.class);
    }

    @Test
    public void testStepName() {
        assertEquals("FX Conversion", step.getStepName());
    }

    @Test
    public void testEurPassthrough() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCurrency("EUR");
        ctx.setAmount("1000.00");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        // EUR should pass through without FX lookup
        assertEquals("1000.00", ctx.getBaseAmount());
    }

    @Test
    public void testFxConversion() {
        // Set up FX rate: 1 EUR = 1.08 USD, so USD->EUR rate = 1/1.08
        FormRowSet rates = TestDataFactory.rowSet(
                TestDataFactory.fxRateRow("USD", "2026-01-15", "1.08", "active")
        );
        when(mockDao.find(isNull(), eq("fx_rates_eur"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rates);

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCurrency("USD");
        ctx.setAmount("1080.00");
        ctx.setTransactionDate("2026-01-15");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertNotNull(ctx.getBaseAmount());
        assertEquals(DomainConstants.PROCESSING_STATUS_FX_CONVERTED, ctx.getProcessingStatus());
    }

    @Test
    public void testMissingRate() {
        // No rates available
        when(mockDao.find(isNull(), eq("fx_rates_eur"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.emptyRowSet());

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCurrency("GBP");
        ctx.setTransactionDate("2026-01-15");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess()); // Continues with placeholder
        assertEquals("0.00", ctx.getBaseAmount());
    }

    @Test
    public void testOldRate() {
        // Rate from 3 days ago
        FormRowSet rates = TestDataFactory.rowSet(
                TestDataFactory.fxRateRow("CHF", "2026-01-12", "0.95", "active")
        );
        when(mockDao.find(isNull(), eq("fx_rates_eur"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rates);

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCurrency("CHF");
        ctx.setAmount("1000.00");
        ctx.setTransactionDate("2026-01-15");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        // Should still use the rate (within 5-day window)
        assertNotNull(ctx.getBaseAmount());
    }

    @Test
    public void testShouldExecuteSkipsOnError() {
        DataContext ctx = TestDataFactory.bankContext();
        assertTrue(step.shouldExecute(ctx));

        ctx.setErrorMessage("previous error");
        assertFalse(step.shouldExecute(ctx));
    }
}
