package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.framework.DataContext;
import com.fiscaladmin.gam.enrichrows.framework.StepResult;
import com.fiscaladmin.gam.enrichrows.helpers.TestDataFactory;
import com.fiscaladmin.gam.framework.status.Status;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CurrencyValidationStepTest {

    private CurrencyValidationStep step;
    private FormDataDao mockDao;

    @Before
    public void setUp() {
        step = new CurrencyValidationStep();
        mockDao = Mockito.mock(FormDataDao.class);
    }

    @Test
    public void testStepName() {
        assertEquals("Currency Validation", step.getStepName());
    }

    @Test
    public void testValidCurrency() {
        // Set up mock to return active EUR currency
        FormRowSet currencies = TestDataFactory.rowSet(
                TestDataFactory.currencyRow("EUR", "Euro", "active"),
                TestDataFactory.currencyRow("USD", "US Dollar", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CURRENCY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(currencies);

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCurrency("EUR");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals(DomainConstants.PROCESSING_STATUS_CURRENCY_VALIDATED, ctx.getProcessingStatus());
        assertNotNull(ctx.getAdditionalDataValue("currency_name"));
    }

    @Test
    public void testInvalidCurrency() {
        // Return currencies that don't include XYZ
        FormRowSet currencies = TestDataFactory.rowSet(
                TestDataFactory.currencyRow("EUR", "Euro", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CURRENCY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(currencies);
        // Allow exception queue save
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_EXCEPTION_QUEUE),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(new FormRowSet());

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCurrency("XYZ");

        StepResult result = step.execute(ctx, mockDao);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("Invalid currency"));
    }

    @Test
    public void testMissingCurrency() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCurrency(null);

        StepResult result = step.execute(ctx, mockDao);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("missing"));
    }

    @Test
    public void testInactiveCurrency() {
        FormRowSet currencies = TestDataFactory.rowSet(
                TestDataFactory.currencyRow("GBP", "British Pound", "inactive")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CURRENCY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(currencies);

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCurrency("GBP");

        StepResult result = step.execute(ctx, mockDao);

        assertFalse(result.isSuccess());
    }

    @Test
    public void testShouldExecuteSkipsOnError() {
        DataContext ctx = TestDataFactory.bankContext();
        assertTrue(step.shouldExecute(ctx));

        ctx.setErrorMessage("previous error");
        assertFalse(step.shouldExecute(ctx));
    }

    @Test
    public void testCurrencyNormalization() {
        FormRowSet currencies = TestDataFactory.rowSet(
                TestDataFactory.currencyRow("EUR", "Euro", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CURRENCY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(currencies);

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCurrency("  eur  ");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("EUR", ctx.getCurrency());
    }

    @Test
    public void testExceptionQueueFieldsForLowAmount() {
        // amount = 1000.00 (< 10K) → priority=low, assigned_to=operations, due_date=+7 days
        FormRowSet currencies = TestDataFactory.rowSet(
                TestDataFactory.currencyRow("EUR", "Euro", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CURRENCY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(currencies);

        DataContext ctx = TestDataFactory.bankContext(); // amount = 1000.00
        ctx.setCurrency("XYZ");

        step.execute(ctx, mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_EXCEPTION_QUEUE), captor.capture());
        FormRow savedRow = captor.getValue().get(0);

        assertEquals("low", savedRow.getProperty("priority"));
        assertEquals("operations", savedRow.getProperty("assigned_to"));
        assertNotNull(savedRow.getProperty("due_date"));
        assertEquals("open", savedRow.getProperty("status"));
        assertEquals("TRX-001", savedRow.getProperty("transaction_id"));
        assertEquals("XYZ", savedRow.getProperty("currency"));
    }

    @Test
    public void testExceptionQueueFieldsForHighAmount() {
        // amount = 500000.00 (>= 100K) → priority=high, assigned_to=supervisor, due_date=+1 day
        FormRowSet currencies = TestDataFactory.rowSet(
                TestDataFactory.currencyRow("EUR", "Euro", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CURRENCY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(currencies);

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCurrency("XYZ");
        ctx.setAmount("500000.00");

        step.execute(ctx, mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_EXCEPTION_QUEUE), captor.capture());
        FormRow savedRow = captor.getValue().get(0);

        assertEquals("high", savedRow.getProperty("priority"));
        assertEquals("supervisor", savedRow.getProperty("assigned_to"));
        assertNotNull(savedRow.getProperty("due_date"));
    }

    @Test
    public void testExceptionQueueFieldsForCriticalAmount() {
        // amount = 2000000.00 (>= 1M) → priority=critical, assigned_to=supervisor
        FormRowSet currencies = TestDataFactory.rowSet(
                TestDataFactory.currencyRow("EUR", "Euro", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CURRENCY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(currencies);

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCurrency("XYZ");
        ctx.setAmount("2000000.00");

        step.execute(ctx, mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_EXCEPTION_QUEUE), captor.capture());
        FormRow savedRow = captor.getValue().get(0);

        assertEquals("critical", savedRow.getProperty("priority"));
        assertEquals("supervisor", savedRow.getProperty("assigned_to"));
    }
}
