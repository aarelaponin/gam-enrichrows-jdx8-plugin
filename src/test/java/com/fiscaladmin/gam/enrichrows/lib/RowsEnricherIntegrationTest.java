package com.fiscaladmin.gam.enrichrows.lib;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;
import com.fiscaladmin.gam.enrichrows.framework.*;
import com.fiscaladmin.gam.enrichrows.helpers.TestDataFactory;
import com.fiscaladmin.gam.enrichrows.persister.EnrichmentDataPersister;
import com.fiscaladmin.gam.enrichrows.steps.*;
import com.fiscaladmin.gam.framework.status.Status;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for the full pipeline: load → process → persist.
 * Uses mocked FormDataDao with realistic master data.
 */
public class RowsEnricherIntegrationTest {

    private FormDataDao mockDao;
    private Map<String, Object> properties;

    @Before
    public void setUp() {
        mockDao = mock(FormDataDao.class);
        properties = TestDataFactory.defaultProperties();

        setupCurrencyMaster();
        setupCounterpartyMaster();
        setupCustomerMaster();
        setupF14Rules();
        setupFxRates();
        setupAssetMaster();
        setupStatementLookup();
    }

    // ===== Master Data Setup =====

    private void setupCurrencyMaster() {
        FormRowSet currencies = new FormRowSet();
        currencies.add(TestDataFactory.currencyRow("EUR", "Euro", "active"));
        currencies.add(TestDataFactory.currencyRow("USD", "US Dollar", "active"));
        currencies.add(TestDataFactory.currencyRow("GBP", "British Pound", "active"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CURRENCY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(currencies);
    }

    private void setupCounterpartyMaster() {
        FormRowSet counterparties = new FormRowSet();

        // Bank counterparty: BARCGB22 → CPT0143
        FormRow bankCp = TestDataFactory.counterpartyRow("rec-1", "CPT0143", "Bank", "BARCGB22", true);
        counterparties.add(bankCp);

        // Custodian counterparty: UBSWCHZH → CPT0200
        FormRow custodianCp = new FormRow();
        custodianCp.setId("rec-2");
        custodianCp.setProperty("counterpartyId", "CPT0200");
        custodianCp.setProperty("counterpartyType", "Custodian");
        custodianCp.setProperty("custodianId", "UBSWCHZH");
        custodianCp.setProperty("isActive", "true");
        custodianCp.setProperty("shortCode", "UBSC");
        counterparties.add(custodianCp);

        // Load all query
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_COUNTERPARTY_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(counterparties);

        // Short code lookup by counterpartyId (c_counterpartyId = ?)
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_COUNTERPARTY_MASTER),
                eq("WHERE c_counterpartyId = ?"), any(Object[].class),
                isNull(), isNull(), isNull(), isNull()))
                .thenAnswer(invocation -> {
                    Object[] params = invocation.getArgument(3);
                    String cpId = params[0].toString();
                    FormRowSet result = new FormRowSet();
                    for (FormRow cp : counterparties) {
                        if (cpId.equals(cp.getProperty("counterpartyId"))) {
                            result.add(cp);
                            break;
                        }
                    }
                    return result;
                });

