package com.fiscaladmin.gam.enrichrows.persister;

import com.fiscaladmin.gam.enrichrows.framework.*;
import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;
import com.fiscaladmin.gam.framework.status.EntityType;
import com.fiscaladmin.gam.framework.status.InvalidTransitionException;
import com.fiscaladmin.gam.framework.status.Status;
import com.fiscaladmin.gam.framework.status.StatusManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import java.util.*;
import java.text.SimpleDateFormat;
import java.text.ParseException;

/**
 * Data persister for enriched GL transactions.
 * This replaces the EnrichmentPersistenceStep and separates persistence from processing.
 * 
 * Saves enriched transaction data to the trx_enrichment table with:
 * - Core transaction data
 * - Entity identification (counterparty, customer)
 * - Transaction classification (F14 internal type)
 * - FX conversion details
 * - Processing metadata
 */
public class EnrichmentDataPersister extends AbstractDataPersister<DataContext> {
    
    private static final String CLASS_NAME = EnrichmentDataPersister.class.getName();
    private static final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private StatusManager statusManager;

    public void setStatusManager(StatusManager statusManager) {
        this.statusManager = statusManager;
    }


    @Override
    public String getPersisterName() {
        return "GL Transaction Enrichment Persister";
    }
    
    @Override
    public String getTargetStorage() {
        return DomainConstants.TABLE_TRX_ENRICHMENT;
    }
    
    @Override
    public boolean validateStorage(FormDataDao dao) {
        try {
            // Try to query the target table - use configuration if available
            String targetTable = DomainConstants.TABLE_TRX_ENRICHMENT;
            dao.find(null, targetTable, null, null, null, false, 0, 1);
            return true;
        } catch (Exception e) {
            String targetTable = DomainConstants.TABLE_TRX_ENRICHMENT;
            LogUtil.error(CLASS_NAME, e, "Target table not found: " + targetTable);
            return false;
        }
    }
    
