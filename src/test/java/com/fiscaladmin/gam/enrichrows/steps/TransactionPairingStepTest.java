package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
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
import static org.mockito.Mockito.*;

public class TransactionPairingStepTest {

    private TransactionPairingStep step;
    private FormDataDao mockDao;

    @Before
    public void setUp() {
        step = new TransactionPairingStep();
        mockDao = Mockito.mock(FormDataDao.class);
    }

    // ===== extractTickerFromDescription tests =====

    @Test
    public void testExtractTickerFromDescriptionBuy() {
        String ticker = step.extractTickerFromDescription("Securities buy (AAPL)");
        assertEquals("AAPL", ticker);
    }

    @Test
    public void testExtractTickerFromDescriptionFee() {
        String ticker = step.extractTickerFromDescription("Securities commission fee (AAPL)");
        assertEquals("AAPL", ticker);
    }

    @Test
    public void testExtractTickerFromDescriptionNull() {
        assertNull(step.extractTickerFromDescription(null));
    }

    @Test
    public void testExtractTickerFromDescriptionNoParens() {
        assertNull(step.extractTickerFromDescription("Wire transfer to account"));
    }

    @Test
    public void testExtractTickerFromDescriptionNestedParens() {
        // TickerExtractor picks the first valid alphanumeric paren group, uppercased
        String ticker = step.extractTickerFromDescription("Securities (buy) order (MSFT)");
        assertEquals("BUY", ticker);
    }

    @Test
    public void testExtractTickerFromDescriptionEmptyParens() {
        String ticker = step.extractTickerFromDescription("Securities buy ()");
        assertNull(ticker);
    }

    @Test
    public void testExtractTickerFromDescriptionWithSpaces() {
        String ticker = step.extractTickerFromDescription("Securities buy ( TSLA )");
        assertEquals("TSLA", ticker);
    }

    // ===== executePairing tests =====

    @Test
    public void testExecutePairingNoRecords() {
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new FormRowSet());

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingNullRecords() {
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(null);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingOnlySecuRecords() {
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-15", "-50000.00"));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingOnlyBankRecords() {
        FormRowSet records = new FormRowSet();
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-15", "-50000.00"));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingMatchingRecords() {
        // Matching secu + bank records: same amount, same date, same currency, same sign
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        secuRow.setProperty("source_reference", "REF-001,REF-002");
        records.add(secuRow);

        FormRow bankRow = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00");
        bankRow.setProperty("source_reference", "REF-001,REF-003");
        records.add(bankRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);

        // Verify pair record was created in trx_pair table
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(),
                eq(DomainConstants.TABLE_TRX_PAIR), any(FormRowSet.class));
    }

    @Test
    public void testExecutePairingNoAmountMatch() {
        // Different amounts → no match (amount-based matching)
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (MSFT)", "2026-01-19", "-30000.00"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingNoDateMatch() {
        // Same amount but dates differ by more than 1 day → no match
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-25", "-50000.00"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingDateTolerancePlusOne() {
        // Bank date is +1 day from secu settlement date → should still match
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        secuRow.setProperty("source_reference", "REF-001");
        records.add(secuRow);

        FormRow bankRow = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-20", "-50000.00");
        bankRow.setProperty("source_reference", "REF-001");
        records.add(bankRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);
    }

