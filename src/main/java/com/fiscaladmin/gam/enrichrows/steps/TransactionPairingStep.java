package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.util.TickerExtractor;
import com.fiscaladmin.gam.framework.status.Status;
import com.fiscaladmin.gam.framework.status.StatusManager;
import com.fiscaladmin.gam.framework.status.EntityType;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Cross-statement pairing step (two-phase matching).
 * Matches consolidated secu F01.05 records with consolidated bank F01.05 records.
 *
 * Phase 1: Match secu original_amount against bank SEC_BUY/SEC_SELL principal amount (±1 day).
 * Phase 2: If secu has_fee=yes, resolve COMM_FEE separately by fee_amount match (±2 days).
 *          If fee is required but not found, pairing is deferred (not partial).
 *
 * Bank records are classified by internal_type (SEC_BUY, SEC_SELL, COMM_FEE).
 *
 * Pairing algorithm:
 * 1. Load all F01.05 records where status = ENRICHED AND pair_id IS NULL
 * 2. Separate into secu and bank lists
 * 3. Build bank principal combo index + bank fee index by date
 * 4. For each secu record:
 *    a. Phase 1: match principal by amount + date (±1 day)
 *    b. Phase 2: if has_fee=yes, match COMM_FEE by fee_amount + date (±2 days)
 * 5. Create PAIR entity records and update matched F01.05 records
 */
public class TransactionPairingStep {

    private static final String CLASS_NAME = TransactionPairingStep.class.getName();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    private StatusManager statusManager;

    public void setStatusManager(StatusManager statusManager) {
        this.statusManager = statusManager;
    }

    /**
     * Execute the pairing step on all unpaired ENRICHED records.
     * Returns the number of pairs created.
     */
    public int executePairing(FormDataDao formDataDao) {
        try {
            // 1. Load all unpaired ENRICHED records
            FormRowSet allRecords = formDataDao.find(
                    null,
                    DomainConstants.TABLE_TRX_ENRICHMENT,
                    null, null, null, null, null, null
            );

            if (allRecords == null || allRecords.isEmpty()) {
                LogUtil.info(CLASS_NAME, "No records found for pairing");
                return 0;
            }

            // Filter to ENRICHED + no pair_id
            List<FormRow> secuRecords = new ArrayList<>();
            List<FormRow> bankRecords = new ArrayList<>();

            for (FormRow row : allRecords) {
                String status = row.getProperty("status");
                String pairId = row.getProperty("pair_id");

                if (!Status.ENRICHED.getCode().equals(status)) continue;
                if (pairId != null && !pairId.isEmpty()) continue;

                String sourceType = row.getProperty("source_tp");
                if (DomainConstants.SOURCE_TYPE_SECU.equals(sourceType)) {
                    if (!isSplitTransaction(row)) {
                        secuRecords.add(row);
                    }
                } else if (DomainConstants.SOURCE_TYPE_BANK.equals(sourceType)) {
                    bankRecords.add(row);
                }
            }

            LogUtil.info(CLASS_NAME, String.format(
                    "Pairing candidates: %d secu records, %d bank records",
                    secuRecords.size(), bankRecords.size()));

            if (secuRecords.isEmpty() || bankRecords.isEmpty()) {
                LogUtil.info(CLASS_NAME, "Not enough records for pairing");
                return 0;
            }

            // 3. Build bank combo index (principals) and fee index
            Map<String, List<BankCombo>> comboIndex = buildBankComboIndex(bankRecords);
            Map<String, List<FormRow>> bankFeeIndex = buildBankFeeIndex(bankRecords);

            // 4. Match secu records against combo index (Phase 1 principal + Phase 2 fee)
            int pairsCreated = 0;
            for (FormRow secuRecord : secuRecords) {
                boolean paired = tryPairSecuRecord(secuRecord, comboIndex, bankFeeIndex, formDataDao);
                if (paired) {
                    pairsCreated++;
                }
            }

            LogUtil.info(CLASS_NAME, "Pairing complete: " + pairsCreated + " pairs created");
            return pairsCreated;

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error during transaction pairing");
            return 0;
        }
    }