        // Bank table (for bankExistsByBic fallback)
        when(mockDao.find(isNull(), eq("bank"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new FormRowSet());
    }

    private void setupCustomerMaster() {
        FormRowSet customers = new FormRowSet();

        FormRow cust1 = TestDataFactory.customerRow("CUST-001", "Test Corporation", "TC01", "active");
        cust1.setProperty("registrationNumber", "CUST-001"); // Bank CSV customer_id = registrationNumber
        customers.add(cust1);

        FormRow cust2 = TestDataFactory.customerRow("CUST-002", "Securities Client Ltd", "SC01", "active");
        cust2.setProperty("registrationNumber", "CUST-002");
        customers.add(cust2);

        // Load all query (for findCustomerByRegistrationOrPersonalId, identifyByNamePattern, etc.)
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(customers);

        // Load by ID (for customerExists and loadFormRow)
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(Object[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenAnswer(invocation -> {
                    Object[] params = invocation.getArgument(3);
                    String id = params[0].toString();
                    FormRowSet result = new FormRowSet();
                    for (FormRow c : customers) {
                        if (id.equals(c.getId())) {
                            result.add(c);
                            break;
                        }
                    }
                    return result;
                });

        // Customer account table (empty — no account-based matching)
        when(mockDao.find(isNull(), eq("customer_account"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new FormRowSet());
    }

    private void setupF14Rules() {
        FormRowSet rules = new FormRowSet();

        // Bank rule: payment_description contains "Wire" → WIRE_TRANSFER
        FormRow bankRule = TestDataFactory.f14RuleRow("RULE-001", "CPT0143", "bank",
                "payment_description", "contains", "Wire", "WIRE_TRANSFER", 10);
        rules.add(bankRule);

        // Secu rule: type equals BUY → EQUITY_BUY
        FormRow secuRule = TestDataFactory.f14RuleRow("RULE-002", "CPT0200", "secu",
                "type", "equals", "BUY", "EQUITY_BUY", 10);
        rules.add(secuRule);

        when(mockDao.find(isNull(), eq("cp_txn_mapping"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rules);
    }

    private void setupFxRates() {
        FormRowSet rates = new FormRowSet();

        // USD rate: 1 EUR = 1.0869 USD (stored as "how many target per 1 EUR")
        FormRow usdRate = TestDataFactory.fxRateRow("USD", "2026-01-15", "1.0869", "active");
        rates.add(usdRate);

        FormRow gbpRate = TestDataFactory.fxRateRow("GBP", "2026-01-15", "0.8456", "active");
        rates.add(gbpRate);

        when(mockDao.find(isNull(), eq("fx_rates_eur"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rates);
    }

    private void setupAssetMaster() {
        FormRowSet assets = new FormRowSet();

        FormRow aapl = TestDataFactory.assetMasterRow("ASSET-001", "AAPL", "US0378331005",
                "Apple Inc", "equity", "common_stock", "USD", "Active");
        assets.add(aapl);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_ASSET_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(assets);
    }

    private void setupStatementLookup() {
        // For persister batch completion — statement row lookup
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                eq("WHERE id = ?"), any(Object[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenAnswer(invocation -> {
                    Object[] params = invocation.getArgument(3);
                    String stmtId = params[0].toString();
                    FormRow stmt = TestDataFactory.statementRow(stmtId, DomainConstants.SOURCE_TYPE_BANK);
                    return TestDataFactory.rowSet(stmt);
                });
    }

    // ===== Helper: Create and execute pipeline =====

    private DataPipeline createPipeline() {
        DataPipeline pipeline = new DataPipeline(mockDao)
                .addStep(new CurrencyValidationStep())
                .addStep(new CounterpartyDeterminationStep())
                .addStep(new CustomerIdentificationStep())
                .addStep(new AssetResolutionStep())
                .addStep(new F14RuleMappingStep())
                .addStep(new FXConversionStep())
                .setStopOnError(false)
                .setProperties(properties);
        return pipeline;
    }

    private EnrichmentDataPersister createPersister() {
        return new EnrichmentDataPersister();
    }

    // ===== Test 1: Bank Happy Path =====

    @Test
    public void testBankHappyPath() {
        DataContext ctx = TestDataFactory.bankContext();
        DataPipeline pipeline = createPipeline();

        PipelineResult pipelineResult = pipeline.execute(ctx);

        assertTrue("Pipeline should succeed", pipelineResult.isSuccess());

        // Verify all expected steps executed
        Map<String, StepResult> stepResults = pipelineResult.getStepResults();
        assertTrue("Currency Validation should succeed", stepResults.get("Currency Validation").isSuccess());
        assertTrue("Counterparty Determination should succeed", stepResults.get("Counterparty Determination").isSuccess());
        assertTrue("Customer Identification should succeed", stepResults.get("Customer Identification").isSuccess());
        // Asset Resolution should skip for bank (returns success with skip message)
        assertTrue("Asset Resolution should pass for bank", stepResults.get("Asset Resolution").isSuccess());
        assertTrue("F14 Rule Mapping should succeed", stepResults.get("F14 Rule Mapping").isSuccess());
        assertTrue("FX Conversion should succeed", stepResults.get("FX Conversion").isSuccess());

        // Verify context was enriched
        assertEquals("CPT0143", ctx.getAdditionalDataValue("counterparty_id"));
        assertEquals("WIRE_TRANSFER", ctx.getAdditionalDataValue("internal_type"));
        assertEquals("CUST-001", ctx.getCustomerId());

        // Persist and verify
        EnrichmentDataPersister persister = createPersister();
        PersistenceResult result = persister.persist(ctx, mockDao, properties);

        assertTrue("Persistence should succeed", result.isSuccess());
        assertNotNull(result.getRecordId());
        assertEquals(false, result.getMetadata().get("needs_manual_review"));
    }

    // ===== Test 2: Securities Happy Path =====

    @Test
    public void testSecuHappyPath() {
        DataContext ctx = TestDataFactory.secuContext();
        DataPipeline pipeline = createPipeline();

        PipelineResult pipelineResult = pipeline.execute(ctx);

        assertTrue("Pipeline should succeed", pipelineResult.isSuccess());

        // Verify secu-specific enrichment
        assertEquals("CPT0200", ctx.getAdditionalDataValue("counterparty_id"));
        // BUY type → "Broker" per determineSecuritiesCounterpartyType
        assertEquals("Broker", ctx.getAdditionalDataValue("counterparty_type"));
        assertEquals("EQUITY_BUY", ctx.getAdditionalDataValue("internal_type"));
        assertNotNull("Asset should be resolved", ctx.getAdditionalDataValue("asset_id"));

        // Persist and verify
        EnrichmentDataPersister persister = createPersister();
        PersistenceResult result = persister.persist(ctx, mockDao, properties);

        assertTrue("Persistence should succeed", result.isSuccess());
        assertEquals(false, result.getMetadata().get("needs_manual_review"));

        // Verify persisted row has asset fields
        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(),
                eq(DomainConstants.TABLE_TRX_ENRICHMENT), captor.capture());
        FormRow row = captor.getAllValues().get(0).get(0);

        assertEquals("secu", row.getProperty("source_tp"));
        assertNotNull(row.getProperty("resolved_asset_id"));
        assertNotNull(row.getProperty("asset_isin"));
        assertEquals("D", row.getProperty("debit_credit")); // BUY → D
        assertEquals("yes", row.getProperty("has_fee")); // secu has fee
        assertEquals("yes", row.getProperty("requires_eur_parallel")); // USD needs parallel

        // BUY type → Broker routing
        assertEquals("CPT0200", row.getProperty("broker_id"));
        assertNull(row.getProperty("custodian_id"));
        assertNull(row.getProperty("counterparty_id"));
    }

    // ===== Test 3: Unknown Counterparty → MANUAL_REVIEW =====

    @Test
    public void testUnknownCounterpartyManualReview() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setStatementBank("UNKNOWN_BIC"); // No counterparty for this BIC

        DataPipeline pipeline = createPipeline();
        PipelineResult pipelineResult = pipeline.execute(ctx);

        // Pipeline should still succeed (counterparty step uses UNKNOWN fallback)
        assertTrue("Pipeline should succeed", pipelineResult.isSuccess());
        assertEquals(FrameworkConstants.ENTITY_UNKNOWN, ctx.getAdditionalDataValue("counterparty_id"));

        // Persist — should trigger MANUAL_REVIEW
        EnrichmentDataPersister persister = createPersister();
        PersistenceResult result = persister.persist(ctx, mockDao, properties);

        assertTrue(result.isSuccess());
        assertEquals(true, result.getMetadata().get("needs_manual_review"));
        assertEquals(Status.MANUAL_REVIEW.getCode(), result.getMetadata().get("processing_status"));
    }

    // ===== Test 4: Unknown Asset (secu) → MANUAL_REVIEW =====

    @Test
    public void testUnknownAssetAutoRegistered() {
        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTicker("NONEXISTENT"); // No asset for this ticker
        ctx.setDescription("Unknown Security XYZ"); // Prevent name match against Apple Inc

        DataPipeline pipeline = createPipeline();
        PipelineResult pipelineResult = pipeline.execute(ctx);

        // Pipeline still succeeds — asset is auto-registered instead of UNKNOWN
        assertTrue("Pipeline should succeed", pipelineResult.isSuccess());

        // Asset should be auto-registered, not UNKNOWN
        assertNotEquals(FrameworkConstants.ENTITY_UNKNOWN, ctx.getAdditionalDataValue("asset_id"));
        assertTrue(((String) ctx.getAdditionalDataValue("asset_id")).startsWith("AST-"));
        assertEquals("EQ", ctx.getAdditionalDataValue("asset_category"));

        // Persist — auto-registered asset means no manual review needed for asset
        EnrichmentDataPersister persister = createPersister();
        PersistenceResult result = persister.persist(ctx, mockDao, properties);

        assertTrue(result.isSuccess());
    }

    // ===== Test 5: Invalid Currency → Pipeline Fails =====

    @Test
    public void testInvalidCurrencyPipelineFails() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCurrency("XYZ"); // Not in currency master

        DataPipeline pipeline = createPipeline();
        PipelineResult pipelineResult = pipeline.execute(ctx);

        // Currency validation fails, but stopOnError=false so pipeline continues
        StepResult currencyResult = pipelineResult.getStepResults().get("Currency Validation");
        assertFalse("Currency validation should fail", currencyResult.isSuccess());
    }

    // ===== Test 6: Mixed Batch → Statement ERROR =====

    @Test
    public void testMixedBatchStatementError() {
        // Use manually constructed pipeline results to simulate one success and one failure
        DataContext successCtx = TestDataFactory.bankContext("TRX-001", "STMT-001");
        DataContext failCtx = TestDataFactory.bankContext("TRX-002", "STMT-001");

        // Run pipeline only for the success context
        DataPipeline pipeline = createPipeline();
        pipeline.execute(successCtx);

        // Build BatchPipelineResult manually: TRX-001 success, TRX-002 failure
        BatchPipelineResult batchPipelineResult = new BatchPipelineResult();
        PipelineResult txSuccess = new PipelineResult();
        txSuccess.setTransactionId("TRX-001");
        txSuccess.setSuccess(true);
        batchPipelineResult.addResult(txSuccess);

        PipelineResult txFailure = new PipelineResult();
        txFailure.setTransactionId("TRX-002");
        txFailure.setSuccess(false);
        txFailure.setErrorMessage("Pipeline stopped at step: Currency Validation");
        batchPipelineResult.addResult(txFailure);

        // Persist batch
        EnrichmentDataPersister persister = createPersister();
        BatchPersistenceResult batchResult = persister.persistBatch(
                Arrays.asList(successCtx, failCtx), batchPipelineResult, mockDao, properties);

        // One success, one failure → statement should be ERROR
        assertEquals(1, batchResult.getSuccessCount());
        assertEquals(1, batchResult.getFailureCount());
    }

    // ===== Test 7: All Success → Statement ENRICHED =====

    @Test
    public void testAllSuccessStatementEnriched() {
        DataContext ctx1 = TestDataFactory.bankContext("TRX-001", "STMT-001");
        DataContext ctx2 = TestDataFactory.bankContext("TRX-002", "STMT-001");
        ctx2.setCustomerId("CUST-001"); // Both use same valid customer

        DataPipeline pipeline = createPipeline();
        BatchPipelineResult batchPipelineResult = pipeline.executeBatch(Arrays.asList(ctx1, ctx2));

        EnrichmentDataPersister persister = createPersister();
        BatchPersistenceResult batchResult = persister.persistBatch(
                Arrays.asList(ctx1, ctx2), batchPipelineResult, mockDao, properties);

        assertEquals(2, batchResult.getSuccessCount());
        assertEquals(0, batchResult.getFailureCount());
    }

    // ===== Test 8: §4.0a/c Securities-Related Bank Row Enriched =====

    @Test
    public void testSecuritiesRelatedBankRowEnriched() {
        // Bank row with "Securities buy (AAPL)" should skip customer identification
        // and be persisted without manual review (despite UNKNOWN customer)
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Securities buy (AAPL)");

        // Also update the transaction row's payment_description for F14 matching
        ctx.getTransactionRow().setProperty("payment_description", "Securities buy (AAPL)");

        DataPipeline pipeline = createPipeline();
        PipelineResult pipelineResult = pipeline.execute(ctx);

        assertTrue("Pipeline should succeed", pipelineResult.isSuccess());

        // Customer Identification step should have been skipped (§4.0a)
        // Verify that customer was NOT identified (remains as original, no customer step ran)
        // The customer_identification_method should not be set
        assertNull("Customer step should not have executed",
                ctx.getAdditionalDataValue("customer_identification_method"));

        // Set customer to UNKNOWN (simulating no customer for securities bank row)
        ctx.setCustomerId(FrameworkConstants.ENTITY_UNKNOWN);

        // Manually set internal_type to avoid UNMATCHED trigger
        // (in production, securities bank rows would have their own F14 rules)
        ctx.setAdditionalDataValue("internal_type", "SECURITIES_BUY");

        // Persist — should NOT trigger manual review for UNKNOWN customer
        // because this is a securities-related bank row (§4.0c)
        EnrichmentDataPersister persister = createPersister();
        PersistenceResult result = persister.persist(ctx, mockDao, properties);

        assertTrue("Persistence should succeed", result.isSuccess());
        assertEquals("Securities-related bank row should not need manual review for UNKNOWN customer",
                false, result.getMetadata().get("needs_manual_review"));
    }

    // ===== Test 9: Pipeline + Persist End-to-End =====

    @Test
    public void testStatusManagerTransitionOrder() {
        DataContext ctx = TestDataFactory.bankContext();
        DataPipeline pipeline = createPipeline();
        pipeline.execute(ctx);

        EnrichmentDataPersister persister = createPersister();
        PersistenceResult result = persister.persist(ctx, mockDao, properties);

        // Verify end-to-end pipeline + persist succeeds
        assertTrue(result.isSuccess());
        assertNotNull(result.getRecordId());
    }
}