    @Test
    public void testExecutePairingAlreadyPairedSkipped() {
        // Bank record already has pair_id → should be excluded
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00"));

        FormRow bankRow = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00");
        bankRow.setProperty("pair_id", "PAIR-EXISTING");
        records.add(bankRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingNonEnrichedStatusSkipped() {
        // Records with non-ENRICHED status should be excluded
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00"));

        FormRow bankRow = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00");
        bankRow.setProperty("status", "processing"); // not ENRICHED
        records.add(bankRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingWithFeeRecord() {
        // Two-phase: secu principal matches bank principal, fee matched separately
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00", "yes", "-25.00");
        secuRow.setProperty("source_reference", "REF-001");
        records.add(secuRow);

        FormRow bankPrincipal = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY");
        bankPrincipal.setProperty("source_reference", "REF-001");
        records.add(bankPrincipal);

        FormRow bankFee = enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-19", "-25.00", "COMM_FEE");
        records.add(bankFee);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);

        // Verify fee_trx_id was set on secu record
        assertEquals("BANK-002", secuRow.getProperty("fee_trx_id"));
    }

    @Test
    public void testExecutePairingSecuUnknownAssetSkipped() {
        // Secu with UNKNOWN resolved_asset_id → should not pair
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "UNKNOWN", "2026-01-19", "-50000.00");
        records.add(secuRow);

        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    // ===== Ticker extraction additional tests =====

    @Test
    public void testExtractTickerFromDescriptionIncomeTax() {
        String ticker = step.extractTickerFromDescription("Income tax withheld (NVDA) (01.07.2024)");
        assertEquals("NVDA", ticker);
    }

    @Test
    public void testExtractTickerFromDescriptionDividends() {
        String ticker = step.extractTickerFromDescription("Dividends (CRWD)");
        assertEquals("CRWD", ticker);
    }

    // ===== Split transaction filtering tests =====

    @Test
    public void testExecutePairingSplitTransactionSkipped() {
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "0");
        secuRow.setProperty("custodian_id", "CPT0200");
        records.add(secuRow);

        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "0"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingSplitZeroPointZeroSkipped() {
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "0.00");
        secuRow.setProperty("custodian_id", "CPT0200");
        records.add(secuRow);

        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "0.00"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingZeroAmountNonCustodianNotSkipped() {
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "0");
        secuRow.setProperty("broker_id", "BRK001");
        // No custodian_id set → not a split
        records.add(secuRow);

        FormRow bankRow = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "0");
        records.add(bankRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);
    }

    // ===== Dividend/tax bank record skipping tests (by internal_type) =====

    @Test
    public void testExecutePairingDividendBankRecordSkipped() {
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00"));
        records.add(enrichedBankRow("BANK-001", "Dividends (AAPL)", "2026-01-19", "-50000.00", "DIV_INCOME"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingIncomeTaxBankRecordSkipped() {
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00"));
        records.add(enrichedBankRow("BANK-001", "Income tax withheld (AAPL) (01.07.2024)", "2026-01-19", "-50000.00", "INCOME_TAX"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingSecuritiesBuyNotSkipped() {
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        records.add(secuRow);
        FormRow bankRow = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY");
        records.add(bankRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);
    }

    // ===== Date offset tests =====

    @Test
    public void testPairRecordDateOffsetZero() {
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        records.add(secuRow);
        FormRow bankRow = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00");
        records.add(bankRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        step.executePairing(mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), captor.capture());
        FormRow pairRow = captor.getValue().get(0);
        assertEquals("0", pairRow.getProperty("date_offset"));
        assertEquals("2026-01-19", pairRow.getProperty("secu_settle_date"));
        assertEquals("2026-01-19", pairRow.getProperty("bank_pay_date"));
    }

    @Test
    public void testPairRecordDateOffsetMinusOne() {
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        records.add(secuRow);
        FormRow bankRow = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-18", "-50000.00");
        records.add(bankRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        step.executePairing(mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), captor.capture());
        FormRow pairRow = captor.getValue().get(0);
        assertEquals("-1", pairRow.getProperty("date_offset"));
        assertEquals("2026-01-19", pairRow.getProperty("secu_settle_date"));
        assertEquals("2026-01-18", pairRow.getProperty("bank_pay_date"));
    }

    @Test
    public void testPairRecordDateOffsetPlusOne() {
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        records.add(secuRow);
        FormRow bankRow = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-20", "-50000.00");
        records.add(bankRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        step.executePairing(mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), captor.capture());
        FormRow pairRow = captor.getValue().get(0);
        assertEquals("1", pairRow.getProperty("date_offset"));
        assertEquals("2026-01-19", pairRow.getProperty("secu_settle_date"));
        assertEquals("2026-01-20", pairRow.getProperty("bank_pay_date"));
    }

    // ===== Pair status tests =====

    @Test
    public void testPairStatusAutoAcceptedWithoutRefOverlap() {
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        secuRow.setProperty("source_reference", "REF-001");
        records.add(secuRow);
        FormRow bankRow = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00");
        bankRow.setProperty("source_reference", "REF-999"); // different ref, no overlap
        records.add(bankRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        step.executePairing(mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), captor.capture());
        FormRow pairRow = captor.getValue().get(0);
        assertEquals("AUTO_ACCEPTED", pairRow.getProperty("status"));
        assertEquals("no", pairRow.getProperty("references_overlap"));
    }

    @Test
    public void testExecutePairingAmountMismatchNoPair() {
        // Mismatched amounts → 0 pairs created (not PENDING_REVIEW)
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        secuRow.setProperty("source_reference", "REF-001");
        records.add(secuRow);
        FormRow bankRow = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-49000.00");
        bankRow.setProperty("source_reference", "REF-001");
        records.add(bankRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);

        // No pair record should be created
        verify(mockDao, never()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), any(FormRowSet.class));
    }

    @Test
    public void testPairStatusAutoAcceptedWithRefOverlap() {
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        secuRow.setProperty("source_reference", "REF-001");
        records.add(secuRow);
        FormRow bankRow = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00");
        bankRow.setProperty("source_reference", "REF-001"); // refs overlap and amounts match
        records.add(bankRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        step.executePairing(mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), captor.capture());
        FormRow pairRow = captor.getValue().get(0);
        assertEquals("AUTO_ACCEPTED", pairRow.getProperty("status"));
        assertEquals("yes", pairRow.getProperty("references_overlap"));
    }

    // ===== New tests: exact match, sign, currency, ambiguity =====

    @Test
    public void testExecutePairingExactMatchRequired() {
        // Secu=50000, bank=49999.95 (off by 0.05) → 0 pairs
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-49999.95"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingSignMismatch() {
        // Secu=-50000 (buy), bank=+50000 (positive) → 0 pairs
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "50000.00"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingCurrencyMismatch() {
        // Same amounts, secu=USD, bank=EUR → 0 pairs
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00"));

        FormRow bankRow = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00");
        bankRow.setProperty("original_currency", "EUR");
        records.add(bankRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingAmbiguousMatch() {
        // Two bank combos with same amount on same date → 0 pairs + warning
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));
        records.add(enrichedBankRow("BANK-002", "Securities buy (MSFT)", "2026-01-19", "-50000.00", "SEC_BUY"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testExecutePairingTwoPhaseWithFee() {
        // Two-phase: secu principal=-10167.80 matches bank, fee=-14.23 matched separately
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-10167.80", "yes", "-14.23"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-10167.80", "SEC_BUY"));
        records.add(enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-19", "-14.23", "COMM_FEE"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);

        // Verify pair has fee
        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), captor.capture());
        FormRow pairRow = captor.getValue().get(0);
        assertEquals("yes", pairRow.getProperty("has_fee"));
    }

    @Test
    public void testExecutePairingNoPrincipalOnlyFee() {
        // Bank has COMM_FEE but no principal → 0 pairs
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-25.00"));
        records.add(enrichedBankRow("BANK-001", "Securities commission fee (AAPL)", "2026-01-19", "-25.00", "COMM_FEE"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testPairRecordTickerFromBankDescription() {
        // Verify PAIR ticker comes from bank description
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        step.executePairing(mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), captor.capture());
        FormRow pairRow = captor.getValue().get(0);
        assertEquals("AAPL", pairRow.getProperty("ticker"));
    }

    @Test
    public void testExecutePairingAllPairsAutoAccepted() {
        // Exact match → status always AUTO_ACCEPTED
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        records.add(secuRow);
        FormRow bankRow = enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00");
        records.add(bankRow);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        step.executePairing(mockDao);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), captor.capture());
        FormRow pairRow = captor.getValue().get(0);
        assertEquals("AUTO_ACCEPTED", pairRow.getProperty("status"));
    }

    // ===== Helper methods =====

    /**
     * Create an enriched secu F01.05 row suitable for pairing tests.
     */
    private FormRow enrichedSecuRow(String id, String resolvedAssetId, String settlementDate, String amount) {
        FormRow row = new FormRow();
        row.setId(id);
        row.setProperty("source_tp", DomainConstants.SOURCE_TYPE_SECU);
        row.setProperty("status", Status.ENRICHED.getCode());
        row.setProperty("resolved_asset_id", resolvedAssetId);
        row.setProperty("settlement_date", settlementDate);
        row.setProperty("transaction_date", settlementDate);
        row.setProperty("total_amount", amount);
        row.setProperty("original_amount", amount);
        row.setProperty("original_currency", "USD");
        return row;
    }

    /**
     * Create an enriched secu F01.05 row with has_fee and fee_amount for Phase 2 tests.
     */
    private FormRow enrichedSecuRow(String id, String resolvedAssetId, String settlementDate,
                                     String amount, String hasFee, String feeAmount) {
        FormRow row = enrichedSecuRow(id, resolvedAssetId, settlementDate, amount);
        row.setProperty("has_fee", hasFee);
        row.setProperty("fee_amount", feeAmount);
        // Set total_amount = |principal| + |fee| (with same sign as principal) so Signal 3 works
        double principal = Double.parseDouble(amount.replaceAll("[^0-9.\\-]", ""));
        double fee = Math.abs(Double.parseDouble(feeAmount.replaceAll("[^0-9.\\-]", "")));
        double total = principal < 0 ? principal - fee : principal + fee;
        row.setProperty("total_amount", String.format("%.2f", total));
        return row;
    }

    /**
     * Create an enriched bank F01.05 row with explicit internal_type.
     */
    private FormRow enrichedBankRow(String id, String description, String date,
                                     String amount, String internalType) {
        FormRow row = new FormRow();
        row.setId(id);
        row.setProperty("source_tp", DomainConstants.SOURCE_TYPE_BANK);
        row.setProperty("status", Status.ENRICHED.getCode());
        row.setProperty("description", description);
        row.setProperty("transaction_date", date);
        row.setProperty("original_amount", amount);
        row.setProperty("original_currency", "USD");
        row.setProperty("internal_type", internalType);
        return row;
    }

    /**
     * Backwards-compatible overload: defaults to SEC_BUY.
     */
    private FormRow enrichedBankRow(String id, String description, String date, String amount) {
        return enrichedBankRow(id, description, date, amount, "SEC_BUY");
    }

    // ===== Two-phase pairing tests (T1-T10) =====

    @Test
    public void testT1_NoFeeSecuPairsPrincipalOnly() {
        // T1: Secu without has_fee pairs principal only; COMM_FEE stays orphaned
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));
        records.add(enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-19", "-25.00", "COMM_FEE"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);

        // Verify pair record has no fee (Phase 2 skipped because no has_fee)
        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), captor.capture());
        FormRow pairRow = captor.getValue().get(0);
        assertEquals("no", pairRow.getProperty("has_fee"));
    }

    @Test
    public void testT2_HasFeeWithCommFeeSameDate() {
        // T2: has_fee=yes + COMM_FEE same date → full pair
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00", "yes", "-25.00"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));
        records.add(enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-19", "-25.00", "COMM_FEE"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);

        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), captor.capture());
        FormRow pairRow = captor.getValue().get(0);
        assertEquals("yes", pairRow.getProperty("has_fee"));
        assertEquals("BANK-002", pairRow.getProperty("bank_fee_enrichment_id"));
    }

    @Test
    public void testT3_HasFeeWithCommFeeAtTPlus1() {
        // T3: has_fee=yes + COMM_FEE at T+1 → full pair
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00", "yes", "-25.00"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));
        records.add(enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-20", "-25.00", "COMM_FEE"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);
    }

    @Test
    public void testT4_HasFeeWithCommFeeAtTPlus2() {
        // T4: has_fee=yes + COMM_FEE at T+2 → full pair
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00", "yes", "-25.00"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));
        records.add(enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-21", "-25.00", "COMM_FEE"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);
    }

    @Test
    public void testT5_HasFeeNoCommFeeDeferred() {
        // T5: has_fee=yes but no COMM_FEE available → NOT paired (deferred)
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00", "yes", "-25.00"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);

        // No pair record should be created
        verify(mockDao, never()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), any(FormRowSet.class));
    }

    @Test
    public void testT6_TwoCommFeesDisambiguatedByTicker() {
        // T6: Two COMM_FEE same amount, disambiguated by ticker
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00", "yes", "-25.00");
        secuRow.setProperty("bank_asset_hint_ticker", "AAPL");
        records.add(secuRow);

        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));
        records.add(enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-19", "-25.00", "COMM_FEE"));
        records.add(enrichedBankRow("BANK-003", "Securities commission fee (MSFT)", "2026-01-19", "-25.00", "COMM_FEE"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);

        // Verify fee matched AAPL, not MSFT
        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), captor.capture());
        FormRow pairRow = captor.getValue().get(0);
        assertEquals("BANK-002", pairRow.getProperty("bank_fee_enrichment_id"));
    }

    @Test
    public void testT7_TwoCommFeesNoTickerAmbiguous() {
        // T7: Two COMM_FEE same amount, no ticker → NOT paired (ambiguous)
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00", "yes", "-25.00"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));
        records.add(enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-19", "-25.00", "COMM_FEE"));
        records.add(enrichedBankRow("BANK-003", "Securities commission fee (MSFT)", "2026-01-19", "-25.00", "COMM_FEE"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testT8_DeferredRun1PairedRun2() {
        // T8: Run 1 deferred (no fee), Run 2 fee arrives → paired
        // Run 1: has_fee=yes but no COMM_FEE → deferred
        FormRowSet run1Records = new FormRowSet();
        run1Records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00", "yes", "-25.00"));
        run1Records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(run1Records);

        int run1Pairs = step.executePairing(mockDao);
        assertEquals(0, run1Pairs);

        // Run 2: COMM_FEE arrives
        FormRowSet run2Records = new FormRowSet();
        run2Records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00", "yes", "-25.00"));
        run2Records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));
        run2Records.add(enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-20", "-25.00", "COMM_FEE"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(run2Records);

        int run2Pairs = step.executePairing(mockDao);
        assertEquals(1, run2Pairs);
    }

    @Test
    public void testT9_CommFeeAmountMismatch() {
        // T9: COMM_FEE amount mismatch → NOT paired
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00", "yes", "-25.00"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));
        records.add(enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-19", "-30.00", "COMM_FEE"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }

    @Test
    public void testT11_HasFeeNullFeeAmountPresent_FeeDetectedViaSignal2() {
        // T11: has_fee=null (not loaded), fee_amount present → fee detected via Signal 2, paired
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        // Do NOT set has_fee — simulates Joget not loading the column
        secuRow.setProperty("fee_amount", "-25.00");
        // Set total_amount to include fee (as persister would)
        secuRow.setProperty("total_amount", "-50025.00");
        records.add(secuRow);

        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));
        records.add(enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-19", "-25.00", "COMM_FEE"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);

        // Verify fee was matched
        assertEquals("BANK-002", secuRow.getProperty("fee_trx_id"));
    }

    @Test
    public void testT12_AllNullTotalDiffersFromOriginal_FeeDetectedViaSignal3() {
        // T12: has_fee=null, fee_amount=null, total != original → fee detected via Signal 3
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        // Do NOT set has_fee or fee_amount
        secuRow.setProperty("total_amount", "-50025.00"); // total includes fee
        // original_amount is already -50000.00 from the 4-param helper
        records.add(secuRow);

        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));
        records.add(enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-19", "-25.00", "COMM_FEE"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);

        assertEquals("BANK-002", secuRow.getProperty("fee_trx_id"));
    }

    @Test
    public void testT13_AllNullTotalEqualsOriginal_NoFee() {
        // T13: has_fee=null, fee_amount=null, total == original → no fee, principal-only pair
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        // total_amount == original_amount (both -50000.00 from 4-param helper)
        records.add(secuRow);

        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));
        records.add(enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-19", "-25.00", "COMM_FEE"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);

        // Verify NO fee was matched (principal-only pair)
        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), captor.capture());
        FormRow pairRow = captor.getValue().get(0);
        assertEquals("no", pairRow.getProperty("has_fee"));
    }

    @Test
    public void testT14_HasFeeExplicitNo_TotalDiffers_TrustsExplicitNo() {
        // T14: has_fee="no" explicitly, total != original → trust explicit "no", principal-only pair
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        secuRow.setProperty("has_fee", "no");
        secuRow.setProperty("total_amount", "-50025.00"); // differs, but explicit no overrides
        records.add(secuRow);

        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));
        records.add(enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-19", "-25.00", "COMM_FEE"));

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(1, pairs);

        // Verify NO fee was matched (trusts explicit "no")
        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), captor.capture());
        FormRow pairRow = captor.getValue().get(0);
        assertEquals("no", pairRow.getProperty("has_fee"));
    }

    @Test
    public void testT15_FeeDetectedViaSignal3_NoCommFeeAvailable_Deferred() {
        // T15: Fee detected via Signal 3, but no COMM_FEE available → deferred (0 pairs)
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00");
        // has_fee=null, fee_amount=null, but total differs from original
        secuRow.setProperty("total_amount", "-50025.00");
        records.add(secuRow);

        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));
        // No COMM_FEE record

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);

        // No pair record should be created
        verify(mockDao, never()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_TRX_PAIR), any(FormRowSet.class));
    }

    @Test
    public void testT10_CommFeeCurrencyMismatch() {
        // T10: COMM_FEE currency mismatch → NOT paired
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50000.00", "yes", "-25.00"));
        records.add(enrichedBankRow("BANK-001", "Securities buy (AAPL)", "2026-01-19", "-50000.00", "SEC_BUY"));

        FormRow bankFee = enrichedBankRow("BANK-002", "Securities commission fee (AAPL)", "2026-01-19", "-25.00", "COMM_FEE");
        bankFee.setProperty("original_currency", "EUR"); // currency mismatch vs secu USD
        records.add(bankFee);

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_TRX_ENRICHMENT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(records);

        int pairs = step.executePairing(mockDao);
        assertEquals(0, pairs);
    }
}
