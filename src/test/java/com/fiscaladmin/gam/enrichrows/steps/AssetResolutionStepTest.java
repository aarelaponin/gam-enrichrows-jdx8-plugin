package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;
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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AssetResolutionStepTest {

    private AssetResolutionStep step;
    private FormDataDao mockDao;

    @Before
    public void setUp() {
        step = new AssetResolutionStep();
        mockDao = Mockito.mock(FormDataDao.class);
    }

    @Test
    public void testStepName() {
        assertEquals("Asset Resolution", step.getStepName());
    }

    @Test
    public void testSkipBankTransactions() {
        DataContext ctx = TestDataFactory.bankContext();
        assertFalse(step.shouldExecute(ctx));
    }

    @Test
    public void testExecuteForSecu() {
        DataContext ctx = TestDataFactory.secuContext();
        assertTrue(step.shouldExecute(ctx));
    }

    @Test
    public void testTickerMatch() {
        FormRowSet assets = TestDataFactory.rowSet(
                TestDataFactory.assetMasterRow("ASSET-001", "AAPL", "US0378331005",
                        "Apple Inc.", "equity", "common_stock", "USD", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.secuContext();
        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("ASSET-001", ctx.getAdditionalDataValue("asset_id"));
        assertEquals("US0378331005", ctx.getAdditionalDataValue("asset_isin"));
        assertEquals("equity", ctx.getAdditionalDataValue("asset_category"));
        assertEquals("common_stock", ctx.getAdditionalDataValue("asset_class"));
        assertEquals("USD", ctx.getAdditionalDataValue("asset_base_currency"));
        assertEquals(DomainConstants.PROCESSING_STATUS_ASSET_RESOLVED, ctx.getProcessingStatus());
    }

    @Test
    public void testIsinFallback() {
        FormRowSet assets = TestDataFactory.rowSet(
                TestDataFactory.assetMasterRow("ASSET-002", "MSFT", "US5949181045",
                        "Microsoft Corp", "equity", "common_stock", "USD", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTicker("US5949181045"); // Pass ISIN as ticker

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("ASSET-002", ctx.getAdditionalDataValue("asset_id"));
    }

    @Test
    public void testInactiveAsset() {
        FormRowSet assets = TestDataFactory.rowSet(
                TestDataFactory.assetMasterRow("ASSET-003", "AAPL", "US0378331005",
                        "Apple Inc.", "equity", "common_stock", "USD", "suspended")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.secuContext();
        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess()); // Continues with actual asset data for reference
        assertEquals("ASSET-003", ctx.getAdditionalDataValue("asset_id"));
        assertEquals("US0378331005", ctx.getAdditionalDataValue("asset_isin"));
        assertEquals("equity", ctx.getAdditionalDataValue("asset_category"));
        assertEquals("common_stock", ctx.getAdditionalDataValue("asset_class"));
        assertEquals("USD", ctx.getAdditionalDataValue("asset_base_currency"));
    }

    @Test
    public void testMissingTicker() {
        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTicker(null);

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess()); // Continues with UNKNOWN
        assertEquals(FrameworkConstants.ENTITY_UNKNOWN, ctx.getAdditionalDataValue("asset_id"));
    }

    @Test
    public void testAutoRegistrationForUnknownTicker() {
        FormRowSet assets = TestDataFactory.rowSet(
                TestDataFactory.assetMasterRow("ASSET-001", "MSFT", "US5949181045",
                        "Microsoft Corp", "equity", "common_stock", "USD", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTicker("UNKNOWN_TICKER");
        ctx.setDescription("Something unrelated");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertNotEquals(FrameworkConstants.ENTITY_UNKNOWN, ctx.getAdditionalDataValue("asset_id"));
        assertTrue(((String) ctx.getAdditionalDataValue("asset_id")).startsWith("AST-"));
        assertEquals("EQ", ctx.getAdditionalDataValue("asset_category"));

        // Verify asset was saved to asset_master
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER), any(FormRowSet.class));
    }

    @Test
    public void testCurrencyMismatch() {
        FormRowSet assets = TestDataFactory.rowSet(
                TestDataFactory.assetMasterRow("ASSET-001", "AAPL", "US0378331005",
                        "Apple Inc.", "equity", "common_stock", "USD", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setCurrency("EUR"); // Different from asset's USD

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("yes", ctx.getAdditionalDataValue("currency_mismatch_flag"));
    }

    @Test
    public void testAutoRegistrationSavesToAssetMaster() {
        FormRowSet assets = TestDataFactory.rowSet(
                TestDataFactory.assetMasterRow("ASSET-001", "MSFT", "US5949181045",
                        "Microsoft Corp", "equity", "common_stock", "USD", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTicker("UNKNOWN_TICKER");
        ctx.setDescription("Something unrelated");

        step.execute(ctx, mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER), captor.capture());
        FormRow savedRow = captor.getValue().get(0);

        assertEquals("UNKNOWN_TICKER", savedRow.getProperty("ticker"));
        assertEquals("EQ", savedRow.getProperty("categoryCode"));
        assertEquals("Active", savedRow.getProperty("tradingStatus"));
        assertEquals("USD", savedRow.getProperty("tradingCurrency"));
    }

    @Test
    public void testAutoRegistrationWithEmptyAssetMaster() {
        FormRowSet assets = new FormRowSet(); // empty asset master
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setAmount("200000.00");
        ctx.setTicker("NONEXISTENT");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertNotEquals(FrameworkConstants.ENTITY_UNKNOWN, ctx.getAdditionalDataValue("asset_id"));
        assertTrue(((String) ctx.getAdditionalDataValue("asset_id")).startsWith("AST-"));

        // Verify auto-registration save
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER), any(FormRowSet.class));
    }

    @Test
    public void testNoCurrencyMismatch() {
        FormRowSet assets = TestDataFactory.rowSet(
                TestDataFactory.assetMasterRow("ASSET-001", "AAPL", "US0378331005",
                        "Apple Inc.", "equity", "common_stock", "USD", "active")
        );
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setCurrency("USD"); // Same as asset's USD

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("no", ctx.getAdditionalDataValue("currency_mismatch_flag"));
    }

    @Test
    public void testAutoRegistrationBondCategory() {
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new FormRowSet());

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTicker("BIGBANK");
        ctx.setDescription("Bigbank 8% allutatud võlakiri 8% 16.02.2033");

        step.execute(ctx, mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER), captor.capture());
        FormRow savedRow = captor.getValue().get(0);

        assertEquals("BD", savedRow.getProperty("categoryCode"));
        assertEquals("bond", savedRow.getProperty("asset_class"));
        assertEquals("OTC", savedRow.getProperty("primaryExchange"));
        assertEquals("2033-02-16", savedRow.getProperty("maturityDate"));
        assertEquals("8", savedRow.getProperty("couponRate"));
    }

    @Test
    public void testAutoRegistrationEquityCategory() {
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new FormRowSet());

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTicker("CRWD");
        ctx.setDescription("CROWDSTRIKE HOLDINGS INC - A");

        step.execute(ctx, mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER), captor.capture());
        FormRow savedRow = captor.getValue().get(0);

        assertEquals("EQ", savedRow.getProperty("categoryCode"));
        assertEquals("equity", savedRow.getProperty("asset_class"));
        assertNull(savedRow.getProperty("maturityDate"));
        assertNull(savedRow.getProperty("couponRate"));
    }

    @Test
    public void testAutoRegistrationBondWithEnglishKeyword() {
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new FormRowSet());

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTicker("GOVBOND");
        ctx.setDescription("Government bond 3.5% 01.06.2030");

        step.execute(ctx, mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER), captor.capture());
        FormRow savedRow = captor.getValue().get(0);

        assertEquals("BD", savedRow.getProperty("categoryCode"));
        assertEquals("2030-06-01", savedRow.getProperty("maturityDate"));
        assertEquals("3.5", savedRow.getProperty("couponRate"));
    }

    @Test
    public void testAutoRegistrationFallbackToUnknownOnDbError() {
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new FormRowSet());

        Mockito.doThrow(new RuntimeException("DB error"))
                .when(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER), any(FormRowSet.class));

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTicker("FAIL_TICKER");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals(FrameworkConstants.ENTITY_UNKNOWN, ctx.getAdditionalDataValue("asset_id"));

        // Verify exception queue entry was created
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_EXCEPTION_QUEUE), any(FormRowSet.class));
    }

    @Test
    public void testAutoRegistrationCreatesAuditLog() {
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new FormRowSet());

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTicker("NEWTICKER");
        ctx.setDescription("New equity");

        step.execute(ctx, mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, Mockito.atLeastOnce()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_AUDIT_LOG), captor.capture());

        boolean foundAutoRegisterAudit = false;
        for (FormRowSet rowSet : captor.getAllValues()) {
            FormRow row = rowSet.get(0);
            if ("ASSET_AUTO_REGISTERED".equals(row.getProperty("action"))) {
                foundAutoRegisterAudit = true;
                assertTrue(row.getProperty("details").contains("NEWTICKER"));
            }
        }
        assertTrue("Expected ASSET_AUTO_REGISTERED audit log entry", foundAutoRegisterAudit);
    }

    @Test
    public void testExtractMaturityDateMultipleDates() {
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new FormRowSet());

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTicker("MULTIBOND");
        ctx.setDescription("Bond 01.01.2025 maturity 16.02.2033");

        step.execute(ctx, mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER), captor.capture());
        FormRow savedRow = captor.getValue().get(0);

        // Should take the last date
        assertEquals("2033-02-16", savedRow.getProperty("maturityDate"));
    }

    @Test
    public void testExtractCouponRate() {
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new FormRowSet());

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTicker("BB85");
        ctx.setDescription("Bigbank 8.5% bond");

        step.execute(ctx, mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER), captor.capture());
        FormRow savedRow = captor.getValue().get(0);

        assertEquals("8.5", savedRow.getProperty("couponRate"));
    }

    @Test
    public void testAutoRegistrationContinuesEnrichment() {
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new FormRowSet());

        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTicker("NEWEQ");
        ctx.setDescription("New Equity Corp");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals(DomainConstants.PROCESSING_STATUS_ASSET_RESOLVED, ctx.getProcessingStatus());
        assertEquals("EQ", ctx.getAdditionalDataValue("asset_category"));
        assertEquals("equity", ctx.getAdditionalDataValue("asset_class"));
        assertTrue(((String) ctx.getAdditionalDataValue("asset_id")).startsWith("AST-"));
    }

    // =========================================================================
    // §9b Idempotency guard tests
    // =========================================================================

    @Test
    public void testIdempotency_skipsResolved() {
        DataContext ctx = TestDataFactory.secuContext();
        TestDataFactory.withAsset(ctx, "ASSET-001", "US0378331005");
        assertFalse("Should skip when asset already resolved", step.shouldExecute(ctx));
    }

    @Test
    public void testIdempotency_skipsEvenUnknown() {
        DataContext ctx = TestDataFactory.secuContext();
        TestDataFactory.withAsset(ctx, "UNKNOWN", null);
        assertFalse("Should skip even when asset is UNKNOWN (no sentinel)", step.shouldExecute(ctx));
    }
}