    /**
     * Build an index of bank principal combos by date.
     * Only indexes SEC_BUY/SEC_SELL records (principals).
     * Fee matching is handled separately in Phase 2.
     */
    private Map<String, List<BankCombo>> buildBankComboIndex(List<FormRow> bankRecords) {
        Map<String, List<BankCombo>> comboIndex = new HashMap<>();

        for (FormRow row : bankRecords) {
            if (!isBankPrincipal(row)) continue;

            String date = row.getProperty("transaction_date");
            if (date == null || date.isEmpty()) {
                date = row.getProperty("settlement_date");
            }
            if (date == null || date.isEmpty()) continue;

            double amount = parseAmount(row.getProperty("original_amount"));
            comboIndex.computeIfAbsent(date, k -> new ArrayList<>())
                    .add(new BankCombo(row, null, amount, date));
        }

        LogUtil.info(CLASS_NAME, "Bank combo index built with " + comboIndex.size() + " distinct date keys");
        return comboIndex;
    }

    /**
     * Build an index of unpaired COMM_FEE bank records by transaction_date.
     */
    private Map<String, List<FormRow>> buildBankFeeIndex(List<FormRow> bankRecords) {
        Map<String, List<FormRow>> feeIndex = new HashMap<>();
        for (FormRow row : bankRecords) {
            if (!isBankFee(row)) continue;
            String pairId = row.getProperty("pair_id");
            if (pairId != null && !pairId.isEmpty()) continue;

            String date = row.getProperty("transaction_date");
            if (date == null || date.isEmpty()) continue;
            feeIndex.computeIfAbsent(date, k -> new ArrayList<>()).add(row);
        }
        int totalFees = feeIndex.values().stream().mapToInt(List::size).sum();
        LogUtil.info(CLASS_NAME, String.format(
                "Bank fee index built: %d COMM_FEE records across %d distinct date keys",
                totalFees, feeIndex.size()));
        return feeIndex;
    }

