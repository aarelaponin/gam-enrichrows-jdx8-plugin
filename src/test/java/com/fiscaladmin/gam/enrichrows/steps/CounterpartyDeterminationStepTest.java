package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;
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

public class CounterpartyDeterminationStepTest {

    private CounterpartyDeterminationStep step;
    private FormDataDao mockDao;

    @Before
    public void setUp() {
        step = new CounterpartyDeterminationStep();
        mockDao = Mockito.mock(FormDataDao.class);
    }

    @Test
    public void testStepName() {
        assertEquals("Counterparty Determination", step.getStepName());
    }

    @Test
    public void testBankCounterpartyFound() {
        // Set up counterparty master with a matching bank
        FormRowSet counterparties = TestDataFactory.rowSet(
                TestDataFactory.counterpartyRow("rec-1", "CPT0143", "Bank", "BARCGB22", true)
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_COUNTERPARTY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(counterparties);

        // Bank master for name lookup
        FormRowSet banks = TestDataFactory.rowSet();
        when(mockDao.find(isNull(), eq("bank"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(banks);

        // Counterparty detail lookup for short code
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_COUNTERPARTY_MASTER),
                eq("WHERE c_counterpartyId = ?"), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(counterparties);

        DataContext ctx = TestDataFactory.bankContext();
        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("CPT0143", ctx.getAdditionalDataValue("counterparty_id"));
        assertEquals("Bank", ctx.getAdditionalDataValue("counterparty_type"));
        assertEquals(DomainConstants.PROCESSING_STATUS_COUNTERPARTY_DETERMINED,
                ctx.getProcessingStatus());
    }

    @Test
    public void testCounterpartyNotFound() {
        // No counterparties match
        FormRowSet emptySet = TestDataFactory.emptyRowSet();
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_COUNTERPARTY_MASTER),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(emptySet);
        when(mockDao.find(isNull(), eq("bank"),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(emptySet);

        DataContext ctx = TestDataFactory.bankContext();
        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess()); // Continues with UNKNOWN
        assertEquals(FrameworkConstants.ENTITY_UNKNOWN,
                ctx.getAdditionalDataValue("counterparty_id"));
    }

    @Test
    public void testSecuCounterpartyDetermination() {
        FormRowSet counterparties = TestDataFactory.rowSet(
                TestDataFactory.counterpartyRow("rec-2", "CPT0200", "Custodian", "UBSWCHZH", true)
        );
        // Need to handle the custodianId lookup
        counterparties.get(0).setProperty("custodianId", "UBSWCHZH");

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_COUNTERPARTY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(counterparties);
        when(mockDao.find(isNull(), eq("bank"),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_COUNTERPARTY_MASTER),
                eq("WHERE c_counterpartyId = ?"), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(counterparties);

        DataContext ctx = TestDataFactory.secuContext();
        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertNotNull(ctx.getAdditionalDataValue("counterparty_id"));
    }

    @Test
    public void testInactiveCounterpartySkipped() {
        FormRowSet counterparties = TestDataFactory.rowSet(
                TestDataFactory.counterpartyRow("rec-1", "CPT0143", "Bank", "BARCGB22", false)
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_COUNTERPARTY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(counterparties);
        when(mockDao.find(isNull(), eq("bank"),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(TestDataFactory.emptyRowSet());

        DataContext ctx = TestDataFactory.bankContext();
        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess()); // Continues with UNKNOWN
        assertEquals(FrameworkConstants.ENTITY_UNKNOWN,
                ctx.getAdditionalDataValue("counterparty_id"));
    }

    // ===== Estonian Transaction Type Tests (§2.3) =====

    /**
     * Helper: set up mocks so the secu counterparty lookup succeeds,
     * then execute the step and return the context for assertions.
     */
    private DataContext executeSecuWithType(String txType) {
        // Counterparty master with a Custodian entry whose custodianId matches the statement bank
        FormRowSet counterparties = TestDataFactory.rowSet(
                TestDataFactory.counterpartyRow("rec-2", "CPT0200", "Custodian", "UBSWCHZH", true)
        );
        counterparties.get(0).setProperty("custodianId", "UBSWCHZH");

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_COUNTERPARTY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(counterparties);
        when(mockDao.find(isNull(), eq("bank"),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_COUNTERPARTY_MASTER),
                eq("WHERE c_counterpartyId = ?"), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(counterparties);

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setType(txType);
        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("CPT0200", ctx.getAdditionalDataValue("counterparty_id"));
        return ctx;
    }

    @Test
    public void testEstonianBuyType() {
        DataContext ctx = executeSecuWithType("ost");
        assertEquals("Broker", ctx.getAdditionalDataValue("counterparty_type"));
    }

    @Test
    public void testEstonianSellType() {
        DataContext ctx = executeSecuWithType("müük");
        assertEquals("Broker", ctx.getAdditionalDataValue("counterparty_type"));
    }

    @Test
    public void testEstonianSplitType() {
        DataContext ctx = executeSecuWithType("split+");
        assertEquals("Custodian", ctx.getAdditionalDataValue("counterparty_type"));
    }

    @Test
    public void testEstonianSplitMinusType() {
        DataContext ctx = executeSecuWithType("split-");
        assertEquals("Custodian", ctx.getAdditionalDataValue("counterparty_type"));
    }

    @Test
    public void testOtherSideDataStored() {
        // Bank transactions should store other_side_bic and other_side_name in additionalData
        FormRowSet counterparties = TestDataFactory.rowSet(
                TestDataFactory.counterpartyRow("rec-1", "CPT0143", "Bank", "BARCGB22", true)
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_COUNTERPARTY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(counterparties);
        when(mockDao.find(isNull(), eq("bank"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_COUNTERPARTY_MASTER),
                eq("WHERE c_counterpartyId = ?"), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(counterparties);

        DataContext ctx = TestDataFactory.bankContext(); // has otherSideBic=DEUTDEFF, otherSideName=Deutsche Bank AG
        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("DEUTDEFF", ctx.getAdditionalDataValue("other_side_bic"));
        assertEquals("Deutsche Bank AG", ctx.getAdditionalDataValue("other_side_name"));
    }

    @Test
    public void testShortCodeStored() {
        // After counterparty resolved, short code should be stored in additionalData
        FormRowSet counterparties = TestDataFactory.rowSet(
                TestDataFactory.counterpartyRow("rec-1", "CPT0143", "Bank", "BARCGB22", true)
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_COUNTERPARTY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(counterparties);
        when(mockDao.find(isNull(), eq("bank"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.emptyRowSet());
        // Short code lookup: counterpartyRow sets shortCode = "CPT0" (first 4 chars)
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_COUNTERPARTY_MASTER),
                eq("WHERE c_counterpartyId = ?"), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(counterparties);

        DataContext ctx = TestDataFactory.bankContext();
        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertNotNull("counterparty_short_code should be stored",
                ctx.getAdditionalDataValue("counterparty_short_code"));
        assertEquals("CPT0", ctx.getAdditionalDataValue("counterparty_short_code"));
    }

    @Test
    public void testShouldExecuteSkipsOnError() {
        DataContext ctx = TestDataFactory.bankContext();
        assertTrue(step.shouldExecute(ctx));

        ctx.setErrorMessage("previous error");
        assertFalse(step.shouldExecute(ctx));
    }

    // =========================================================================
    // §9b Idempotency guard tests
    // =========================================================================

    @Test
    public void testIdempotency_skipsResolved() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");
        assertFalse("Should skip when counterparty already resolved", step.shouldExecute(ctx));
    }

    @Test
    public void testIdempotency_skipsEvenUnknown() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "UNKNOWN", "Bank");
        assertFalse("Should skip even when counterparty is UNKNOWN (no sentinel)", step.shouldExecute(ctx));
    }

    // =========================================================================
    // English Transaction Type Tests
    // =========================================================================

    @Test
    public void testEnglishBuyType() {
        DataContext ctx = executeSecuWithType("BUY");
        assertEquals("Broker", ctx.getAdditionalDataValue("counterparty_type"));
    }

    @Test
    public void testEnglishSellType() {
        DataContext ctx = executeSecuWithType("SELL");
        assertEquals("Broker", ctx.getAdditionalDataValue("counterparty_type"));
    }

    @Test
    public void testEnglishTradeType() {
        DataContext ctx = executeSecuWithType("TRADE");
        assertEquals("Broker", ctx.getAdditionalDataValue("counterparty_type"));
    }

    @Test
    public void testEnglishCustodyType() {
        DataContext ctx = executeSecuWithType("CUSTODY");
        assertEquals("Custodian", ctx.getAdditionalDataValue("counterparty_type"));
    }

    @Test
    public void testEnglishDividendType() {
        DataContext ctx = executeSecuWithType("DIVIDEND");
        assertEquals("Custodian", ctx.getAdditionalDataValue("counterparty_type"));
    }

    // =========================================================================
    // Edge Cases — null, empty, unknown transaction types
    // =========================================================================

    @Test
    public void testNullTransactionType() {
        DataContext ctx = executeSecuWithType(null);
        assertEquals("Custodian", ctx.getAdditionalDataValue("counterparty_type"));
    }

    @Test
    public void testEmptyTransactionType() {
        DataContext ctx = executeSecuWithType("");
        assertEquals("Custodian", ctx.getAdditionalDataValue("counterparty_type"));
    }

    @Test
    public void testUnknownTransactionType() {
        DataContext ctx = executeSecuWithType("UNKNOWN_TYPE");
        assertEquals("Custodian", ctx.getAdditionalDataValue("counterparty_type"));
    }
}
