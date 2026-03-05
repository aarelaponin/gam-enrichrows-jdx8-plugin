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
        // indexOf('(') picks the first opening paren
        String ticker = step.extractTickerFromDescription("Securities (buy) order (MSFT)");
        assertEquals("buy", ticker);
    }

    @Test
    public void testExtractTickerFromDescriptionEmptyParens() {
        String ticker = step.extractTickerFromDescription("Securities buy ()");
        assertEquals("", ticker);
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
        // Secu + bank principal + bank fee → combo amount = principal + fee
        FormRowSet records = new FormRowSet();
        FormRow secuRow = enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-50025.00");
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
    public void testExecutePairingComboMatchPrincipalPlusFee() {
        // Secu=10182.03, principal=10167.80, fee=14.23 → combo matches
        FormRowSet records = new FormRowSet();
        records.add(enrichedSecuRow("SECU-001", "AST000296", "2026-01-19", "-10182.03"));
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
}