    /**
     * Try to pair a secu record with a matching bank combo by amount + date.
     */
    private boolean tryPairSecuRecord(FormRow secuRecord,
                                       Map<String, List<BankCombo>> comboIndex,
                                       Map<String, List<FormRow>> bankFeeIndex,
                                       FormDataDao formDataDao) {
        // Defensive guard: skip unresolved assets
        String resolvedAssetId = secuRecord.getProperty("resolved_asset_id");
        if (resolvedAssetId == null || resolvedAssetId.isEmpty() || "UNKNOWN".equals(resolvedAssetId)) {
            return false;
        }

        // Get secu amount and settlement date
        String secuAmountStr = secuRecord.getProperty("original_amount");
        if (secuAmountStr == null) return false;
        double secuAmount = parseAmount(secuAmountStr);

        String settlementDate = secuRecord.getProperty("settlement_date");
        if (settlementDate == null) {
            settlementDate = secuRecord.getProperty("transaction_date");
        }
        if (settlementDate == null) return false;

        String secuCurrency = secuRecord.getProperty("original_currency");

        // Collect all combos from settlement_date, settlement_date-1, settlement_date+1
        List<BankCombo> candidateCombos = new ArrayList<>();
        collectCombosForDate(comboIndex, settlementDate, candidateCombos);
        try {
            Date date = DATE_FORMAT.parse(settlementDate);
            Calendar cal = Calendar.getInstance();

            cal.setTime(date);
            cal.add(Calendar.DAY_OF_MONTH, -1);
            collectCombosForDate(comboIndex, DATE_FORMAT.format(cal.getTime()), candidateCombos);

            cal.setTime(date);
            cal.add(Calendar.DAY_OF_MONTH, 1);
            collectCombosForDate(comboIndex, DATE_FORMAT.format(cal.getTime()), candidateCombos);
        } catch (Exception e) {
            LogUtil.warn(CLASS_NAME, "Error parsing date for tolerance check: " + settlementDate);
        }

        // Filter combos: exact amount match, same sign, same currency
        List<BankCombo> matchingCombos = new ArrayList<>();
        for (BankCombo combo : candidateCombos) {
            // Exact amount match (0.01 rounding guard)
            if (Math.abs(secuAmount - combo.comboAmount) >= 0.01) continue;

            // Sign matching: both must be same sign (negative = buy, positive = sell)
            if (Math.signum(secuAmount) != Math.signum(combo.comboAmount) && secuAmount != 0) continue;

            // Currency matching
            String bankCurrency = combo.principal.getProperty("original_currency");
            if (secuCurrency != null && bankCurrency != null && !secuCurrency.equals(bankCurrency)) continue;

            matchingCombos.add(combo);
        }

        if (matchingCombos.isEmpty()) return false;

        if (matchingCombos.size() > 1) {
            LogUtil.warn(CLASS_NAME, String.format(
                    "Ambiguous match for secu=%s: %d matching bank combos with amount=%.2f on date %s. Skipping.",
                    secuRecord.getId(), matchingCombos.size(), secuAmount, settlementDate));
            return false;
        }

        // Exactly 1 match
        BankCombo match = matchingCombos.get(0);

        // Phase 2: Resolve fee if secu expects one
        FormRow feeRecord = null;
        LogUtil.debug(CLASS_NAME, String.format(
                "Phase 2 check for secu=%s: has_fee=%s, fee_amount=%s, total_amount=%s, original_amount=%s",
                secuRecord.getId(),
                secuRecord.getProperty("has_fee"),
                secuRecord.getProperty("fee_amount"),
                secuRecord.getProperty("total_amount"),
                secuRecord.getProperty("original_amount")));
        boolean expectsFee = detectHasFee(secuRecord);
        if (expectsFee) {
            feeRecord = findMatchingFee(secuRecord, bankFeeIndex, match.date);
            if (feeRecord == null) {
                // Full pairing requirement: do NOT pair without fee
                LogUtil.info(CLASS_NAME, String.format(
                        "Deferred pairing for secu=%s: fee expected but no matching COMM_FEE found",
                        secuRecord.getId()));
                return false;
            }
        }

        // Compute date offset
        int dateOffset = computeDateOffset(settlementDate, match.date);

        // Cross-verify source references
        boolean refsOverlap = verifySourceReferences(secuRecord, match.principal);

        // Create the pair — always AUTO_ACCEPTED for exact amount match
        String pairId = UUID.randomUUID().toString();
        String timestamp = DATE_FORMAT.format(new Date());
        String ticker = extractTickerFromDescription(match.principal.getProperty("description"));

        String bankPayDate = match.principal.getProperty("transaction_date");
        createPairRecord(formDataDao, pairId, secuRecord, match.principal,
                feeRecord, ticker, settlementDate, bankPayDate, dateOffset,
                refsOverlap, "AUTO_ACCEPTED", timestamp);

        // Update secu F01.05
        secuRecord.setProperty("pair_id", pairId);
        if (feeRecord != null) {
            secuRecord.setProperty("fee_trx_id", feeRecord.getId());
        }
        saveRow(formDataDao, DomainConstants.TABLE_TRX_ENRICHMENT, secuRecord);

        // Update bank principal F01.05
        match.principal.setProperty("pair_id", pairId);
        saveRow(formDataDao, DomainConstants.TABLE_TRX_ENRICHMENT, match.principal);

        // Update bank fee F01.05 if exists
        if (feeRecord != null) {
            feeRecord.setProperty("pair_id", pairId);
            saveRow(formDataDao, DomainConstants.TABLE_TRX_ENRICHMENT, feeRecord);
            removeFeeFromIndex(bankFeeIndex, feeRecord);
        }

        // Transition matched records to PAIRED
        transitionToPaired(formDataDao, secuRecord.getId());
        transitionToPaired(formDataDao, match.principal.getId());
        if (feeRecord != null) {
            transitionToPaired(formDataDao, feeRecord.getId());
        }

        // Remove matched combos from index to prevent double-matching
        List<BankCombo> dateCombos = comboIndex.get(match.date);
        if (dateCombos != null) {
            dateCombos.removeIf(c -> c.principal == match.principal);
        }

        LogUtil.info(CLASS_NAME, String.format(
                "Paired: secu=%s with bank=%s (amount=%.2f, date=%s, fee=%s, status=AUTO_ACCEPTED)",
                secuRecord.getId(), match.principal.getId(), secuAmount, settlementDate,
                feeRecord != null ? feeRecord.getId() : "none"));

        return true;
    }

