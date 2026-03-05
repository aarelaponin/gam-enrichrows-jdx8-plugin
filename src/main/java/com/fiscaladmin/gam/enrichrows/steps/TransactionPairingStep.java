package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
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
 * Cross-statement pairing step.
 * Matches consolidated secu F01.05 records with consolidated bank F01.05 records
 * using amount + date matching: secu original_amount must exactly equal
 * bank principal original_amount + bank fee original_amount.
 *
 * Bank records are classified by internal_type (SEC_BUY, SEC_SELL, COMM_FEE)
 * instead of description parsing.
 *
 * Pairing algorithm:
 * 1. Load all F01.05 records where status = ENRICHED AND pair_id IS NULL
 * 2. Separate into secu and bank lists
 * 3. Build bank combo index by date (principal + optional fee combos)
 * 4. For each secu record, find matching combo by amount + date (±1 day)
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

            // 3. Build bank combo index by date
            Map<String, List<BankCombo>> comboIndex = buildBankComboIndex(bankRecords);

            // 4. Match secu records against combo index
            int pairsCreated = 0;
            for (FormRow secuRecord : secuRecords) {
                boolean paired = tryPairSecuRecord(secuRecord, comboIndex, formDataDao);
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
     * Build an index of bank combos by date.
     * Groups bank records by transaction_date, classifies by internal_type,
     * and builds principal + optional fee combinations.
     */
    private Map<String, List<BankCombo>> buildBankComboIndex(List<FormRow> bankRecords) {
        // Group bank records by transaction_date
        Map<String, List<FormRow>> byDate = new HashMap<>();
        for (FormRow row : bankRecords) {
            if (shouldSkipBankRecord(row)) continue;
            String date = row.getProperty("transaction_date");
            if (date == null || date.isEmpty()) {
                date = row.getProperty("settlement_date");
            }
            if (date == null || date.isEmpty()) continue;
            byDate.computeIfAbsent(date, k -> new ArrayList<>()).add(row);
        }

        // Build combos for each date
        Map<String, List<BankCombo>> comboIndex = new HashMap<>();
        for (Map.Entry<String, List<FormRow>> entry : byDate.entrySet()) {
            String date = entry.getKey();
            List<FormRow> records = entry.getValue();

            List<FormRow> principals = new ArrayList<>();
            List<FormRow> fees = new ArrayList<>();

            for (FormRow row : records) {
                if (isBankPrincipal(row)) {
                    principals.add(row);
                } else if (isBankFee(row)) {
                    fees.add(row);
                }
                // Skip records that are neither principal nor fee (null/unknown internal_type)
            }

            List<BankCombo> combos = new ArrayList<>();
            for (FormRow principal : principals) {
                double principalAmount = parseAmount(principal.getProperty("original_amount"));

                // Create combos with each fee on the same date
                for (FormRow fee : fees) {
                    double feeAmount = parseAmount(fee.getProperty("original_amount"));
                    combos.add(new BankCombo(principal, fee, principalAmount + feeAmount, date));
                }

                // Create no-fee combo (e.g., LHV1T where no commission is charged)
                combos.add(new BankCombo(principal, null, principalAmount, date));
            }

            if (!combos.isEmpty()) {
                comboIndex.put(date, combos);
            }
        }

        LogUtil.info(CLASS_NAME, "Bank combo index built with " + comboIndex.size() + " distinct date keys");
        return comboIndex;
    }

    /**
     * Try to pair a secu record with a matching bank combo by amount + date.
     */
    private boolean tryPairSecuRecord(FormRow secuRecord,
                                       Map<String, List<BankCombo>> comboIndex,
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
                match.fee, ticker, settlementDate, bankPayDate, dateOffset,
                refsOverlap, "AUTO_ACCEPTED", timestamp);

        // Update secu F01.05
        secuRecord.setProperty("pair_id", pairId);
        if (match.fee != null) {
            secuRecord.setProperty("fee_trx_id", match.fee.getId());
        }
        saveRow(formDataDao, DomainConstants.TABLE_TRX_ENRICHMENT, secuRecord);

        // Update bank principal F01.05
        match.principal.setProperty("pair_id", pairId);
        saveRow(formDataDao, DomainConstants.TABLE_TRX_ENRICHMENT, match.principal);

        // Update bank fee F01.05 if exists
        if (match.fee != null) {
            match.fee.setProperty("pair_id", pairId);
            saveRow(formDataDao, DomainConstants.TABLE_TRX_ENRICHMENT, match.fee);
        }

        // Transition matched records to PAIRED
        transitionToPaired(formDataDao, secuRecord.getId());
        transitionToPaired(formDataDao, match.principal.getId());
        if (match.fee != null) {
            transitionToPaired(formDataDao, match.fee.getId());
        }

        // Remove matched combos from index to prevent double-matching
        List<BankCombo> dateCombos = comboIndex.get(match.date);
        if (dateCombos != null) {
            dateCombos.removeIf(c -> c.principal == match.principal);
        }

        LogUtil.info(CLASS_NAME, String.format(
                "Paired: secu=%s with bank=%s (amount=%.2f, date=%s, status=AUTO_ACCEPTED)",
                secuRecord.getId(), match.principal.getId(), secuAmount, settlementDate));

        return true;
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
     * Pattern: "Securities buy (TICKER)" or "Securities commission fee (TICKER)"
     */
    String extractTickerFromDescription(String description) {
        if (description == null) return null;
        int open = description.indexOf('(');
        int close = description.indexOf(')');
        if (open >= 0 && close > open) {
            return description.substring(open + 1, close).trim();
        }
        return null;
    }

    /**
     * Check if a bank record should be skipped based on internal_type.
     * DIV_INCOME and INCOME_TAX are bank-only records with no secu counterpart.
     */
    private boolean shouldSkipBankRecord(FormRow bankRecord) {
        String type = bankRecord.getProperty("internal_type");
        return "DIV_INCOME".equals(type) || "INCOME_TAX".equals(type);
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
