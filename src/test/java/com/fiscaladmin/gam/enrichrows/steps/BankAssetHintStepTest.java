package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.framework.DataContext;
import com.fiscaladmin.gam.enrichrows.framework.StepResult;
import com.fiscaladmin.gam.enrichrows.helpers.TestDataFactory;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Arrays;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class BankAssetHintStepTest {

    private BankAssetHintStep step;
    private FormDataDao mockDao;

    @Before
    public void setUp() {
        step = new BankAssetHintStep();
        mockDao = Mockito.mock(FormDataDao.class);
    }

    // ===== Step name =====

    @Test
    public void testStepName() {
        assertEquals("Bank Asset Hint", step.getStepName());
    }

    // ===== shouldExecute guards =====

    @Test
    public void testSkipSecuTransactions() {
        DataContext ctx = TestDataFactory.secuContext();
        TestDataFactory.withF14(ctx, "DIV_INCOME", "RULE-DIV");
        assertFalse(step.shouldExecute(ctx));
    }

    @Test
    public void testSkipBankNonIncomeType() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withF14(ctx, "WIRE_TRANSFER", "RULE-001");
        assertFalse(step.shouldExecute(ctx));
    }

    @Test
    public void testSkipBankNoInternalType() {
        DataContext ctx = TestDataFactory.bankContext();
        // No F14 mapping applied — no internal_type
        assertFalse(step.shouldExecute(ctx));
    }

    @Test
    public void testExecuteForBankDivIncome() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withF14(ctx, "DIV_INCOME", "RULE-DIV");
        assertTrue(step.shouldExecute(ctx));
    }

    @Test
    public void testExecuteForBankIntIncome() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withF14(ctx, "INT_INCOME", "RULE-INT");
        assertTrue(step.shouldExecute(ctx));
    }

    @Test
    public void testExecuteForBankDivTax() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withF14(ctx, "DIV_TAX", "RULE-TAX");
        assertTrue(step.shouldExecute(ctx));
    }

    @Test
    public void testExecuteForBankIncomeTax() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withF14(ctx, "INCOME_TAX", "RULE-TAX");
        assertTrue(step.shouldExecute(ctx));
    }

    @Test
    public void testAllEligibleInternalTypesAccepted() {
        for (String type : Arrays.asList("DIV_INCOME", "INT_INCOME", "DIV_TAX", "INCOME_TAX")) {
            DataContext ctx = TestDataFactory.bankContext();
            TestDataFactory.withF14(ctx, type, "RULE-TEST");
            assertTrue("Should accept type: " + type, step.shouldExecute(ctx));
        }
    }

    @Test
    public void testSkipAlreadyResolved() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withF14(ctx, "DIV_INCOME", "RULE-DIV");
        ctx.setAdditionalDataValue("asset_id", "ASSET-001");
        assertFalse(step.shouldExecute(ctx));
    }

    // ===== performStep tests =====

    @Test
    public void testTickerExtractedAndMatched() {
        FormRowSet assets = TestDataFactory.rowSet(
                TestDataFactory.assetMasterRow("AST000301", "SBLK", "MHY7877Q1038",
                        "Star Bulk Carriers Corp", "equity", "common_stock", "USD", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.bankDividendContext("SBLK");
        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("AST000301", ctx.getAdditionalDataValue("asset_id"));
        assertEquals("MHY7877Q1038", ctx.getAdditionalDataValue("asset_isin"));
        assertEquals("equity", ctx.getAdditionalDataValue("asset_category"));
        assertEquals("common_stock", ctx.getAdditionalDataValue("asset_class"));
        assertEquals("USD", ctx.getAdditionalDataValue("asset_base_currency"));
        assertEquals("yes", ctx.getAdditionalDataValue("bank_asset_hint"));
        assertEquals("SBLK", ctx.getAdditionalDataValue("bank_asset_hint_ticker"));
    }

    @Test
    public void testTickerExtractedNoMatch() {
        FormRowSet assets = TestDataFactory.rowSet(
                TestDataFactory.assetMasterRow("AST000301", "SBLK", "MHY7877Q1038",
                        "Star Bulk Carriers Corp", "equity", "common_stock", "USD", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.bankDividendContext("ZZZZ");
        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertNull(ctx.getAdditionalDataValue("asset_id"));
        // No exception created
        verify(mockDao, never()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_EXCEPTION_QUEUE), any(FormRowSet.class));
    }

    @Test
    public void testNoTickerInDescription() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withF14(ctx, "DIV_INCOME", "RULE-DIV");
        ctx.setPaymentDescription("Hüpoteeklaen AS Dividendid");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertNull(ctx.getAdditionalDataValue("asset_id"));
    }

    @Test
    public void testInactiveAssetSkipped() {
        FormRowSet assets = TestDataFactory.rowSet(
                TestDataFactory.assetMasterRow("AST000301", "SBLK", "MHY7877Q1038",
                        "Star Bulk Carriers Corp", "equity", "common_stock", "USD", "suspended")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.bankDividendContext("SBLK");
        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertNull(ctx.getAdditionalDataValue("asset_id"));
        // No exception created for inactive
        verify(mockDao, never()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_EXCEPTION_QUEUE), any(FormRowSet.class));
    }

    @Test
    public void testCurrencyMismatchFlagSet() {
        FormRowSet assets = TestDataFactory.rowSet(
                TestDataFactory.assetMasterRow("AST000301", "SBLK", "MHY7877Q1038",
                        "Star Bulk Carriers Corp", "equity", "common_stock", "USD", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.bankDividendContext("SBLK");
        ctx.setCurrency("EUR"); // Different from asset's USD
        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("yes", ctx.getAdditionalDataValue("currency_mismatch_flag"));
    }

    @Test
    public void testCurrencyMatchFlagSet() {
        FormRowSet assets = TestDataFactory.rowSet(
                TestDataFactory.assetMasterRow("AST000301", "SBLK", "MHY7877Q1038",
                        "Star Bulk Carriers Corp", "equity", "common_stock", "USD", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.bankDividendContext("SBLK");
        ctx.setCurrency("USD"); // Same as asset's USD
        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("no", ctx.getAdditionalDataValue("currency_mismatch_flag"));
    }

    @Test
    public void testAuditLogCreated() {
        FormRowSet assets = TestDataFactory.rowSet(
                TestDataFactory.assetMasterRow("AST000301", "SBLK", "MHY7877Q1038",
                        "Star Bulk Carriers Corp", "equity", "common_stock", "USD", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.bankDividendContext("SBLK");
        step.execute(ctx, mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_AUDIT_LOG), captor.capture());
        FormRow auditRow = captor.getValue().get(0);
        assertEquals(DomainConstants.AUDIT_BANK_ASSET_HINT_RESOLVED, auditRow.getProperty("action"));
        assertTrue(auditRow.getProperty("details").contains("SBLK"));
    }

    @Test
    public void testIdempotency() {
        FormRowSet assets = TestDataFactory.rowSet(
                TestDataFactory.assetMasterRow("AST000301", "SBLK", "MHY7877Q1038",
                        "Star Bulk Carriers Corp", "equity", "common_stock", "USD", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.bankDividendContext("SBLK");

        // First execution — resolves asset
        step.execute(ctx, mockDao);
        assertEquals("AST000301", ctx.getAdditionalDataValue("asset_id"));

        // Second execution — shouldExecute returns false
        assertFalse(step.shouldExecute(ctx));
    }

    @Test
    public void testNoAutoRegistration() {
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new FormRowSet());

        DataContext ctx = TestDataFactory.bankDividendContext("UNKNOWN_TICKER");
        step.execute(ctx, mockDao);

        assertNull(ctx.getAdditionalDataValue("asset_id"));
        // Verify NO save to asset_master (no auto-registration)
        verify(mockDao, never()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER), any(FormRowSet.class));
    }

    @Test
    public void testNoExceptionCreated() {
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new FormRowSet());

        DataContext ctx = TestDataFactory.bankDividendContext("UNKNOWN_TICKER");
        step.execute(ctx, mockDao);

        // Verify NO save to exception_queue
        verify(mockDao, never()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_EXCEPTION_QUEUE), any(FormRowSet.class));
    }
}
