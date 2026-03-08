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

    // =========================================================================
    // Securities FX conversion tests
    // =========================================================================

    @Test
    public void testSecuFeeConversion() {
        // FX rate: 1 EUR = 1.08 USD → USD→EUR = 1/1.08
        FormRowSet rates = TestDataFactory.rowSet(
                TestDataFactory.fxRateRow("USD", "2026-01-15", "1.08", "active")
        );
        when(mockDao.find(isNull(), eq("fx_rates_eur"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rates);

        DataContext ctx = TestDataFactory.secuContext(); // USD, fee=25.00
        ctx.setTransactionDate("2026-01-15");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        // Fee should be converted: 25.00 / 1.08 ≈ 23.15
        String baseFee = (String) ctx.getAdditionalDataValue("base_fee");
        assertNotNull("base_fee should be set for secu transactions", baseFee);
        double baseFeeVal = Double.parseDouble(baseFee);
        assertTrue("base_fee should be ~23.15", baseFeeVal > 23.0 && baseFeeVal < 24.0);
    }

    @Test
    public void testSecuTotalAmountConversion() {
        FormRowSet rates = TestDataFactory.rowSet(
                TestDataFactory.fxRateRow("USD", "2026-01-15", "1.08", "active")
        );
        when(mockDao.find(isNull(), eq("fx_rates_eur"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rates);

        DataContext ctx = TestDataFactory.secuContext(); // USD, totalAmount=50025.00
        ctx.setTransactionDate("2026-01-15");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        // Total amount should be converted: 50025.00 / 1.08 ≈ 46319.44
        String baseTotalAmount = (String) ctx.getAdditionalDataValue("base_total_amount");
        assertNotNull("base_total_amount should be set for secu transactions", baseTotalAmount);
        double baseTotalVal = Double.parseDouble(baseTotalAmount);
        assertTrue("base_total_amount should be ~46319", baseTotalVal > 46000.0 && baseTotalVal < 47000.0);
    }

    @Test
    public void testRateInversion() {
        // Rate stored as "1 EUR = 1.08 USD", so USD→EUR should use 1/1.08
        FormRowSet rates = TestDataFactory.rowSet(
                TestDataFactory.fxRateRow("USD", "2026-01-15", "1.08", "active")
        );
        when(mockDao.find(isNull(), eq("fx_rates_eur"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rates);

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCurrency("USD");
        ctx.setAmount("108.00");
        ctx.setTransactionDate("2026-01-15");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        // 108 USD / 1.08 = 100 EUR
        double baseAmount = Double.parseDouble(ctx.getBaseAmount());
        assertEquals(100.0, baseAmount, 0.01);
    }

    @Test
    public void testMultipleRatesSameDate_usesFirst() {
        // Two rates for same currency and date — first match wins
        FormRowSet rates = TestDataFactory.rowSet(
                TestDataFactory.fxRateRow("GBP", "2026-01-15", "0.85", "active"),
                TestDataFactory.fxRateRow("GBP", "2026-01-15", "0.90", "active")
        );
        when(mockDao.find(isNull(), eq("fx_rates_eur"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rates);

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCurrency("GBP");
        ctx.setAmount("85.00");
        ctx.setTransactionDate("2026-01-15");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        // Should use first rate: 85 / 0.85 = 100 EUR
        double baseAmount = Double.parseDouble(ctx.getBaseAmount());
        assertEquals(100.0, baseAmount, 0.01);
    }

    // =========================================================================
    // §9b Idempotency guard tests
    // =========================================================================

    @Test
    public void testIdempotency_skipsNonZero() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setAdditionalDataValue("base_amount", "920.00");
        assertFalse("Should skip when base_amount is non-zero", step.shouldExecute(ctx));
    }

    @Test
    public void testIdempotency_reEvaluatesZero() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setAdditionalDataValue("base_amount", "0.0");
        assertTrue("Should re-evaluate when base_amount is zero", step.shouldExecute(ctx));
    }

    @Test
    public void testIdempotency_reEvaluatesNull() {
        DataContext ctx = TestDataFactory.bankContext();
        // No base_amount set → should execute
        assertTrue("Should execute when base_amount is null", step.shouldExecute(ctx));
    }
}