    @Override
    public PersistenceResult persist(DataContext context, FormDataDao dao,
                                    Map<String, Object> parameters) {
        try {
            // Create the enriched record with all 52 F01.05 fields
            FormRow enrichedRow = createEnrichedRecord(context, parameters);

            // Determine processing status
            boolean needsManualReview = determineManualReviewStatus(context, parameters);

            // Save the record first
            String targetTable = DomainConstants.TABLE_TRX_ENRICHMENT;
            boolean saved = saveFormRow(dao, targetTable, enrichedRow);

            if (saved) {
                String recordId = enrichedRow.getId();
                Status targetStatus = needsManualReview ? Status.MANUAL_REVIEW : Status.ENRICHED;

                // Transition enrichment record through lifecycle via StatusManager
                transitionEnrichment(dao, recordId, targetStatus, needsManualReview);

                // Update context with persistence outcome (used by orchestrator for post-persistence logic)
                context.setAdditionalDataValue("enriched_record_id", recordId);
                context.setAdditionalDataValue("needs_manual_review", needsManualReview);

                // Update source row with enrichment reference
                updateSourceRowWithEnrichmentRef(context, dao, recordId);

                // Transition source transaction to mirror enrichment status
                transitionSourceTransaction(context, dao, targetStatus);

                PersistenceResult result = new PersistenceResult(true, recordId,
                    "Enrichment persisted successfully");
                result.setTargetStorage(targetTable);
                result.addMetadata("needs_manual_review", needsManualReview);
                result.addMetadata("processing_status", targetStatus.getCode());

                return result;
            } else {
                return new PersistenceResult(false, null,
                    "Failed to save enriched record");
            }

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                "Error persisting enrichment for transaction: " + context.getTransactionId());
            return new PersistenceResult(false, null,
                "Persistence error: " + e.getMessage());
        }
    }
    
    /**
     * Create the enriched record with all 52 F01.05 fields.
     */
    private FormRow createEnrichedRecord(DataContext context, Map<String, Object> config) {
        FormRow row = createFormRow();
        String enrichedId = "TRX-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        row.setId(enrichedId);

        Map<String, Object> data = context.getAdditionalData();
        if (data == null) data = new HashMap<>();
        if (config == null) config = new HashMap<>();

        boolean isSecu = DomainConstants.SOURCE_TYPE_SECU.equals(context.getSourceType());

        // ===== PROVENANCE (10 fields) =====
        setPropertySafe(row, "source_tp", context.getSourceType());                        // 1
        setPropertySafe(row, "statement_id", context.getStatementId());                    // 2
        setPropertySafe(row, "statement_date", context.getStatementDate());                  // 3
        setPropertySafe(row, "source_trx_id", context.getTransactionId());                 // 4
        setPropertySafe(row, "origin", "auto");                                            // 5
        setPropertySafe(row, "lineage_note", buildLineageNote(context, config));            // 6
        // 7-10: acc_post_id, parent_enrichment_id, group_id, split_sequence = NULL

        // ===== TRANSACTION CORE (8 fields) =====
        setPropertySafe(row, "transaction_date", context.getTransactionDate());             // 11
        setPropertySafe(row, "settlement_date", computeSettlementDate(context, config));    // 12
        setPropertySafe(row, "debit_credit", computeDebitCredit(context, config));          // 13
        setPropertySafe(row, "description", buildDescription(context, config));             // 14
        setPropertySafe(row, "original_amount", context.getAmount());                       // 15
        if (isSecu) {                                                                       // 16
            setPropertySafe(row, "fee_amount", context.getFee());
        }
        String totalAmount = isSecu ? context.getTotalAmount() : context.getAmount();       // 17
        setPropertySafe(row, "total_amount", totalAmount);
        setPropertySafe(row, "original_currency", context.getCurrency());                   // 18

        // ===== CLASSIFICATION (3 fields) =====
        setPropertySafe(row, "internal_type", data.get("internal_type"));                   // 19
        setPropertySafe(row, "type_confidence", computeTypeConfidence(context, config));     // 20
        setPropertySafe(row, "matched_rule_id", data.get("f14_rule_id"));                   // 21

        // ===== CUSTOMER (4 fields) =====
        setPropertySafe(row, "resolved_customer_id", context.getCustomerId());              // 22
        setPropertySafe(row, "customer_match_method", mapCustomerMatchMethod(data));         // 23
        setPropertySafe(row, "customer_code", data.get("customer_code"));                   // 24
        setPropertySafe(row, "customer_display_name", data.get("customer_name"));           // 25

        // ===== ASSET (6 fields, secu only) =====
        if (isSecu) {
            setPropertySafe(row, "resolved_asset_id", data.get("asset_id"));                // 26
            setPropertySafe(row, "asset_isin", data.get("asset_isin"));                     // 27
            setPropertySafe(row, "asset_category", data.get("asset_category"));             // 28
            setPropertySafe(row, "asset_class", data.get("asset_class"));                   // 29
            setPropertySafe(row, "asset_base_currency", data.get("asset_base_currency"));   // 30
            String mismatch = getStringValue(data.get("currency_mismatch_flag"));           // 31
            setPropertySafe(row, "currency_mismatch_flag",
                    "true".equals(mismatch) || "yes".equals(mismatch) ? "yes" : "no");
        }

        // ===== COUNTERPARTY (7 fields) =====
        routeCounterparty(row, data);                                                       // 32-37
        setPropertySafe(row, "counterparty_source", "statement_bank");                      // 38

        // ===== CURRENCY & FX (5 fields) =====
        setPropertySafe(row, "validated_currency", context.getCurrency());                  // 39
        setPropertySafe(row, "fx_rate_source", data.get("fx_rate_source"));                 // 40
        String currency = context.getCurrency();                                            // 41
        setPropertySafe(row, "requires_eur_parallel",
                !"EUR".equals(currency) ? "yes" : "no");
        setPropertySafe(row, "fx_rate_to_eur", data.get("fx_rate"));                        // 42
        setPropertySafe(row, "fx_rate_date", data.get("fx_rate_date"));                     // 43

        // ===== FEE & PAIRING (4 fields) =====
        setPropertySafe(row, "base_amount_eur", context.getBaseAmount());                   // 44
        if (isSecu) {
            setPropertySafe(row, "base_fee_eur", data.get("base_fee"));                     // 44b
        }
        String feeAmount = context.getFee();                                                // 45
        boolean hasFee = feeAmount != null && !feeAmount.isEmpty()
                && !"0".equals(feeAmount) && !"0.00".equals(feeAmount);
        setPropertySafe(row, "has_fee", hasFee ? "yes" : "no");
        // Source references (GROUP_CONCAT'd from consolidation) — for audit trail
        if (isSecu) {
            setPropertySafe(row, "source_reference", context.getReference());
        } else {
            FormRow trxRow2 = context.getTransactionRow();
            if (trxRow2 != null) {
                setPropertySafe(row, "source_reference",
                    trxRow2.getProperty("transaction_reference"));
            }
        }
        // 46-47: fee_trx_id, pair_id = NULL

        // ===== STATUS & NOTES (5 fields) =====
        // 48: status — handled by StatusManager transitions, not set here
        setPropertySafe(row, "enrichment_timestamp", TIMESTAMP_FORMAT.format(new Date()));  // 49
        setPropertySafe(row, "error_message", context.getErrorMessage());                   // 50
        setPropertySafe(row, "processing_notes", buildProcessingNotes(context));             // 51
        setPropertySafe(row, "version", "1");                                               // 52

        return row;
    }

    // ===== HELPER METHODS =====

    /**
     * Build description from configurable field lists per source type.
     * Format: "field_label: value | field_label: value | ..."
     */
    String buildDescription(DataContext context, Map<String, Object> config) {
        String fieldList;
        if (DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType())) {
            fieldList = getConfigString(config, "bankDescriptionFields",
                    "payment_description,reference_number,other_side_name,other_side_bic,other_side_account");
        } else {
            fieldList = getConfigString(config, "secuDescriptionFields",
                    "description,reference,ticker,quantity,price");
        }

        int maxLength = getConfigInt(config, "descriptionMaxLength", 2000);
        FormRow trxRow = context.getTransactionRow();
        if (trxRow == null) return "";

        StringBuilder sb = new StringBuilder();
        String[] fields = fieldList.split(",");
        for (String field : fields) {
            field = field.trim();
            String value = trxRow.getProperty(field);
            if (value != null && !value.isEmpty()) {
                String segment = field + ": " + value;
                String separator = sb.length() > 0 ? " | " : "";
                if (sb.length() + separator.length() + segment.length() > maxLength) {
                    if (sb.length() == 0) {
                        sb.append(segment, 0, maxLength); // first field truncated as last resort
                    }
                    break;
                }
                if (sb.length() > 0) sb.append(" | ");
                sb.append(segment);
            }
        }

        return sb.toString();
    }

    /**
     * Compute settlement date: bank = same as transaction_date, secu = T+N (skip weekends).
     */
    String computeSettlementDate(DataContext context, Map<String, Object> config) {
        String txDate = context.getTransactionDate();
        if (txDate == null || txDate.isEmpty()) return null;

        if (DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType())) {
            return txDate; // T+0 for bank
        }

        int days = getConfigInt(config, "settlementDays", 2);
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            Calendar cal = Calendar.getInstance();
            cal.setTime(sdf.parse(txDate));

            int added = 0;
            while (added < days) {
                cal.add(Calendar.DAY_OF_MONTH, 1);
                int dow = cal.get(Calendar.DAY_OF_WEEK);
                if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                    added++;
                }
            }
            return sdf.format(cal.getTime());
        } catch (ParseException e) {
            LogUtil.error(CLASS_NAME, e, "Error computing settlement date for: " + txDate);
            return txDate;
        }
    }

    /**
     * Compute debit/credit: bank = direct from context, secu = configurable mapping.
     */
    String computeDebitCredit(DataContext context, Map<String, Object> config) {
        if (DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType())) {
            return context.getDebitCredit();
        }
        return mapSecuDebitCredit(context.getType(), config);
    }

    /**
     * Map securities transaction type to D/C/N using configurable mapping.
     */
    String mapSecuDebitCredit(String txType, Map<String, Object> config) {
        if (txType == null || txType.isEmpty()) return "N";

        String mappingStr = getConfigString(config, "secuDebitCreditMapping", null);
        if (mappingStr != null && !mappingStr.isEmpty()) {
            try {
                // Try JSON format first: {"BUY":"D","SELL":"C",...}
                if (mappingStr.startsWith("{")) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> mapping = new ObjectMapper().readValue(mappingStr, Map.class);
                    String dc = mapping.get(txType.toUpperCase());
                    return dc != null ? dc : "N";
                }
                // Comma-separated format: BUY:D,SELL:C,...
                for (String pair : mappingStr.split(",")) {
                    String[] parts = pair.trim().split(":");
                    if (parts.length == 2 && parts[0].trim().equalsIgnoreCase(txType)) {
                        return parts[1].trim();
                    }
                }
            } catch (Exception e) {
                LogUtil.error(CLASS_NAME, e, "Error parsing secuDebitCreditMapping");
            }
        }

        // Hardcoded defaults — consider configuring via secuDebitCreditMapping property
        LogUtil.warn(CLASS_NAME, "Using hardcoded D/C fallback for securities type: " + txType);
        switch (txType.toUpperCase()) {
            case "BUY": case "PURCHASE": return "D";
            case "SELL": case "DISPOSE": return "C";
            case "DIVIDEND": case "COUPON": case "INTEREST": return "C";
            case "FEE": case "CUSTODY_FEE": case "SAFEKEEPING": case "TAX": return "D";
            default: return "N";
        }
    }

    /**
     * Compute type_confidence: high/medium/low based on internal_type and customer confidence.
     */
    String computeTypeConfidence(DataContext context, Map<String, Object> config) {
        Map<String, Object> data = context.getAdditionalData();
        if (data == null) return "low";

        String internalType = getStringValue(data.get("internal_type"));
        Object custConfObj = data.get("customer_confidence");
        int custConf = custConfObj instanceof Number ? ((Number) custConfObj).intValue() : 0;
        int highThreshold = getConfigInt(config, "confidenceThresholdHigh", 80);
        int medThreshold = getConfigInt(config, "confidenceThresholdMedium", 50);

        if (!FrameworkConstants.INTERNAL_TYPE_UNMATCHED.equals(internalType) && custConf >= highThreshold) {
            return "high";
        }
        if (custConf >= medThreshold) {
            return "medium";
        }
        return "low";
    }

    /**
     * Build lineage note: "Pipeline v{version}: {N}/{total} steps OK. Steps: {step1},{step2},..."
     */
    String buildLineageNote(DataContext context, Map<String, Object> config) {
        String version = getConfigString(config, "pipelineVersion", "3.0");
        int totalSteps = getConfigInt(config, "pipelineStepCount", 6);
        List<String> steps = context.getProcessedSteps();
        int completed = steps != null ? steps.size() : 0;
        String stepList = steps != null ? String.join(",", steps) : "";
        return String.format("Pipeline v%s: %d/%d steps OK. Steps: %s", version, completed, totalSteps, stepList);
    }

    /**
     * Route counterparty to the correct field pair based on counterparty_type.
     */
    private void routeCounterparty(FormRow row, Map<String, Object> data) {
        String cpType = getStringValue(data.get("counterparty_type"));
        String cpId = getStringValue(data.get("counterparty_id"));
        String cpShortCode = getStringValue(data.get("counterparty_short_code"));

        if ("Custodian".equalsIgnoreCase(cpType)) {
            setPropertySafe(row, "custodian_id", cpId);
            setPropertySafe(row, "custodian_short_code", cpShortCode);
        } else if ("Broker".equalsIgnoreCase(cpType)) {
            setPropertySafe(row, "broker_id", cpId);
            setPropertySafe(row, "broker_short_code", cpShortCode);
        } else {
            // Default to Bank
            setPropertySafe(row, "counterparty_id", cpId);
            setPropertySafe(row, "counterparty_short_code", cpShortCode);
        }
    }

    /**
     * Map customer identification method to spec values.
     */
    private String mapCustomerMatchMethod(Map<String, Object> data) {
        String method = getStringValue(data.get("customer_identification_method"));
        if (method == null) return "unresolved";
        switch (method) {
            case "DIRECT_ID": return "direct_id";
            case "ACCOUNT_NUMBER": return "account_mapping";
            case "REGISTRATION_NUMBER_EXTRACTED": return "registration_number";
            case "NAME_PATTERN": return "name_pattern";
            case "NONE": return "unresolved";
            default: return method.toLowerCase();
        }
    }

    /**
     * Build processing notes summary.
     */
    private String buildProcessingNotes(DataContext context) {
        Map<String, Object> data = context.getAdditionalData();
        if (data == null) data = new HashMap<>();

        List<String> steps = context.getProcessedSteps();
        String stepStr = steps != null ? String.join(",", steps) : "none";
        String custId = context.getCustomerId() != null ? context.getCustomerId() : "N/A";
        Object confObj = data.get("customer_confidence");
        String conf = confObj != null ? confObj.toString() : "0";
        String cpId = getStringValue(data.get("counterparty_id"));
        if (cpId == null) cpId = "N/A";
        String cpType = getStringValue(data.get("counterparty_type"));
        if (cpType == null) cpType = "N/A";
        String intType = getStringValue(data.get("internal_type"));
        if (intType == null) intType = "N/A";

        return String.format("Steps completed: %s. Customer: %s (%s%%). Counterparty: %s (%s). Internal type: %s.",
                stepStr, custId, conf, cpId, cpType, intType);
    }

    // ===== CONFIG HELPERS =====

    private String getConfigString(Map<String, Object> config, String key, String defaultValue) {
        if (config != null && config.containsKey(key)) {
            Object val = config.get(key);
            return val != null ? val.toString() : defaultValue;
        }
        return defaultValue;
    }

    private int getConfigInt(Map<String, Object> config, String key, int defaultValue) {
        if (config != null && config.containsKey(key)) {
            Object val = config.get(key);
            if (val instanceof Number) return ((Number) val).intValue();
            try { return Integer.parseInt(val.toString()); } catch (NumberFormatException e) { /* fall through */ }
        }
        return defaultValue;
    }
    
    /**
     * Determine if manual review is needed (6 conditions per spec Section 14).
     */
    boolean determineManualReviewStatus(DataContext context) {
        return determineManualReviewStatus(context, null);
    }

    /**
     * Determine if manual review is needed.
     * Source-type-aware: for secu, UNKNOWN customer is expected (not an error)
     * because secu transactions have no customer data by design.
     */
    boolean determineManualReviewStatus(DataContext context, Map<String, Object> config) {
        Map<String, Object> data = context.getAdditionalData();
        if (data == null) return true;

        boolean isBank = DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType());

        // 1. UNKNOWN customer — bank only (secu has no customer by design)
        // §4.0c: Securities-related bank rows skip this check (customer resolved via pairing)
        if (isBank) {
            String customerId = context.getCustomerId();
            if (FrameworkConstants.ENTITY_UNKNOWN.equals(customerId)) {
                String desc = context.getPaymentDescription();
                boolean isSecuritiesRelated = desc != null && (
                        desc.toLowerCase().startsWith("securities")
                        || desc.toLowerCase().startsWith("dividends")
                        || desc.toLowerCase().startsWith("income tax withheld"));
                if (!isSecuritiesRelated) {
                    return true;
                }
            }
        }

        // 2. UNKNOWN counterparty — both bank and secu
        String counterpartyId = getStringValue(data.get("counterparty_id"));
        if (FrameworkConstants.ENTITY_UNKNOWN.equals(counterpartyId)) {
            return true;
        }

        // 3. UNMATCHED internal type — both
        String internalType = getStringValue(data.get("internal_type"));
        if (FrameworkConstants.INTERNAL_TYPE_UNMATCHED.equals(internalType)) {
            return true;
        }

        // 4. Low customer confidence — bank only
        if (isBank) {
            int confidenceThreshold = getConfigInt(config, "confidenceThresholdHigh", 80);
            Object customerConfidence = data.get("customer_confidence");
            if (customerConfidence instanceof Number) {
                double confidence = ((Number) customerConfidence).doubleValue();
                if (confidence < confidenceThreshold) {
                    return true;
                }
            }
        }

        // 5. UNKNOWN asset — secu only
        String assetId = getStringValue(data.get("asset_id"));
        if (FrameworkConstants.ENTITY_UNKNOWN.equals(assetId)) {
            return true;
        }

        // 6. Missing FX rate — both
        String fxRateSource = getStringValue(data.get("fx_rate_source"));
        if ("MISSING".equals(fxRateSource)) {
            return true;
        }

        return false;
    }
    
    /**
     * Update source transaction row with enrichment reference data.
     */
    private void updateSourceRowWithEnrichmentRef(DataContext context, FormDataDao dao, String enrichedRecordId) {
        try {
            String sourceTable = DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType()) ?
                    DomainConstants.TABLE_BANK_TOTAL_TRX : DomainConstants.TABLE_SECU_TOTAL_TRX;
            FormRow sourceRow = loadFormRow(dao, sourceTable, context.getTransactionId());
            if (sourceRow != null) {
                sourceRow.setProperty("enrichment_date", TIMESTAMP_FORMAT.format(new Date()));
                sourceRow.setProperty("enriched_record_id", enrichedRecordId);
                FormRowSet rowSet = new FormRowSet();
                rowSet.add(sourceRow);
                dao.saveOrUpdate(null, sourceTable, rowSet);
            }
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error updating source row: " + context.getTransactionId());
        }
    }

    /**
     * Transition enrichment record through its lifecycle via StatusManager.
     * Lifecycle: null → NEW → PROCESSING → {ENRICHED|MANUAL_REVIEW|ERROR}
     */
    private void transitionEnrichment(FormDataDao dao, String recordId,
                                      Status targetStatus, boolean needsManualReview) {
        if (statusManager == null) {
            return;
        }
        try {
            String tableName = DomainConstants.TABLE_TRX_ENRICHMENT;
            statusManager.transition(dao, tableName, EntityType.ENRICHMENT, recordId,
                    Status.NEW, "rows-enrichment", "Enrichment record created");
            statusManager.transition(dao, tableName, EntityType.ENRICHMENT, recordId,
                    Status.PROCESSING, "rows-enrichment", "Pipeline processing");
            statusManager.transition(dao, tableName, EntityType.ENRICHMENT, recordId,
                    targetStatus, "rows-enrichment",
                    needsManualReview ? "Requires manual review" : "Enrichment completed successfully");
        } catch (InvalidTransitionException e) {
            LogUtil.error(CLASS_NAME, e, "Invalid status transition for enrichment: " + recordId);
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error transitioning enrichment record: " + recordId);
        }
    }

    /**
     * Transition source transaction to mirror enrichment status via StatusManager.
     * Source transaction status mirrors the enrichment record status:
     * - ENRICHED → ENRICHED
     * - MANUAL_REVIEW → MANUAL_REVIEW
     * - ERROR → ERROR
     */
    private void transitionSourceTransaction(DataContext context, FormDataDao dao, Status targetStatus) {
        if (statusManager == null) {
            return;
        }
        try {
            EntityType entityType = DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType()) ?
                    EntityType.BANK_TRX : EntityType.SECU_TRX;
            Status sourceTarget = (targetStatus == Status.ERROR) ? Status.ERROR
                    : (targetStatus == Status.MANUAL_REVIEW) ? Status.MANUAL_REVIEW
                    : Status.ENRICHED;
            String reason = buildTransitionReason(context);
            statusManager.transition(dao, entityType, context.getTransactionId(),
                    sourceTarget, "rows-enrichment", reason);
        } catch (InvalidTransitionException e) {
            LogUtil.error(CLASS_NAME, e,
                    "Invalid status transition for source transaction: " + context.getTransactionId());
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error transitioning source transaction status");
        }
    }

    /**
     * Build a descriptive transition reason from context data.
     */
    private String buildTransitionReason(DataContext context) {
        Map<String, Object> data = context.getAdditionalData();
        List<String> steps = context.getProcessedSteps();
        int completed = steps != null ? steps.size() : 0;

        List<String> issues = new ArrayList<>();
        if (data != null) {
            if (FrameworkConstants.ENTITY_UNKNOWN.equals(context.getCustomerId())) {
                issues.add("UNKNOWN customer");
            }
            if (FrameworkConstants.INTERNAL_TYPE_UNMATCHED.equals(getStringValue(data.get("internal_type")))) {
                issues.add("UNMATCHED type");
            }
            if (FrameworkConstants.ENTITY_UNKNOWN.equals(getStringValue(data.get("counterparty_id")))) {
                issues.add("UNKNOWN counterparty");
            }
        }

        if (issues.isEmpty()) {
            return String.format("%d/%d steps OK", completed, completed);
        }
        return String.format("%d steps OK, %s", completed, String.join(", ", issues));
    }
    
    /**
     * Persist batch of enriched transactions with state management
     * This method handles:
     * - Persisting enriched data to trx_enrichment table
     * - Updating source transaction statuses to "enriched"
     * - Updating statement status to "processed" when all transactions complete
     * - Tracking success/failure per statement
     */
    public BatchPersistenceResult persistBatch(List<DataContext> contexts, 
                                              BatchPipelineResult pipelineResult,
                                              FormDataDao dao,
                                              Map<String, Object> config) {
        BatchPersistenceResult batchResult = new BatchPersistenceResult();
        batchResult.setStartTime(new Date());
        batchResult.setTargetStorage(DomainConstants.TABLE_TRX_ENRICHMENT);
        
        // Group transactions by statement
        Map<String, List<DataContext>> statementGroups = groupByStatement(contexts);
        LogUtil.info(CLASS_NAME, "Processing " + contexts.size() + 
                   " transactions across " + statementGroups.size() + " statements");
        
        // Process each statement's transactions
        for (Map.Entry<String, List<DataContext>> entry : statementGroups.entrySet()) {
            String statementId = entry.getKey();
            List<DataContext> statementTransactions = entry.getValue();
            
            LogUtil.info(CLASS_NAME, "Processing statement " + statementId + 
                       " with " + statementTransactions.size() + " transactions");
            
            BatchPersistenceResult.StatementStatus statementStatus = 
                new BatchPersistenceResult.StatementStatus(statementId);
            statementStatus.setTotalTransactions(statementTransactions.size());
            
            int statementSuccessCount = 0;
            int statementFailureCount = 0;
            
            // Process each transaction for this statement
            for (DataContext context : statementTransactions) {
                try {
                    // Get the pipeline result for this transaction
                    PipelineResult txPipelineResult = findPipelineResult(pipelineResult, context.getTransactionId());
                    
                    // Only persist if pipeline was successful
                    if (txPipelineResult != null && txPipelineResult.isSuccess()) {
                        PersistenceResult persistResult = persist(context, dao, config);
                        batchResult.addResult(persistResult);
                        
                        if (persistResult.isSuccess()) {
                            statementSuccessCount++;
                            LogUtil.info(CLASS_NAME, "Successfully persisted transaction " + 
                                       context.getTransactionId() + " as " + persistResult.getRecordId());
                        } else {
                            statementFailureCount++;
                            LogUtil.error(CLASS_NAME, null, "Failed to persist transaction " + 
                                        context.getTransactionId() + ": " + persistResult.getMessage());
                        }
                    } else {
                        // Transaction failed in pipeline, skip persistence
                        statementFailureCount++;
                        PersistenceResult skipResult = new PersistenceResult(false, null,
                            "Skipped due to pipeline failure");
                        batchResult.addResult(skipResult);
                        LogUtil.info(CLASS_NAME, "Skipping persistence for failed transaction " + 
                                   context.getTransactionId());
                    }
                    
                } catch (Exception e) {
                    statementFailureCount++;
                    LogUtil.error(CLASS_NAME, e, "Error persisting transaction " + context.getTransactionId());
                    PersistenceResult errorResult = new PersistenceResult(false, null,
                        "Persistence error: " + e.getMessage());
                    batchResult.addResult(errorResult);
                }
            }
            
            // Update statement status
            statementStatus.setSuccessCount(statementSuccessCount);
            statementStatus.setFailureCount(statementFailureCount);
            
            // Update statement in database
            updateStatementStatus(statementId, statementSuccessCount, 
                                statementFailureCount, statementTransactions.size(), dao);
            
            // Determine final statement status
            String finalStatus = statementFailureCount == 0 ?
                    Status.ENRICHED.getCode() : Status.ERROR.getCode();
            statementStatus.setStatus(finalStatus);
            statementStatus.setProcessedDate(new Date());
            
            batchResult.addStatementStatus(statementId, statementStatus);
            
            LogUtil.info(CLASS_NAME, "Statement " + statementId + " processing complete: " +
                       statementSuccessCount + " success, " + statementFailureCount + " failures");
        }
        
        batchResult.setEndTime(new Date());
        
        // Log final summary
        LogUtil.info(CLASS_NAME, "========================================");
        LogUtil.info(CLASS_NAME, "BATCH PERSISTENCE COMPLETE");
        LogUtil.info(CLASS_NAME, "  Total records: " + batchResult.getTotalRecords());
        LogUtil.info(CLASS_NAME, "  Successful: " + batchResult.getSuccessCount());
        LogUtil.info(CLASS_NAME, "  Failed: " + batchResult.getFailureCount());
        LogUtil.info(CLASS_NAME, "  Statements processed: " + batchResult.getStatementsProcessed());
        LogUtil.info(CLASS_NAME, "  Statements with errors: " + batchResult.getStatementsWithErrors());
        LogUtil.info(CLASS_NAME, "========================================");
        
        return batchResult;
    }
    
    /**
     * Group transactions by statement ID
     */
    private Map<String, List<DataContext>> groupByStatement(List<DataContext> contexts) {
        Map<String, List<DataContext>> groups = new HashMap<>();
        for (DataContext context : contexts) {
            String statementId = context.getStatementId();
            if (statementId != null) {
                groups.computeIfAbsent(statementId, k -> new ArrayList<>()).add(context);
            }
        }
        return groups;
    }
    
    /**
     * Find pipeline result for a specific transaction
     */
    private PipelineResult findPipelineResult(BatchPipelineResult batchResult, String transactionId) {
        if (batchResult == null || batchResult.getResults() == null) {
            return null;
        }
        
        for (PipelineResult result : batchResult.getResults()) {
            if (transactionId.equals(result.getTransactionId())) {
                return result;
            }
        }
        return null;
    }
    
    /**
     * Update statement status via StatusManager and save metadata
     */
    private void updateStatementStatus(String statementId, int successCount,
                                      int failureCount, int totalCount, FormDataDao dao) {
        try {
            // Transition statement status via StatusManager
            if (statusManager != null) {
                Status targetStatus = failureCount == 0 ? Status.ENRICHED : Status.ERROR;
                String reason = String.format("Statement processing complete: %d success, %d failures out of %d total",
                        successCount, failureCount, totalCount);
                try {
                    statusManager.transition(dao, EntityType.STATEMENT, statementId,
                            targetStatus, "rows-enrichment", reason);
                } catch (InvalidTransitionException e) {
                    LogUtil.error(CLASS_NAME, e, "Invalid status transition for statement: " + statementId);
                }
            }

            // Save metadata fields (status already handled by StatusManager)
            FormRow statementRow = loadFormRow(dao, DomainConstants.TABLE_BANK_STATEMENT, statementId);
            if (statementRow != null) {
                statementRow.setProperty("processing_completed", TIMESTAMP_FORMAT.format(new Date()));
                statementRow.setProperty("transactions_processed", String.valueOf(totalCount));
                statementRow.setProperty("transactions_success", String.valueOf(successCount));
                statementRow.setProperty("transactions_failed", String.valueOf(failureCount));

                FormRowSet rowSet = new FormRowSet();
                rowSet.add(statementRow);
                dao.saveOrUpdate(null, DomainConstants.TABLE_BANK_STATEMENT, rowSet);

                LogUtil.info(CLASS_NAME, "Updated statement " + statementId + " metadata");
            } else {
                LogUtil.warn(CLASS_NAME, "Statement not found for metadata update: " + statementId);
            }
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error updating statement status: " + statementId);
        }
    }
}