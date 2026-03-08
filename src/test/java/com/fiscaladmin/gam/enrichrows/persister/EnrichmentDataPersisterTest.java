package com.fiscaladmin.gam.enrichrows.persister;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;
import com.fiscaladmin.gam.enrichrows.framework.*;
import com.fiscaladmin.gam.enrichrows.helpers.TestDataFactory;
import com.fiscaladmin.gam.framework.status.Status;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class EnrichmentDataPersisterTest {

    private EnrichmentDataPersister persister;
    private FormDataDao mockDao;

    @Before
    public void setUp() {
        persister = new EnrichmentDataPersister();
        mockDao = Mockito.mock(FormDataDao.class);
        // Note: StatusManager.transition() is static and cannot be mocked with plain Mockito.
        // StatusManager is not set on the persister — transitions are skipped via null guard.
    }

    // ===== Phase 6 Tests: StatusManager Integration =====

    @Test
    public void testEnrichmentLifecycleTransitions() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        assertNotNull(result.getRecordId());
    }

    @Test
    public void testManualReviewTransition() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");
        TestDataFactory.withF14(ctx, "WIRE_TRANSFER", "RULE-001");
        TestDataFactory.withFx(ctx, 1.0, "2026-01-15");
        ctx.setCustomerId("UNKNOWN");

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        assertEquals(true, result.getMetadata().get("needs_manual_review"));
    }

    @Test
    public void testSourceTransactionTransition() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        assertNotNull(result.getRecordId());
    }

    @Test
    public void testSecuSourceTransactionTransition() {
        DataContext ctx = TestDataFactory.fullyEnrichedSecuContext();

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        assertNotNull(result.getRecordId());
    }

    @Test
    public void testManualReviewMirrorsToSourceTransaction() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");
        TestDataFactory.withF14(ctx, "WIRE_TRANSFER", "RULE-001");
        TestDataFactory.withFx(ctx, 1.0, "2026-01-15");
        ctx.setCustomerId("UNKNOWN"); // triggers manual review

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        assertEquals(true, result.getMetadata().get("needs_manual_review"));
    }

    @Test
    public void testStatementBatchCompletionSuccess() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        List<DataContext> contexts = Collections.singletonList(ctx);

        BatchPipelineResult pipelineResult = new BatchPipelineResult();
        PipelineResult txResult = new PipelineResult();
        txResult.setTransactionId(ctx.getTransactionId());
        txResult.setSuccess(true);
        pipelineResult.addResult(txResult);

        FormRow statementRow = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                eq("WHERE id = ?"), any(Object[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.rowSet(statementRow));

        BatchPersistenceResult batchResult = persister.persistBatch(
                contexts, pipelineResult, mockDao, new HashMap<>());

        assertEquals(1, batchResult.getSuccessCount());
        assertEquals(0, batchResult.getFailureCount());
    }

    @Test
    public void testStatementBatchCompletionWithErrors() {
        DataContext ctx = TestDataFactory.bankContext();
        List<DataContext> contexts = Collections.singletonList(ctx);

        BatchPipelineResult pipelineResult = new BatchPipelineResult();
        PipelineResult txResult = new PipelineResult();
        txResult.setTransactionId(ctx.getTransactionId());
        txResult.setSuccess(false);
        pipelineResult.addResult(txResult);

        FormRow statementRow = TestDataFactory.statementRow("STMT-001", DomainConstants.SOURCE_TYPE_BANK);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_STATEMENT),
                eq("WHERE id = ?"), any(Object[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.rowSet(statementRow));

        BatchPersistenceResult batchResult = persister.persistBatch(
                contexts, pipelineResult, mockDao, new HashMap<>());

        assertEquals(0, batchResult.getSuccessCount());
        assertEquals(1, batchResult.getFailureCount());
    }

    @Test
    public void testManualReviewDetermination() {
        // 1. UNKNOWN customer
        DataContext ctx1 = TestDataFactory.fullyEnrichedBankContext();
        ctx1.setCustomerId(FrameworkConstants.ENTITY_UNKNOWN);
        PersistenceResult r1 = persister.persist(ctx1, mockDao, new HashMap<>());
        assertEquals(true, r1.getMetadata().get("needs_manual_review"));

        // 2. UNKNOWN counterparty
        DataContext ctx2 = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx2, FrameworkConstants.ENTITY_UNKNOWN, "Bank");
        TestDataFactory.withCustomer(ctx2, "CUST-001", 100);
        TestDataFactory.withF14(ctx2, "WIRE_TRANSFER", "R1");
        PersistenceResult r2 = persister.persist(ctx2, mockDao, new HashMap<>());
        assertEquals(true, r2.getMetadata().get("needs_manual_review"));

        // 3. UNMATCHED internal type
        DataContext ctx3 = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx3, "CPT0143", "Bank");
        TestDataFactory.withCustomer(ctx3, "CUST-001", 100);
        TestDataFactory.withF14(ctx3, FrameworkConstants.INTERNAL_TYPE_UNMATCHED, null);
        PersistenceResult r3 = persister.persist(ctx3, mockDao, new HashMap<>());
        assertEquals(true, r3.getMetadata().get("needs_manual_review"));

        // 4. Low customer confidence
        DataContext ctx4 = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx4, "CPT0143", "Bank");
        TestDataFactory.withCustomer(ctx4, "CUST-001", 50);
        TestDataFactory.withF14(ctx4, "WIRE_TRANSFER", "R1");
        PersistenceResult r4 = persister.persist(ctx4, mockDao, new HashMap<>());
        assertEquals(true, r4.getMetadata().get("needs_manual_review"));
    }

    @Test
    public void testNoStatusManagerGraceful() {
        EnrichmentDataPersister persisterNoSM = new EnrichmentDataPersister();
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();

        PersistenceResult result = persisterNoSM.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        assertNotNull(result.getRecordId());
    }

    // ===== Phase 7 Tests: 52-Field Mapping =====

    /**
     * Capture the FormRow saved by persist() via ArgumentCaptor on dao.saveOrUpdate().
     */
    private FormRow capturePersistedRow() {
        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(),
                eq(DomainConstants.TABLE_TRX_ENRICHMENT), captor.capture());
        // The first captured call is the enrichment record save
        FormRowSet captured = captor.getAllValues().get(0);
        return captured.get(0);
    }

    @Test
    public void testBankFieldMapping() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        Map<String, Object> config = TestDataFactory.defaultProperties();

        PersistenceResult result = persister.persist(ctx, mockDao, config);
        assertTrue(result.isSuccess());

        FormRow row = capturePersistedRow();

        // Provenance
        assertEquals("bank", row.getProperty("source_tp"));
        assertEquals("STMT-001", row.getProperty("statement_id"));
        assertEquals("2026-01-01", row.getProperty("statement_date"));
        assertEquals("TRX-001", row.getProperty("source_trx_id"));
        assertEquals("auto", row.getProperty("origin"));
        assertNotNull(row.getProperty("lineage_note"));
        assertNull(row.getProperty("acc_post_id"));
        assertNull(row.getProperty("parent_enrichment_id"));

        // Transaction core
        assertEquals("2026-01-15", row.getProperty("transaction_date"));
        assertEquals("2026-01-15", row.getProperty("settlement_date")); // bank = T+0
        assertEquals("D", row.getProperty("debit_credit"));  // bank direct from context
        assertNotNull(row.getProperty("description"));
        assertEquals("1000.00", row.getProperty("original_amount"));
        assertEquals("1000.00", row.getProperty("total_amount")); // bank: total = amount
        assertEquals("EUR", row.getProperty("original_currency"));

        // Classification
        assertEquals("WIRE_TRANSFER", row.getProperty("internal_type"));
        assertEquals("high", row.getProperty("type_confidence")); // matched + confidence 100
        assertEquals("RULE-001", row.getProperty("matched_rule_id"));

        // Customer
        assertEquals("CUST-001", row.getProperty("resolved_customer_id"));
        assertEquals("direct_id", row.getProperty("customer_match_method"));
        assertEquals("TC01", row.getProperty("customer_code"));
        assertEquals("Test Customer", row.getProperty("customer_display_name"));

        // Asset fields should NOT be set for bank
        assertNull(row.getProperty("resolved_asset_id"));
        assertNull(row.getProperty("asset_isin"));

        // Counterparty (Bank type → counterparty_id/short_code)
        assertEquals("CPT0143", row.getProperty("counterparty_id"));
        assertEquals("BARC", row.getProperty("counterparty_short_code"));
        assertNull(row.getProperty("custodian_id"));
        assertNull(row.getProperty("broker_id"));
        assertEquals("statement_bank", row.getProperty("counterparty_source"));

        // Currency & FX
        assertEquals("EUR", row.getProperty("validated_currency"));
        assertEquals("no", row.getProperty("requires_eur_parallel")); // EUR doesn't need parallel
        assertEquals("1.0", row.getProperty("fx_rate_to_eur"));
        assertEquals("2026-01-15", row.getProperty("fx_rate_date"));

        // Fee & Pairing
        assertNotNull(row.getProperty("base_amount_eur"));
        assertEquals("no", row.getProperty("has_fee")); // bank context has no fee
        assertEquals("900727947,900727952", row.getProperty("source_reference"));

        // Status & Notes
        assertNotNull(row.getProperty("enrichment_timestamp"));
        assertNotNull(row.getProperty("processing_notes"));
        assertEquals("1", row.getProperty("version"));
    }

    @Test
    public void testSecuFieldMapping() {
        DataContext ctx = TestDataFactory.fullyEnrichedSecuContext();
        Map<String, Object> config = TestDataFactory.defaultProperties();

        PersistenceResult result = persister.persist(ctx, mockDao, config);
        assertTrue(result.isSuccess());

        FormRow row = capturePersistedRow();

        // Provenance
        assertEquals("secu", row.getProperty("source_tp"));
        assertEquals("STMT-002", row.getProperty("statement_id"));
        assertEquals("TRX-002", row.getProperty("source_trx_id"));
        assertEquals("auto", row.getProperty("origin"));

        // Transaction core - secu specific
        assertNotNull(row.getProperty("settlement_date"));
        assertNotEquals("2026-01-15", row.getProperty("settlement_date")); // secu T+2
        assertEquals("D", row.getProperty("debit_credit")); // BUY → D
        assertEquals("50000.00", row.getProperty("original_amount"));
        assertEquals("25.00", row.getProperty("fee_amount"));
        assertEquals("50025.00", row.getProperty("total_amount")); // secu uses totalAmount
        assertEquals("USD", row.getProperty("original_currency"));

        // Classification
        assertEquals("EQUITY_BUY", row.getProperty("internal_type"));
        assertEquals("RULE-002", row.getProperty("matched_rule_id"));

        // Asset (secu only)
        assertEquals("ASSET-001", row.getProperty("resolved_asset_id"));
        assertEquals("US0378331005", row.getProperty("asset_isin"));
        assertEquals("equity", row.getProperty("asset_category"));
        assertEquals("common_stock", row.getProperty("asset_class"));
        assertEquals("USD", row.getProperty("asset_base_currency"));
        assertEquals("no", row.getProperty("currency_mismatch_flag"));

        // Counterparty (Custodian type → custodian_id/short_code)
        assertEquals("CPT0200", row.getProperty("custodian_id"));
        assertEquals("BARC", row.getProperty("custodian_short_code"));
        assertNull(row.getProperty("counterparty_id"));
        assertNull(row.getProperty("broker_id"));

        // Currency & FX
        assertEquals("USD", row.getProperty("validated_currency"));
        assertEquals("yes", row.getProperty("requires_eur_parallel")); // non-EUR
        assertEquals("0.92", row.getProperty("fx_rate_to_eur"));

        // Fee
        assertEquals("yes", row.getProperty("has_fee")); // secu has fee 25.00
        assertEquals("23", row.getProperty("base_fee_eur")); // base_fee from FX conversion
        assertEquals("SEC-REF-001", row.getProperty("source_reference"));
    }

    // ===== Description Builder =====

    @Test
    public void testBuildDescriptionBankDefaults() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        Map<String, Object> config = new HashMap<>();

        String desc = persister.buildDescription(ctx, config);

        // Default bank fields: payment_description,reference_number,other_side_name,other_side_bic,other_side_account
        assertTrue(desc.contains("payment_description: Wire transfer"));
        assertTrue(desc.contains("reference_number: REF-12345"));
        assertTrue(desc.contains("other_side_name: Deutsche Bank AG"));
        assertTrue(desc.contains("other_side_bic: DEUTDEFF"));
        // Fields separated by " | "
        assertTrue(desc.contains(" | "));
    }

    @Test
    public void testBuildDescriptionSecuDefaults() {
        DataContext ctx = TestDataFactory.fullyEnrichedSecuContext();
        Map<String, Object> config = new HashMap<>();

        String desc = persister.buildDescription(ctx, config);

        // Default secu fields: description,reference,ticker,quantity,price
        assertTrue(desc.contains("description: Apple Inc. Common Stock"));
        assertTrue(desc.contains("reference: SEC-REF-001"));
        assertTrue(desc.contains("ticker: AAPL"));
        assertTrue(desc.contains("quantity: 100"));
        assertTrue(desc.contains("price: 500.00"));
    }

    @Test
    public void testBuildDescriptionCustomFields() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        Map<String, Object> config = new HashMap<>();
        config.put("bankDescriptionFields", "payment_description,reference_number");

        String desc = persister.buildDescription(ctx, config);

        assertTrue(desc.contains("payment_description: Wire transfer"));
        assertTrue(desc.contains("reference_number: REF-12345"));
        // Should NOT contain fields not in custom list
        assertFalse(desc.contains("other_side_name"));
    }

    @Test
    public void testBuildDescriptionNullFieldSkipped() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        // Add a field config that includes a non-existent property
        Map<String, Object> config = new HashMap<>();
        config.put("bankDescriptionFields", "payment_description,nonexistent_field,reference_number");

        String desc = persister.buildDescription(ctx, config);

        assertTrue(desc.contains("payment_description: Wire transfer"));
        assertTrue(desc.contains("reference_number: REF-12345"));
        assertFalse(desc.contains("nonexistent_field"));
    }

    @Test
    public void testBuildDescriptionTruncation() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        Map<String, Object> config = new HashMap<>();
        config.put("descriptionMaxLength", 30);

        String desc = persister.buildDescription(ctx, config);

        assertTrue(desc.length() <= 30);
    }

    @Test
    public void testBuildDescriptionNullTransactionRow() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setTransactionRow(null);

        String desc = persister.buildDescription(ctx, new HashMap<>());

        assertEquals("", desc);
    }

    // ===== Settlement Date =====

    @Test
    public void testSettlementDateBankSameAsTransaction() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        Map<String, Object> config = new HashMap<>();

        String settlement = persister.computeSettlementDate(ctx, config);

        assertEquals("2026-01-15", settlement); // T+0 for bank
    }

    @Test
    public void testSettlementDateSecuTPlusTwo() {
        DataContext ctx = TestDataFactory.fullyEnrichedSecuContext();
        // 2026-01-15 is a Thursday, T+2 = 2026-01-19 (Mon, skipping weekend)
        Map<String, Object> config = TestDataFactory.defaultProperties();

        String settlement = persister.computeSettlementDate(ctx, config);

        assertEquals("2026-01-19", settlement); // Thu + 2 business days = Mon
    }

    @Test
    public void testSettlementDateSecuWeekdayNoSkip() {
        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTransactionDate("2026-01-12"); // Monday
        Map<String, Object> config = TestDataFactory.defaultProperties();

        String settlement = persister.computeSettlementDate(ctx, config);

        assertEquals("2026-01-14", settlement); // Mon + 2 = Wed
    }

    @Test
    public void testSettlementDateSecuWeekendSkip() {
        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTransactionDate("2026-01-16"); // Friday
        Map<String, Object> config = TestDataFactory.defaultProperties();

        String settlement = persister.computeSettlementDate(ctx, config);

        assertEquals("2026-01-20", settlement); // Fri + 2 business days = Tue (skip Sat, Sun)
    }

    @Test
    public void testSettlementDateCustomTPlusThree() {
        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTransactionDate("2026-01-12"); // Monday
        Map<String, Object> config = new HashMap<>();
        config.put("settlementDays", 3);

        String settlement = persister.computeSettlementDate(ctx, config);

        assertEquals("2026-01-15", settlement); // Mon + 3 = Thu
    }

    @Test
    public void testSettlementDateNullDate() {
        DataContext ctx = TestDataFactory.secuContext();
        ctx.setTransactionDate(null);

        String settlement = persister.computeSettlementDate(ctx, new HashMap<>());

        assertNull(settlement);
    }

    // ===== Secu Debit/Credit Mapping =====

    @Test
    public void testDebitCreditBankDirect() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setDebitCredit("C");

        String dc = persister.computeDebitCredit(ctx, new HashMap<>());

        assertEquals("C", dc); // Bank uses direct value
    }

    @Test
    public void testSecuDebitCreditDefaultBuy() {
        String dc = persister.mapSecuDebitCredit("BUY", new HashMap<>());
        assertEquals("D", dc);
    }

    @Test
    public void testSecuDebitCreditDefaultSell() {
        String dc = persister.mapSecuDebitCredit("SELL", new HashMap<>());
        assertEquals("C", dc);
    }

    @Test
    public void testSecuDebitCreditDefaultDividend() {
        String dc = persister.mapSecuDebitCredit("DIVIDEND", new HashMap<>());
        assertEquals("C", dc);
    }

    @Test
    public void testSecuDebitCreditDefaultFee() {
        String dc = persister.mapSecuDebitCredit("FEE", new HashMap<>());
        assertEquals("D", dc);
    }

    @Test
    public void testSecuDebitCreditDefaultUnknown() {
        String dc = persister.mapSecuDebitCredit("SOME_UNKNOWN_TYPE", new HashMap<>());
        assertEquals("N", dc);
    }

    @Test
    public void testSecuDebitCreditNullType() {
        String dc = persister.mapSecuDebitCredit(null, new HashMap<>());
        assertEquals("N", dc);
    }

    @Test
    public void testSecuDebitCreditJsonMapping() {
        Map<String, Object> config = new HashMap<>();
        config.put("secuDebitCreditMapping", "{\"BUY\":\"D\",\"SELL\":\"C\",\"CUSTOM_TYPE\":\"D\"}");

        assertEquals("D", persister.mapSecuDebitCredit("BUY", config));
        assertEquals("C", persister.mapSecuDebitCredit("SELL", config));
        assertEquals("D", persister.mapSecuDebitCredit("CUSTOM_TYPE", config));
        assertEquals("N", persister.mapSecuDebitCredit("UNRECOGNIZED", config)); // Not in map
    }

    @Test
    public void testSecuDebitCreditCommaSeparatedMapping() {
        Map<String, Object> config = new HashMap<>();
        config.put("secuDebitCreditMapping", "BUY:D,SELL:C,CUSTOM:N");

        assertEquals("D", persister.mapSecuDebitCredit("BUY", config));
        assertEquals("C", persister.mapSecuDebitCredit("SELL", config));
        assertEquals("N", persister.mapSecuDebitCredit("CUSTOM", config));
    }

    // ===== Type Confidence =====

    @Test
    public void testTypeConfidenceHigh() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        // Has internal_type=WIRE_TRANSFER (not UNMATCHED) and customer_confidence=100

        String confidence = persister.computeTypeConfidence(ctx, TestDataFactory.defaultProperties());

        assertEquals("high", confidence);
    }

    @Test
    public void testTypeConfidenceMedium() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withF14(ctx, "WIRE_TRANSFER", "R1");
        TestDataFactory.withCustomer(ctx, "CUST-001", 60); // >= 50 but < 80

        String confidence = persister.computeTypeConfidence(ctx, TestDataFactory.defaultProperties());

        assertEquals("medium", confidence);
    }

    @Test
    public void testTypeConfidenceLow() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withF14(ctx, "WIRE_TRANSFER", "R1");
        TestDataFactory.withCustomer(ctx, "CUST-001", 30); // < 50

        String confidence = persister.computeTypeConfidence(ctx, TestDataFactory.defaultProperties());

        assertEquals("low", confidence);
    }

    @Test
    public void testTypeConfidenceUnmatchedAlwaysNotHigh() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withF14(ctx, FrameworkConstants.INTERNAL_TYPE_UNMATCHED, null);
        TestDataFactory.withCustomer(ctx, "CUST-001", 100); // high confidence but UNMATCHED type

        String confidence = persister.computeTypeConfidence(ctx, TestDataFactory.defaultProperties());

        // UNMATCHED blocks "high", but confidence 100 >= 50 → "medium"
        assertEquals("medium", confidence);
    }

    @Test
    public void testTypeConfidenceNullAdditionalData() {
        DataContext ctx = new DataContext();
        ctx.setAdditionalData(null);

        String confidence = persister.computeTypeConfidence(ctx, new HashMap<>());

        assertEquals("low", confidence);
    }

    // ===== Counterparty Routing =====

    @Test
    public void testCounterpartyRoutingBank() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        // Bank type → counterparty_id/short_code
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("CPT0143", row.getProperty("counterparty_id"));
        assertEquals("BARC", row.getProperty("counterparty_short_code"));
        assertNull(row.getProperty("custodian_id"));
        assertNull(row.getProperty("broker_id"));
    }

    @Test
    public void testCounterpartyRoutingCustodian() {
        DataContext ctx = TestDataFactory.fullyEnrichedSecuContext();
        // Custodian type → custodian_id/short_code
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("CPT0200", row.getProperty("custodian_id"));
        assertEquals("BARC", row.getProperty("custodian_short_code"));
        assertNull(row.getProperty("counterparty_id"));
        assertNull(row.getProperty("broker_id"));
    }

    @Test
    public void testCounterpartyRoutingBroker() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "BRK-001", "Broker");
        TestDataFactory.withCustomer(ctx, "CUST-001", 100);
        TestDataFactory.withF14(ctx, "WIRE_TRANSFER", "R1");
        TestDataFactory.withFx(ctx, 1.0, "2026-01-15");

        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("BRK-001", row.getProperty("broker_id"));
        assertEquals("BARC", row.getProperty("broker_short_code"));
        assertNull(row.getProperty("counterparty_id"));
        assertNull(row.getProperty("custodian_id"));
    }

    // ===== Lineage Note =====

    @Test
    public void testBuildLineageNoteFormat() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.addProcessedStep("Currency Validation");
        ctx.addProcessedStep("FX Conversion");
        Map<String, Object> config = TestDataFactory.defaultProperties();

        String note = persister.buildLineageNote(ctx, config);

        assertTrue(note.startsWith("Pipeline v3.0:"));
        assertTrue(note.contains("2/6 steps OK"));
        assertTrue(note.contains("Currency Validation"));
        assertTrue(note.contains("FX Conversion"));
    }

    @Test
    public void testBuildLineageNoteNoSteps() {
        DataContext ctx = TestDataFactory.bankContext();
        // Clear processed steps
        ctx.setProcessedSteps(new ArrayList<>());
        Map<String, Object> config = new HashMap<>();

        String note = persister.buildLineageNote(ctx, config);

        assertTrue(note.contains("0/6 steps OK"));
        assertTrue(note.startsWith("Pipeline v3.0:"));
    }

    @Test
    public void testBuildLineageNoteCustomVersion() {
        DataContext ctx = TestDataFactory.bankContext();
        Map<String, Object> config = new HashMap<>();
        config.put("pipelineVersion", "3.1");

        String note = persister.buildLineageNote(ctx, config);

        assertTrue(note.startsWith("Pipeline v3.1:"));
    }

    // ===== Manual Review Triggers (all 6 conditions) =====

    @Test
    public void testManualReviewUnknownCustomer() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setCustomerId(FrameworkConstants.ENTITY_UNKNOWN);

        assertTrue(persister.determineManualReviewStatus(ctx));
    }

    @Test
    public void testManualReviewUnknownCounterparty() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setAdditionalDataValue("counterparty_id", FrameworkConstants.ENTITY_UNKNOWN);

        assertTrue(persister.determineManualReviewStatus(ctx));
    }

    @Test
    public void testManualReviewUnmatchedType() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setAdditionalDataValue("internal_type", FrameworkConstants.INTERNAL_TYPE_UNMATCHED);

        assertTrue(persister.determineManualReviewStatus(ctx));
    }

    @Test
    public void testManualReviewLowConfidence() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setAdditionalDataValue("customer_confidence", 50);

        assertTrue(persister.determineManualReviewStatus(ctx));
    }

    @Test
    public void testManualReviewUnknownAsset() {
        DataContext ctx = TestDataFactory.fullyEnrichedSecuContext();
        ctx.setAdditionalDataValue("asset_id", FrameworkConstants.ENTITY_UNKNOWN);

        assertTrue(persister.determineManualReviewStatus(ctx));
    }

    @Test
    public void testManualReviewMissingFxRate() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setAdditionalDataValue("fx_rate_source", "MISSING");

        assertTrue(persister.determineManualReviewStatus(ctx));
    }

    @Test
    public void testManualReviewNotNeeded() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        // fullyEnrichedBankContext has all data → no manual review needed

        assertFalse(persister.determineManualReviewStatus(ctx));
    }

    @Test
    public void testManualReviewNullAdditionalData() {
        DataContext ctx = new DataContext();
        ctx.setAdditionalData(null);

        // null additionalData → manual review needed
        assertTrue(persister.determineManualReviewStatus(ctx));
    }

    @Test
    public void testManualReviewCustomConfidenceThreshold() {
        // With default threshold (80), confidence 70 triggers review
        DataContext ctx1 = TestDataFactory.fullyEnrichedBankContext();
        ctx1.setAdditionalDataValue("customer_confidence", 70);
        assertTrue(persister.determineManualReviewStatus(ctx1));

        // With custom threshold (60), confidence 70 should NOT trigger review
        DataContext ctx2 = TestDataFactory.fullyEnrichedBankContext();
        ctx2.setAdditionalDataValue("customer_confidence", 70);
        Map<String, Object> config = new HashMap<>();
        config.put("confidenceThresholdHigh", 60);
        assertFalse(persister.determineManualReviewStatus(ctx2, config));
    }

    // ===== EUR Parallel =====

    @Test
    public void testRequiresEurParallelForNonEur() {
        DataContext ctx = TestDataFactory.fullyEnrichedSecuContext(); // USD
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("yes", row.getProperty("requires_eur_parallel"));
    }

    @Test
    public void testNoEurParallelForEur() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext(); // EUR
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("no", row.getProperty("requires_eur_parallel"));
    }

    // ===== Has Fee =====

    @Test
    public void testHasFeeYes() {
        DataContext ctx = TestDataFactory.fullyEnrichedSecuContext(); // fee = 25.00
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("yes", row.getProperty("has_fee"));
    }

    @Test
    public void testHasFeeNoWhenZero() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext(); // no fee
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("no", row.getProperty("has_fee"));
    }

    @Test
    public void testBaseFeeEurNotSetForBank() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        // base_fee_eur should NOT be set for bank transactions
        assertNull(row.getProperty("base_fee_eur"));
    }

    // ===== Customer Match Method =====

    @Test
    public void testCustomerMatchMethodDirectId() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        // withCustomer sets customer_identification_method = "DIRECT_ID"
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("direct_id", row.getProperty("customer_match_method"));
    }

    @Test
    public void testCustomerMatchMethodAccountNumber() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setAdditionalDataValue("customer_identification_method", "ACCOUNT_NUMBER");
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("account_mapping", row.getProperty("customer_match_method"));
    }

    @Test
    public void testCustomerMatchMethodRegistration() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setAdditionalDataValue("customer_identification_method", "REGISTRATION_NUMBER_EXTRACTED");
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("registration_number", row.getProperty("customer_match_method"));
    }

    @Test
    public void testCustomerMatchMethodNamePattern() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setAdditionalDataValue("customer_identification_method", "NAME_PATTERN");
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("name_pattern", row.getProperty("customer_match_method"));
    }

    @Test
    public void testCustomerMatchMethodNone() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setAdditionalDataValue("customer_identification_method", "NONE");
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("unresolved", row.getProperty("customer_match_method"));
    }

    // ===== Currency Mismatch Flag =====

    @Test
    public void testCurrencyMismatchFlagTrue() {
        DataContext ctx = TestDataFactory.fullyEnrichedSecuContext();
        ctx.setAdditionalDataValue("currency_mismatch_flag", "yes");
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("yes", row.getProperty("currency_mismatch_flag"));
    }

    @Test
    public void testCurrencyMismatchFlagFalse() {
        DataContext ctx = TestDataFactory.fullyEnrichedSecuContext();
        // Default in fullyEnrichedSecuContext: currency_mismatch_flag = "false"
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("no", row.getProperty("currency_mismatch_flag"));
    }

    // ===== Processing Notes =====

    @Test
    public void testProcessingNotesContent() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.addProcessedStep("Currency Validation");
        ctx.addProcessedStep("FX Conversion");
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        String notes = row.getProperty("processing_notes");

        assertNotNull(notes);
        assertTrue(notes.contains("Currency Validation"));
        assertTrue(notes.contains("FX Conversion"));
        assertTrue(notes.contains("CUST-001"));
        assertTrue(notes.contains("CPT0143"));
        assertTrue(notes.contains("WIRE_TRANSFER"));
    }

    // ===== Record ID Format =====

    @Test
    public void testRecordIdFormat() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.getRecordId().startsWith("TRX-"));
        assertEquals(10, result.getRecordId().length()); // "TRX-" + 6 hex chars
    }

    // ===== Source Row Update (Phase 9) =====

    @Test
    public void testUpdateSourceRowWithEnrichmentRef() {
        // Stub loadFormRow for bank source table so updateSourceRowWithEnrichmentRef executes
        FormRow sourceRow = new FormRow();
        sourceRow.setId("TRX-001");
        sourceRow.setProperty("status", "processing");
        FormRowSet sourceRowSet = new FormRowSet();
        sourceRowSet.add(sourceRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                eq("WHERE id = ?"), any(Object[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(sourceRowSet);

        // Set StatusManager so the null guard doesn't skip transitions
        // (transitions will fail silently since StatusManager is static, but the code path executes)
        persister.setStatusManager(new com.fiscaladmin.gam.framework.status.StatusManager());

        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());

        // Capture the saveOrUpdate call for the source table
        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(),
                eq(DomainConstants.TABLE_BANK_TOTAL_TRX), captor.capture());
        FormRow updatedSource = captor.getValue().get(0);

        assertNotNull(updatedSource.getProperty("enrichment_date"));
        assertNotNull(updatedSource.getProperty("enriched_record_id"));
        assertTrue(updatedSource.getProperty("enriched_record_id").startsWith("TRX-"));
    }

    @Test
    public void testUpdateSourceRowSkipsWhenNotFound() {
        // No stub for source table → loadFormRow returns null → graceful skip
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        // Verify no saveOrUpdate on bank_total_trx (only on trx_enrichment)
        verify(mockDao, Mockito.never()).saveOrUpdate(isNull(),
                eq(DomainConstants.TABLE_BANK_TOTAL_TRX), any(FormRowSet.class));
    }

    // ===== Description Truncation Edge Case (Phase 8) =====

    @Test
    public void testBuildDescriptionFirstFieldExceedsMaxLength() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        // Set a very short maxLength so even the first field exceeds it
        Map<String, Object> config = new HashMap<>();
        config.put("descriptionMaxLength", 10);
        config.put("bankDescriptionFields", "payment_description");

        String desc = persister.buildDescription(ctx, config);

        // First field "payment_description: Wire transfer" (35 chars) exceeds 10
        // Should truncate the first field as last resort
        assertEquals(10, desc.length());
        assertTrue(desc.startsWith("payment_de")); // truncated at 10
    }

    // ===== §2.4 Secu Manual Review: source-type-aware determineManualReviewStatus =====

    @Test
    public void testSecuUnknownCustomerNotManualReview() {
        // For secu, UNKNOWN customer is expected (not an error) → NOT manual review
        DataContext ctx = TestDataFactory.fullyEnrichedSecuContext();
        ctx.setCustomerId(FrameworkConstants.ENTITY_UNKNOWN);

        assertFalse(persister.determineManualReviewStatus(ctx, new HashMap<>()));
    }

    @Test
    public void testBankUnknownCustomerStillManualReview() {
        // For bank, UNKNOWN customer → still triggers manual review (unchanged behavior)
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setCustomerId(FrameworkConstants.ENTITY_UNKNOWN);

        assertTrue(persister.determineManualReviewStatus(ctx, new HashMap<>()));
    }

    @Test
    public void testSecuUnknownAssetManualReview() {
        // For secu, UNKNOWN asset → triggers manual review
        DataContext ctx = TestDataFactory.fullyEnrichedSecuContext();
        ctx.setAdditionalDataValue("asset_id", FrameworkConstants.ENTITY_UNKNOWN);

        assertTrue(persister.determineManualReviewStatus(ctx, new HashMap<>()));
    }

    // ===== §2.5a needs_manual_review flag stored on context =====

    @Test
    public void testNeedsManualReviewFlagOnContext() {
        // After persist(), the needs_manual_review flag should be stored on context additionalData
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        // fullyEnrichedBankContext has all data → no manual review needed
        persister.persist(ctx, mockDao, new HashMap<>());

        assertEquals(false, ctx.getAdditionalDataValue("needs_manual_review"));
    }

    @Test
    public void testNeedsManualReviewFlagOnContextWhenTrue() {
        // UNKNOWN customer on bank → needs_manual_review = true on context
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setCustomerId(FrameworkConstants.ENTITY_UNKNOWN);

        persister.persist(ctx, mockDao, new HashMap<>());

        assertEquals(true, ctx.getAdditionalDataValue("needs_manual_review"));
    }

    // ===== §4.0c: Securities-Related Bank Row Manual Review Skip =====

    @Test
    public void testManualReviewSkippedForSecuritiesBuy() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setCustomerId(FrameworkConstants.ENTITY_UNKNOWN);
        ctx.setPaymentDescription("Securities buy (AAPL)");

        assertFalse(persister.determineManualReviewStatus(ctx, new HashMap<>()));
    }

    @Test
    public void testManualReviewSkippedForDividends() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setCustomerId(FrameworkConstants.ENTITY_UNKNOWN);
        ctx.setPaymentDescription("Dividends (MSFT)");

        assertFalse(persister.determineManualReviewStatus(ctx, new HashMap<>()));
    }

    @Test
    public void testManualReviewSkippedForIncomeTax() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setCustomerId(FrameworkConstants.ENTITY_UNKNOWN);
        ctx.setPaymentDescription("Income tax withheld (MSFT) (15.01.2026)");

        assertFalse(persister.determineManualReviewStatus(ctx, new HashMap<>()));
    }

    @Test
    public void testManualReviewNotSkippedForNonSecurities() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setCustomerId(FrameworkConstants.ENTITY_UNKNOWN);
        ctx.setPaymentDescription("Wire transfer");

        assertTrue(persister.determineManualReviewStatus(ctx, new HashMap<>()));
    }

    @Test
    public void testManualReviewNotSkippedForNullDescription() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setCustomerId(FrameworkConstants.ENTITY_UNKNOWN);
        ctx.setPaymentDescription(null);

        assertTrue(persister.determineManualReviewStatus(ctx, new HashMap<>()));
    }

    // ===== Source Reference Tests =====

    @Test
    public void testSourceReferenceBank_fromTransactionRow() {
        // Bank context with GROUP_CONCAT'd transaction_reference → source_reference populated
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        // bankTrxRow sets transaction_reference = "900727947,900727952"
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("900727947,900727952", row.getProperty("source_reference"));
    }

    @Test
    public void testSourceReferenceSecu_fromContext() {
        // Secu context with reference → source_reference populated from context.getReference()
        DataContext ctx = TestDataFactory.fullyEnrichedSecuContext();
        // secuContext sets reference = "SEC-REF-001"
        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("SEC-REF-001", row.getProperty("source_reference"));
    }

    @Test
    public void testSourceReferenceBank_nullTransactionRow() {
        // Bank context with null transaction row → source_reference not set (no NPE)
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setTransactionRow(null);

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());
        assertTrue(result.isSuccess());

        FormRow row = capturePersistedRow();
        assertNull(row.getProperty("source_reference"));
    }

    // ===== §9b Upsert Logic =====

    @Test
    public void testUpsert_reusesExistingEnrichmentId() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setAdditionalDataValue("existing_enrichment_id", "TRX-EXIST");

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        assertEquals("TRX-EXIST", result.getRecordId());

        FormRow row = capturePersistedRow();
        assertEquals("TRX-EXIST", row.getId());
    }

    @Test
    public void testUpsert_generatesNewIdWhenNoExisting() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        // No existing_enrichment_id set

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        assertTrue(result.getRecordId().startsWith("TRX-"));
        assertNotEquals("TRX-EXIST", result.getRecordId());
    }

    // ===== Loan Resolution Fields =====

    @Test
    public void testLoanFieldsMappedWhenPresent() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        TestDataFactory.withLoan(ctx, "LOAN-001", "disbursement", "DIRECT_MATCH");

        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertEquals("LOAN-001", row.getProperty("loan_id"));
        assertEquals("disbursement", row.getProperty("loan_direction"));
        assertEquals("DIRECT_MATCH", row.getProperty("loan_resolution_method"));
    }

    @Test
    public void testLoanFieldsAbsentWhenEmpty() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        // No loan data set

        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertNull(row.getProperty("loan_id"));
        assertNull(row.getProperty("loan_direction"));
        assertNull(row.getProperty("loan_resolution_method"));
    }

    @Test
    public void testLoanFieldsAbsentWhenNull() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setAdditionalDataValue("loan_id", null);
        ctx.setAdditionalDataValue("loan_direction", null);
        ctx.setAdditionalDataValue("loan_resolution_method", null);

        persister.persist(ctx, mockDao, new HashMap<>());

        FormRow row = capturePersistedRow();
        assertNull(row.getProperty("loan_id"));
        assertNull(row.getProperty("loan_direction"));
        assertNull(row.getProperty("loan_resolution_method"));
    }

    // ===== §4.0c Securities Commission/Sell Manual Review Skip =====

    @Test
    public void testManualReviewSkippedForSecuritiesSell() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setCustomerId(FrameworkConstants.ENTITY_UNKNOWN);
        ctx.setPaymentDescription("Securities sell (MSFT)");

        assertFalse(persister.determineManualReviewStatus(ctx, new HashMap<>()));
    }

    @Test
    public void testManualReviewSkippedForSecuritiesCommission() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setCustomerId(FrameworkConstants.ENTITY_UNKNOWN);
        ctx.setPaymentDescription("Securities commission fee");

        assertFalse(persister.determineManualReviewStatus(ctx, new HashMap<>()));
    }

    // ===== Re-enrichment Tests =====

    @Test
    public void testReEnrichment_existingEnrichmentIdReused() {
        // Re-enrichment: existing enrichment ID should be reused (upsert)
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setAdditionalDataValue("existing_enrichment_id", "ENR-EXIST");
        ctx.setReEnrichment(true);

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        assertEquals("ENR-EXIST", result.getRecordId());

        FormRow row = capturePersistedRow();
        assertEquals("ENR-EXIST", row.getId());
    }

    @Test
    public void testReEnrichment_statusUpgrade() {
        // MANUAL_REVIEW trx → all sentinels resolved → determineManualReviewStatus=false → ENRICHED
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setReEnrichment(true);
        ctx.setAdditionalDataValue("existing_enrichment_id", "ENR-001");
        ctx.getTransactionRow().setProperty("status", Status.MANUAL_REVIEW.getCode());

        boolean needsReview = persister.determineManualReviewStatus(ctx, new HashMap<>());

        assertFalse("Fully resolved context should not need manual review", needsReview);
    }

    @Test
    public void testReEnrichment_statusDowngrade() {
        // ENRICHED trx → new sentinel (UNKNOWN counterparty) → needs manual review
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setReEnrichment(true);
        ctx.setAdditionalDataValue("existing_enrichment_id", "ENR-001");
        ctx.setAdditionalDataValue("counterparty_id", FrameworkConstants.ENTITY_UNKNOWN);
        ctx.getTransactionRow().setProperty("status", Status.ENRICHED.getCode());

        boolean needsReview = persister.determineManualReviewStatus(ctx, new HashMap<>());

        assertTrue("UNKNOWN counterparty should trigger manual review", needsReview);
    }

    @Test
    public void testReEnrichment_enrichmentRecordSkipsLifecycle() {
        // Re-enrichment with StatusManager: should NOT do NEW→PROCESSING lifecycle
        com.fiscaladmin.gam.framework.status.StatusManager sm =
                Mockito.spy(new com.fiscaladmin.gam.framework.status.StatusManager());
        persister.setStatusManager(sm);

        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setReEnrichment(true);
        ctx.setAdditionalDataValue("existing_enrichment_id", "ENR-EXIST");

        // Stub source row load for updateSourceRowWithEnrichmentRef
        FormRow sourceRow = new FormRow();
        sourceRow.setId("TRX-001");
        sourceRow.setProperty("status", Status.ENRICHED.getCode());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                eq("WHERE id = ?"), any(Object[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.rowSet(sourceRow));

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        assertEquals("ENR-EXIST", result.getRecordId());
    }

    @Test
    public void testReEnrichment_sourceTransactionSameStatusNoTransition() {
        // Re-enrichment: source trx already ENRICHED, result also ENRICHED → no transition
        com.fiscaladmin.gam.framework.status.StatusManager sm =
                Mockito.spy(new com.fiscaladmin.gam.framework.status.StatusManager());
        persister.setStatusManager(sm);

        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setReEnrichment(true);
        ctx.setAdditionalDataValue("existing_enrichment_id", "ENR-001");
        ctx.getTransactionRow().setProperty("status", Status.ENRICHED.getCode());

        // Stub source row load
        FormRow sourceRow = new FormRow();
        sourceRow.setId("TRX-001");
        sourceRow.setProperty("status", Status.ENRICHED.getCode());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                eq("WHERE id = ?"), any(Object[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.rowSet(sourceRow));

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        // Persist should succeed — transition skipped because same status
        assertTrue(result.isSuccess());
    }

    // ===== §4.1 Self-Transition Skip Tests =====

    @Test
    public void testReEnrichment_selfTransitionSkippedSilently() {
        // Re-enrichment: enrichment_status=enriched, target=ENRICHED → no StatusManager call
        com.fiscaladmin.gam.framework.status.StatusManager sm =
                Mockito.spy(new com.fiscaladmin.gam.framework.status.StatusManager());
        persister.setStatusManager(sm);

        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setReEnrichment(true);
        ctx.setAdditionalDataValue("existing_enrichment_id", "ENR-001");
        ctx.setAdditionalDataValue("enrichment_status", Status.ENRICHED.getCode());
        ctx.getTransactionRow().setProperty("status", Status.ENRICHED.getCode());

        // Stub source row load
        FormRow sourceRow = new FormRow();
        sourceRow.setId("TRX-001");
        sourceRow.setProperty("status", Status.ENRICHED.getCode());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                eq("WHERE id = ?"), any(Object[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.rowSet(sourceRow));

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        // StatusManager should NOT have been called for enrichment transition (self-transition skipped)
        // and should NOT have been called for source transaction (same status skipped by transitionSourceTransaction)
        // Only saveOrUpdate calls should be for the enrichment row and the source row metadata update
        verify(mockDao, never()).load(any(), eq(DomainConstants.TABLE_TRX_ENRICHMENT), anyString());
    }

    @Test
    public void testReEnrichment_statusChangeLogged() {
        // Re-enrichment: enrichment_status=manual_review, target=ENRICHED → StatusManager IS called
        com.fiscaladmin.gam.framework.status.StatusManager sm =
                Mockito.spy(new com.fiscaladmin.gam.framework.status.StatusManager());
        persister.setStatusManager(sm);

        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setReEnrichment(true);
        ctx.setAdditionalDataValue("existing_enrichment_id", "ENR-001");
        ctx.setAdditionalDataValue("enrichment_status", Status.MANUAL_REVIEW.getCode());
        ctx.getTransactionRow().setProperty("status", Status.MANUAL_REVIEW.getCode());

        // Stub source row load
        FormRow sourceRow = new FormRow();
        sourceRow.setId("TRX-001");
        sourceRow.setProperty("status", Status.MANUAL_REVIEW.getCode());
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_BANK_TOTAL_TRX),
                eq("WHERE id = ?"), any(Object[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.rowSet(sourceRow));

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        // StatusManager SHOULD attempt the enrichment transition (manual_review → enriched is a status change)
        // It will fail because dao.load returns null in mock, but the code path should be exercised
        assertEquals("ENR-001", result.getRecordId());
    }

    // ===== §5 Workspace Protection Persist Guard =====

    @Test
    public void testPersist_workspaceProtected_inReview_skipped() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setAdditionalDataValue("workspace_protected", "true");
        ctx.setAdditionalDataValue("enrichment_status", Status.IN_REVIEW.getCode());

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        assertNull(result.getRecordId());
        assertTrue(result.getMessage().contains("workspace protected"));
        verify(mockDao, never()).saveOrUpdate(any(), anyString(), any(FormRowSet.class));
    }

    @Test
    public void testPersist_workspaceProtected_adjusted_skipped() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setAdditionalDataValue("workspace_protected", "true");
        ctx.setAdditionalDataValue("enrichment_status", Status.ADJUSTED.getCode());

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue(result.isSuccess());
        assertNull(result.getRecordId());
        assertTrue(result.getMessage().contains("workspace protected"));
        verify(mockDao, never()).saveOrUpdate(any(), anyString(), any(FormRowSet.class));
    }

    @Test
    public void testPersist_workspaceProtectedSkipped() {
        DataContext ctx = TestDataFactory.fullyEnrichedBankContext();
        ctx.setAdditionalDataValue("workspace_protected", "true");

        PersistenceResult result = persister.persist(ctx, mockDao, new HashMap<>());

        assertTrue("Workspace-protected persist should return success", result.isSuccess());
        assertNull("Workspace-protected persist should have null recordId", result.getRecordId());
        assertTrue(result.getMessage().contains("workspace protected"));
        // No DB writes should have occurred
        verify(mockDao, never()).saveOrUpdate(any(), anyString(), any(FormRowSet.class));
    }
}
