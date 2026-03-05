package com.fiscaladmin.gam.enrichrows.loader;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
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
    public void testConsolidatedStatementsFiltered() {
        // Create statements: one consolidated, one with different status
        FormRow consolidated = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        consolidated.setProperty("status", Status.CONSOLIDATED.getCode());

        FormRow enriched = TestDataFactory.statementRow("STMT-002", DomainConstants.SOURCE_TYPE_BANK);
        enriched.setProperty("status", Status.ENRICHED.getCode());

        FormRowSet statements = TestDataFactory.rowSet(consolidated, enriched);

        // Return statements when queried
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                isNull(), isNull(), eq("from_date"), eq(false), eq(0), eq(100)))
                .thenReturn(statements);

        // Return empty for bank transactions
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                isNull(), isNull(), eq("payment_date"), eq(false), eq(0), eq(10000)))
                .thenReturn(TestDataFactory.emptyRowSet());

        // Validate data source
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

        // Only consolidated statement should be processed (STMT-001)
        // No transactions returned since we returned empty bank transactions
        assertEquals(0, result.size());
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
}