    /**
     * Phase 2: Find a matching COMM_FEE bank record for a secu record's fee.
     * Searches ±2 days from the principal date. Returns null if no unique match.
     */
    private FormRow findMatchingFee(FormRow secuRecord, Map<String, List<FormRow>> bankFeeIndex, String principalDate) {
        double expectedFee = resolveFeeAmount(secuRecord);
        if (expectedFee < 0.01) {
            LogUtil.warn(CLASS_NAME, String.format(
                    "Fee expected for secu=%s but could not resolve fee amount", secuRecord.getId()));
            return null;
        }

        String secuCurrency = secuRecord.getProperty("original_currency");

        // Collect fee candidates from ±2 days
        List<FormRow> candidates = new ArrayList<>();
        collectFeesForDateRange(bankFeeIndex, principalDate, 2, candidates);

        // Filter by amount (within 0.01), currency, and not already paired
        List<FormRow> matches = new ArrayList<>();
        for (FormRow fee : candidates) {
            String feePairId = fee.getProperty("pair_id");
            if (feePairId != null && !feePairId.isEmpty()) continue;

            double feeAmount = Math.abs(parseAmount(fee.getProperty("original_amount")));
            if (Math.abs(expectedFee - feeAmount) >= 0.01) continue;

            String feeCurrency = fee.getProperty("original_currency");
            if (secuCurrency != null && feeCurrency != null && !secuCurrency.equals(feeCurrency)) continue;

            matches.add(fee);
        }

        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return matches.get(0);

        // Ambiguous: try ticker disambiguation
        String secuTicker = extractTickerFromSecu(secuRecord);
        if (secuTicker != null) {
            List<FormRow> tickerMatches = new ArrayList<>();
            for (FormRow fee : matches) {
                String feeTicker = TickerExtractor.extractFromDescription(fee.getProperty("description"));
                if (secuTicker.equals(feeTicker)) {
                    tickerMatches.add(fee);
                }
            }
            if (tickerMatches.size() == 1) return tickerMatches.get(0);
        }

        LogUtil.warn(CLASS_NAME, String.format(
                "Ambiguous fee match for secu=%s: %d COMM_FEE candidates with amount=%.2f. Skipping fee match.",
                secuRecord.getId(), matches.size(), expectedFee));
        return null;
    }

    /**
     * Collect fee records from the fee index within ±rangeDays of centerDate.
     */
    private void collectFeesForDateRange(Map<String, List<FormRow>> feeIndex,
                                          String centerDate, int rangeDays,
                                          List<FormRow> target) {
        try {
            Date date = DATE_FORMAT.parse(centerDate);
            Calendar cal = Calendar.getInstance();
            for (int offset = -rangeDays; offset <= rangeDays; offset++) {
                cal.setTime(date);
                cal.add(Calendar.DAY_OF_MONTH, offset);
                String key = DATE_FORMAT.format(cal.getTime());
                List<FormRow> fees = feeIndex.get(key);
                if (fees != null) {
                    target.addAll(fees);
                }
            }
        } catch (Exception e) {
            // Fallback to exact date on parse error
            List<FormRow> fees = feeIndex.get(centerDate);
            if (fees != null) {
                target.addAll(fees);
            }
        }
    }

