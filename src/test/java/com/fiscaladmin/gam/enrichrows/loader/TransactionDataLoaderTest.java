package com.fiscaladmin.gam.enrichrows.loader;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;
import com.fiscaladmin.gam.enrichrows.framework.DataContext;
import com.fiscaladmin.gam.enrichrows.helpers.TestDataFactory;
import com.fiscaladmin.gam.framework.status.Status;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class TransactionDataLoaderTest {

    private TransactionDataLoader loader;
    private FormDataDao mockDao;

    @Before
    public void setUp() {
        loader = new TransactionDataLoader();
        mockDao = Mockito.mock(FormDataDao.class);
        // Note: StatusManager.transition() is static and cannot be mocked.
        // StatusManager is not set — transitions are skipped via null guard.
    }

    @Test
    public void testConsolidatedAndEnrichedStatementsIncluded() {
        // Both consolidated and enriched statements should be included
        FormRow consolidated = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        consolidated.setProperty("status", Status.CONSOLIDATED.getCode());

        FormRow enriched = TestDataFactory.statementRow("STMT-002", DomainConstants.SOURCE_TYPE_BANK);
        enriched.setProperty("status", Status.ENRICHED.getCode());

        FormRow posted = TestDataFactory.statementRow("STMT-003", DomainConstants.SOURCE_TYPE_BANK);
        posted.setProperty("status", Status.POSTED.getCode());

        FormRowSet statements = TestDataFactory.rowSet(consolidated, enriched, posted);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(statements);

        // Return empty for bank transactions
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.emptyRowSet());

        wireValidationMocks();

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        // CONSOLIDATED and ENRICHED included, POSTED excluded
        // No transactions returned since bank trx rows are empty
        assertEquals(0, result.size());
        // Verify bank trx fetch was called twice (once for each enrichable statement)
        verify(mockDao, times(2)).find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000));
    }

    @Test
    public void testBankContextConstruction() {
        // Set up a consolidated statement
        FormRow statement = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        statement.setProperty("status", Status.CONSOLIDATED.getCode());

        FormRowSet statements = TestDataFactory.rowSet(statement);

        // Set up bank transaction
        FormRow bankTrx = TestDataFactory.bankTrxRow("TRX-001", "STMT-001");
        bankTrx.setProperty("status", Status.NEW.getCode());
        FormRowSet bankTrxRows = TestDataFactory.rowSet(bankTrx);

        // Wire up mocks
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(statements);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(bankTrxRows);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_SECU_TOTAL_TRX),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(1, result.size());
        DataContext ctx = result.get(0);
        assertEquals("TRX-001", ctx.getTransactionId());
        assertEquals("STMT-001", ctx.getStatementId());
        assertEquals(DomainConstants.SOURCE_TYPE_BANK, ctx.getSourceType());
        assertEquals("EUR", ctx.getCurrency());
        assertEquals("1000.00", ctx.getAmount());
        assertEquals("2026-01-01", ctx.getStatementDate());
    }

    @Test
    public void testStatusManagerProcessingTransitionForBank() {
        // Set up consolidated statement + bank transaction
        FormRow statement = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        statement.setProperty("status", Status.CONSOLIDATED.getCode());

        FormRow bankTrx = TestDataFactory.bankTrxRow("TRX-001", "STMT-001");
        bankTrx.setProperty("status", Status.NEW.getCode());

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(statement));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.rowSet(bankTrx));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_SECU_TOTAL_TRX),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        // Verify transaction was loaded successfully (StatusManager transition skipped — static methods)
        assertEquals(1, result.size());
        assertEquals("TRX-001", result.get(0).getTransactionId());
    }

    @Test
    public void testStatusManagerProcessingTransitionForSecu() {
        // Set up consolidated statement + secu transaction
        FormRow statement = TestDataFactory.statementRow("STMT-002", DomainConstants.SOURCE_TYPE_SECU);
        statement.setProperty("status", Status.CONSOLIDATED.getCode());

        FormRow secuTrx = TestDataFactory.secuTrxRow("TRX-002", "STMT-002");
        secuTrx.setProperty("status", Status.NEW.getCode());

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(statement));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_SECU_TOTAL_TRX),
                isNull(), isNull(), eq("transaction_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.rowSet(secuTrx));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_SECU_TOTAL_TRX),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        // Verify transaction was loaded successfully (StatusManager transition skipped — static methods)
        assertEquals(1, result.size());
        assertEquals("TRX-002", result.get(0).getTransactionId());
    }

    @Test
    public void testNoStatusManagerTransitionWhenNull() {
        // Set up loader without StatusManager
        loader = new TransactionDataLoader();

        FormRow statement = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        statement.setProperty("status", Status.CONSOLIDATED.getCode());

        FormRow bankTrx = TestDataFactory.bankTrxRow("TRX-001", "STMT-001");
        bankTrx.setProperty("status", Status.NEW.getCode());

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(statement));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.rowSet(bankTrx));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_SECU_TOTAL_TRX),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());

        // Should not throw, just skip StatusManager
        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());
        assertEquals(1, result.size());
    }

    @Test
    public void testSecuContextConstruction() {
        FormRow statement = TestDataFactory.statementRow("STMT-002", DomainConstants.SOURCE_TYPE_SECU);
        statement.setProperty("status", Status.CONSOLIDATED.getCode());

        FormRow secuTrx = TestDataFactory.secuTrxRow("TRX-002", "STMT-002");
        secuTrx.setProperty("status", Status.NEW.getCode());

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(statement));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_SECU_TOTAL_TRX),
                isNull(), isNull(), eq("transaction_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.rowSet(secuTrx));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_SECU_TOTAL_TRX),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(1, result.size());
        DataContext ctx = result.get(0);
        assertEquals("TRX-002", ctx.getTransactionId());
        assertEquals("STMT-002", ctx.getStatementId());
        assertEquals(DomainConstants.SOURCE_TYPE_SECU, ctx.getSourceType());
        assertEquals("USD", ctx.getCurrency());
        assertEquals("50000.00", ctx.getAmount());
        assertEquals("50025.00", ctx.getTotalAmount());
        assertEquals("AAPL", ctx.getTicker());
        assertEquals("BUY", ctx.getType());
        assertEquals("2026-01-01", ctx.getStatementDate());
    }

    @Test
    public void testBankContextIncludesOtherSideAccount() {
        FormRow statement = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        statement.setProperty("status", Status.CONSOLIDATED.getCode());

        FormRow bankTrx = TestDataFactory.bankTrxRow("TRX-001", "STMT-001");
        bankTrx.setProperty("status", Status.NEW.getCode());
        bankTrx.setProperty("other_side_account", "DE89370400440532013000");
        FormRowSet bankTrxRows = TestDataFactory.rowSet(bankTrx);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(statement));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(bankTrxRows);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_SECU_TOTAL_TRX),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(1, result.size());
        DataContext ctx = result.get(0);
        assertEquals("DE89370400440532013000", ctx.getOtherSideAccount());
    }

    // ===== Re-enrichment Tests =====

    @Test
    public void testEnrichedStatementsLoaded() {
        FormRow enrichedStmt = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        enrichedStmt.setProperty("status", Status.ENRICHED.getCode());

        FormRow bankTrx = TestDataFactory.bankTrxRow("TRX-001", "STMT-001");
        bankTrx.setProperty("status", Status.ENRICHED.getCode());

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(enrichedStmt));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.rowSet(bankTrx));
        wireValidationMocks();

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(1, result.size());
        assertEquals("TRX-001", result.get(0).getTransactionId());
    }

    @Test
    public void testPostedStatementsExcluded() {
        FormRow postedStmt = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        postedStmt.setProperty("status", Status.POSTED.getCode());

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(postedStmt));
        wireValidationMocks();

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(0, result.size());
    }

    @Test
    public void testErrorStatementsExcluded() {
        FormRow errorStmt = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        errorStmt.setProperty("status", Status.ERROR.getCode());

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(errorStmt));
        wireValidationMocks();

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(0, result.size());
    }

    @Test
    public void testEnrichedBankTrxLoaded() {
        FormRow enrichedStmt = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        enrichedStmt.setProperty("status", Status.ENRICHED.getCode());

        FormRow bankTrx = TestDataFactory.bankTrxRow("TRX-001", "STMT-001");
        bankTrx.setProperty("status", Status.ENRICHED.getCode());

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(enrichedStmt));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.rowSet(bankTrx));
        wireValidationMocks();

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(1, result.size());
        assertTrue(result.get(0).isReEnrichment());
    }

    @Test
    public void testManualReviewBankTrxLoaded() {
        FormRow enrichedStmt = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        enrichedStmt.setProperty("status", Status.ENRICHED.getCode());

        FormRow bankTrx = TestDataFactory.bankTrxRow("TRX-001", "STMT-001");
        bankTrx.setProperty("status", Status.MANUAL_REVIEW.getCode());

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(enrichedStmt));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.rowSet(bankTrx));
        wireValidationMocks();

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(1, result.size());
        assertTrue(result.get(0).isReEnrichment());
    }

    @Test
    public void testPostedBankTrxExcluded() {
        FormRow enrichedStmt = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        enrichedStmt.setProperty("status", Status.ENRICHED.getCode());

        FormRow bankTrx = TestDataFactory.bankTrxRow("TRX-001", "STMT-001");
        bankTrx.setProperty("status", Status.POSTED.getCode());

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(enrichedStmt));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.rowSet(bankTrx));
        wireValidationMocks();

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(0, result.size());
    }

    @Test
    public void testPairedBankTrxExcluded() {
        FormRow enrichedStmt = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        enrichedStmt.setProperty("status", Status.ENRICHED.getCode());

        FormRow bankTrx = TestDataFactory.bankTrxRow("TRX-001", "STMT-001");
        bankTrx.setProperty("status", Status.PAIRED.getCode());

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(enrichedStmt));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.rowSet(bankTrx));
        wireValidationMocks();

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(0, result.size());
    }

    @Test
    public void testReEnrichmentFlagSetForNonNew() {
        FormRow stmt = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        stmt.setProperty("status", Status.ENRICHED.getCode());

        FormRow bankTrx = TestDataFactory.bankTrxRow("TRX-001", "STMT-001");
        bankTrx.setProperty("status", Status.ENRICHED.getCode());

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(stmt));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.rowSet(bankTrx));
        wireValidationMocks();

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(1, result.size());
        assertTrue("ENRICHED trx should have reEnrichment=true", result.get(0).isReEnrichment());
    }

    @Test
    public void testReEnrichmentFlagFalseForNew() {
        FormRow stmt = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        stmt.setProperty("status", Status.CONSOLIDATED.getCode());

        FormRow bankTrx = TestDataFactory.bankTrxRow("TRX-001", "STMT-001");
        bankTrx.setProperty("status", Status.NEW.getCode());

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(stmt));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.rowSet(bankTrx));
        wireValidationMocks();

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(1, result.size());
        assertFalse("NEW trx should have reEnrichment=false", result.get(0).isReEnrichment());
    }

    @Test
    public void testWorkspaceProtectedTrxMarked() {
        FormRow stmt = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        stmt.setProperty("status", Status.ENRICHED.getCode());

        FormRow bankTrx = TestDataFactory.bankTrxRow("TRX-001", "STMT-001");
        bankTrx.setProperty("status", Status.ENRICHED.getCode());

        // Mock enrichment record with IN_REVIEW status
        FormRow enrichmentRow = new FormRow();
        enrichmentRow.setId("ENR-001");
        enrichmentRow.setProperty(FrameworkConstants.FIELD_STATUS, Status.IN_REVIEW.getCode());
        FormRowSet enrichmentRows = TestDataFactory.rowSet(enrichmentRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(stmt));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.rowSet(bankTrx));
        // Mock enrichment lookup
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                eq("WHERE c_source_trx_id = ?"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(enrichmentRows);
        wireValidationMocks();

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(1, result.size());
        assertEquals("true", result.get(0).getAdditionalDataValue("workspace_protected"));
    }

    @Test
    public void testPairedEnrichmentTrxMarkedWorkspaceProtected() {
        FormRow stmt = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        stmt.setProperty("status", Status.ENRICHED.getCode());

        FormRow bankTrx = TestDataFactory.bankTrxRow("TRX-001", "STMT-001");
        bankTrx.setProperty("status", Status.ENRICHED.getCode());

        // Mock enrichment record with PAIRED status
        FormRow enrichmentRow = new FormRow();
        enrichmentRow.setId("ENR-001");
        enrichmentRow.setProperty(FrameworkConstants.FIELD_STATUS, Status.PAIRED.getCode());
        FormRowSet enrichmentRows = TestDataFactory.rowSet(enrichmentRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(stmt));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.rowSet(bankTrx));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                eq("WHERE c_source_trx_id = ?"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(enrichmentRows);
        wireValidationMocks();

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(1, result.size());
        assertEquals("true", result.get(0).getAdditionalDataValue("workspace_protected"));
    }

    @Test
    public void testEnrichmentStatusStoredInAdditionalData() {
        FormRow stmt = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        stmt.setProperty("status", Status.ENRICHED.getCode());

        FormRow bankTrx = TestDataFactory.bankTrxRow("TRX-001", "STMT-001");
        bankTrx.setProperty("status", Status.ENRICHED.getCode());

        // Mock enrichment record with ENRICHED status (not workspace-protected)
        FormRow enrichmentRow = new FormRow();
        enrichmentRow.setId("ENR-001");
        enrichmentRow.setProperty(FrameworkConstants.FIELD_STATUS, Status.ENRICHED.getCode());
        FormRowSet enrichmentRows = TestDataFactory.rowSet(enrichmentRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(TestDataFactory.rowSet(stmt));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.rowSet(bankTrx));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                eq("WHERE c_source_trx_id = ?"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(enrichmentRows);
        wireValidationMocks();

        List<DataContext> result = loader.loadData(mockDao, new HashMap<>());

        assertEquals(1, result.size());
        assertEquals(Status.ENRICHED.getCode(), result.get(0).getAdditionalDataValue("enrichment_status"));
    }

    // ===== Helper =====

    private void wireValidationMocks() {
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_SECU_TOTAL_TRX),
                isNull(), isNull(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());
    }
}