    /**
     * Extract ticker from a secu record: check bank_asset_hint_ticker first,
     * then fallback to "ticker: XXX" pattern in description.
     */
    private String extractTickerFromSecu(FormRow secuRecord) {
        String hint = secuRecord.getProperty("bank_asset_hint_ticker");
        if (hint != null && !hint.isEmpty()) return hint.toUpperCase();

        String desc = secuRecord.getProperty("description");
        if (desc != null) {
            int idx = desc.toLowerCase().indexOf("ticker:");
            if (idx >= 0) {
                String after = desc.substring(idx + 7).trim();
                String[] parts = after.split("\\s+", 2);
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    return parts[0].toUpperCase();
                }
            }
        }
        return null;
    }

    /**
     * Remove a fee record from the fee index to prevent double-matching.
     */
    private void removeFeeFromIndex(Map<String, List<FormRow>> feeIndex, FormRow feeRecord) {
        String date = feeRecord.getProperty("transaction_date");
        if (date == null) return;
        List<FormRow> fees = feeIndex.get(date);
        if (fees != null) {
            fees.removeIf(f -> f.getId().equals(feeRecord.getId()));
        }
    }

    private void collectCombosForDate(Map<String, List<BankCombo>> comboIndex,
                                       String date, List<BankCombo> target) {
        List<BankCombo> combos = comboIndex.get(date);
        if (combos != null) {
            target.addAll(combos);
        }
    }

    private int computeDateOffset(String secuSettlementDate, String bankDate) {
        try {
            Date secu = DATE_FORMAT.parse(secuSettlementDate);
            Date bank = DATE_FORMAT.parse(bankDate);
            long diffMs = bank.getTime() - secu.getTime();
            return (int) (diffMs / (24 * 60 * 60 * 1000));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Extract ticker from bank description (for audit trail only).
     * Delegates to shared TickerExtractor utility.
     */
    String extractTickerFromDescription(String description) {
        return TickerExtractor.extractFromDescription(description);
    }

    /**
     * Check if a bank record should be skipped based on internal_type.
     * DIV_INCOME and INCOME_TAX are bank-only records with no secu counterpart.
     */
    private boolean shouldSkipBankRecord(FormRow bankRecord) {
        String type = bankRecord.getProperty("internal_type");
        return DomainConstants.INTERNAL_TYPE_DIV_INCOME.equals(type)
                || DomainConstants.INTERNAL_TYPE_INCOME_TAX.equals(type);
    }

    private boolean isBankPrincipal(FormRow bankRecord) {
        String type = bankRecord.getProperty("internal_type");
        return "SEC_BUY".equals(type) || "SEC_SELL".equals(type);
    }

    private boolean isBankFee(FormRow bankRecord) {
        return "COMM_FEE".equals(bankRecord.getProperty("internal_type"));
    }

    /**
     * Check if a secu record is a split transaction (amount=0, custodian counterparty).
     * Split transactions should be excluded from pairing.
     */
    private boolean isSplitTransaction(FormRow secuRecord) {
        String amount = secuRecord.getProperty("original_amount");
        boolean zeroAmount = "0".equals(amount) || "0.00".equals(amount) || "0.0".equals(amount);
        if (!zeroAmount) return false;
        // Custodian counterparty type confirms corporate action (not a cancelled trade)
        String custodianId = secuRecord.getProperty("custodian_id");
        return custodianId != null && !custodianId.isEmpty();
    }

    /**
     * Cross-verify source references between secu and bank records.
     */
    private boolean verifySourceReferences(FormRow secuRecord, FormRow bankRecord) {
        String secuRefs = secuRecord.getProperty("source_reference");
        String bankRefs = bankRecord.getProperty("source_reference");

        if (secuRefs == null || secuRefs.isEmpty() || bankRefs == null || bankRefs.isEmpty()) {
            return false;
        }

        Set<String> secuRefSet = new HashSet<>(Arrays.asList(secuRefs.split(",")));
        Set<String> bankRefSet = new HashSet<>(Arrays.asList(bankRefs.split(",")));
        secuRefSet.retainAll(bankRefSet);
        return !secuRefSet.isEmpty();
    }

    /**
     * Create a PAIR entity record in the trx_pair table.
     */
    private void createPairRecord(FormDataDao formDataDao, String pairId,
                                   FormRow secuRecord, FormRow principalRecord,
                                   FormRow feeRecord, String ticker,
                                   String secuSettleDate, String bankPayDate,
                                   int dateOffset, boolean refsOverlap,
                                   String pairStatus, String timestamp) {
        try {
            FormRow pairRow = new FormRow();
            pairRow.setId(pairId);

            pairRow.setProperty("secu_enrichment_id", secuRecord.getId());
            pairRow.setProperty("bank_principal_enrichment_id", principalRecord.getId());
            pairRow.setProperty("bank_fee_enrichment_id",
                    feeRecord != null ? feeRecord.getId() : "");
            pairRow.setProperty("ticker",
                    ticker != null ? ticker : "");
            pairRow.setProperty("pair_date_match", secuSettleDate);
            pairRow.setProperty("secu_settle_date", secuSettleDate);
            pairRow.setProperty("bank_pay_date", bankPayDate != null ? bankPayDate : "");
            pairRow.setProperty("date_offset", String.valueOf(dateOffset));
            pairRow.setProperty("secu_amount", secuRecord.getProperty("original_amount"));
            pairRow.setProperty("bank_amount", principalRecord.getProperty("original_amount"));
            pairRow.setProperty("currency", secuRecord.getProperty("original_currency"));
            pairRow.setProperty("has_fee", feeRecord != null ? "yes" : "no");
            pairRow.setProperty("fee_amount", feeRecord != null ? feeRecord.getProperty("original_amount") : "");
            pairRow.setProperty("references_overlap", refsOverlap ? "yes" : "no");
            pairRow.setProperty("pair_date", timestamp);
            pairRow.setProperty("status", pairStatus);

            FormRowSet rowSet = new FormRowSet();
            rowSet.add(pairRow);
            formDataDao.saveOrUpdate(null, DomainConstants.TABLE_TRX_PAIR, rowSet);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error creating pair record: " + pairId);
        }
    }

    /**
     * Save a single FormRow to the given table.
     */
    private void saveRow(FormDataDao formDataDao, String table, FormRow row) {
        try {
            FormRowSet rowSet = new FormRowSet();
            rowSet.add(row);
            formDataDao.saveOrUpdate(null, table, rowSet);
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error saving row " + row.getId() + " to " + table);
        }
    }

    /**
     * Transition an enrichment record from ENRICHED to PAIRED via StatusManager.
     */
    private void transitionToPaired(FormDataDao formDataDao, String recordId) {
        if (statusManager == null) return;
        try {
            statusManager.transition(formDataDao, DomainConstants.TABLE_TRX_ENRICHMENT,
                    EntityType.ENRICHMENT, recordId,
                    Status.PAIRED, "rows-enrichment", "Paired with cross-statement match");
        } catch (Exception e) {
            LogUtil.warn(CLASS_NAME, "Could not transition to PAIRED for: " + recordId);
        }
    }

    /**
     * Detect whether a secu record expects a fee using a 3-signal cascade.
     * Signal 1: has_fee property (explicit flag from persister)
     * Signal 2: fee_amount property (non-null, abs >= 0.01)
     * Signal 3: |total_amount| - |original_amount| >= 0.01 (computed difference)
     */
    private boolean detectHasFee(FormRow secuRecord) {
        String hasFeeFlag = secuRecord.getProperty("has_fee");
        if ("yes".equals(hasFeeFlag)) {
            return true;
        }
        if ("no".equals(hasFeeFlag)) {
            return false;
        }

        // Signal 2: fee_amount property
        String feeAmountStr = secuRecord.getProperty("fee_amount");
        if (feeAmountStr != null && !feeAmountStr.isEmpty()) {
            double feeAmt = Math.abs(parseAmount(feeAmountStr));
            if (feeAmt >= 0.01) {
                LogUtil.info(CLASS_NAME, String.format(
                        "Fee detected via fee_amount fallback for secu=%s: fee_amount=%s (has_fee was null)",
                        secuRecord.getId(), feeAmountStr));
                return true;
            }
        }

        // Signal 3: total_amount vs original_amount difference
        String totalStr = secuRecord.getProperty("total_amount");
        String originalStr = secuRecord.getProperty("original_amount");
        if (totalStr != null && originalStr != null) {
            double diff = Math.abs(parseAmount(totalStr)) - Math.abs(parseAmount(originalStr));
            if (diff >= 0.01) {
                LogUtil.info(CLASS_NAME, String.format(
                        "Fee detected via total/original diff for secu=%s: total=%s, original=%s, diff=%.2f (has_fee was null)",
                        secuRecord.getId(), totalStr, originalStr, diff));
                return true;
            }
        }

        return false;
    }

    /**
     * Resolve the expected fee amount from a secu record using a 2-signal cascade.
     * Signal 1: fee_amount property (direct)
     * Signal 2: |total_amount| - |original_amount| (computed)
     * Returns 0.0 if neither signal yields a valid fee.
     */
    private double resolveFeeAmount(FormRow secuRecord) {
        // Signal 1: fee_amount property
        String feeAmountStr = secuRecord.getProperty("fee_amount");
        if (feeAmountStr != null && !feeAmountStr.isEmpty()) {
            double fee = Math.abs(parseAmount(feeAmountStr));
            if (fee >= 0.01) {
                return fee;
            }
        }

        // Signal 2: total_amount - original_amount
        String totalStr = secuRecord.getProperty("total_amount");
        String originalStr = secuRecord.getProperty("original_amount");
        if (totalStr != null && originalStr != null) {
            double diff = Math.abs(parseAmount(totalStr)) - Math.abs(parseAmount(originalStr));
            if (diff >= 0.01) {
                LogUtil.info(CLASS_NAME, String.format(
                        "Fee amount resolved via total/original diff for secu=%s: %.2f (fee_amount was null/empty)",
                        secuRecord.getId(), diff));
                return diff;
            }
        }

        return 0.0;
    }

    private double parseAmount(String amountStr) {
        if (amountStr == null) return 0;
        try {
            return Double.parseDouble(amountStr.replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Bank combo: a principal record optionally combined with a fee record.
     */
    private static class BankCombo {
        final FormRow principal;
        final FormRow fee;       // null if no fee
        final double comboAmount; // principal + fee (or just principal)
        final String date;

        BankCombo(FormRow principal, FormRow fee, double comboAmount, String date) {
            this.principal = principal;
            this.fee = fee;
            this.comboAmount = comboAmount;
            this.date = date;
        }
    }
}
