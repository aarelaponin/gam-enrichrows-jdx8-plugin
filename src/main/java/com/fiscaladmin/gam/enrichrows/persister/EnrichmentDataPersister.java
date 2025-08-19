package com.fiscaladmin.gam.enrichrows.persister;

import com.fiscaladmin.gam.enrichrows.framework.*;
import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;

import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import java.util.*;
import java.text.SimpleDateFormat;

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
            // Create the enriched record
            FormRow enrichedRow = createEnrichedRecord(context);
            
            // Determine processing status
            boolean needsManualReview = determineManualReviewStatus(context);
            setPropertySafe(enrichedRow, "processing_status", 
                needsManualReview ? FrameworkConstants.PROCESSING_STATUS_MANUAL_REVIEW : FrameworkConstants.PROCESSING_STATUS_ENRICHED);
            
            // Save the record - use configured table name
            String targetTable = DomainConstants.TABLE_TRX_ENRICHMENT;
            boolean saved = saveFormRow(dao, targetTable, enrichedRow);
            
            if (saved) {
                String recordId = enrichedRow.getId();
                
                // Update context with persisted record ID
                context.setAdditionalDataValue("enriched_record_id", recordId);
                
                // Update source transaction status
                updateSourceTransactionStatus(context, dao);
                
                // Create audit log
                createAuditLog(context, dao, recordId, needsManualReview);
                
                PersistenceResult result = new PersistenceResult(true, recordId,
                    "Enrichment persisted successfully");
                result.setTargetStorage(targetTable);
                result.addMetadata("needs_manual_review", needsManualReview);
                result.addMetadata("processing_status", 
                    needsManualReview ? FrameworkConstants.PROCESSING_STATUS_MANUAL_REVIEW : FrameworkConstants.PROCESSING_STATUS_ENRICHED);
                
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
     * Create the enriched record with all processed data
     */
    private FormRow createEnrichedRecord(DataContext context) {
        FormRow row = createFormRow();
        
        // Generate formatted ID
        String enrichedId = "TRX-" + UUID.randomUUID().toString()
            .substring(0, 6).toUpperCase();
        row.setId(enrichedId);
        
        // Populate all fields
        populateCoreFields(row, context);
        populateEntityFields(row, context);
        populateClassificationFields(row, context);
        populateFXFields(row, context);
        populateMetadataFields(row, context);
        
        // Type-specific fields
        if (DomainConstants.SOURCE_TYPE_SECU.equals(context.getSourceType())) {
            populateSecuritiesFields(row, context);
        } else if (DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType())) {
            populateBankingFields(row, context);
        }
        
        return row;
    }
    
    private void populateCoreFields(FormRow row, DataContext context) {
        setPropertySafe(row, "source_transaction_id", context.getTransactionId());
        setPropertySafe(row, "source_type", context.getSourceType());
        setPropertySafe(row, "statement_id", context.getStatementId());
        setPropertySafe(row, "transaction_date", context.getTransactionDate());
        setPropertySafe(row, "amount", context.getAmount());
        setPropertySafe(row, "currency", context.getCurrency());
        setPropertySafe(row, "description", context.getDescription());
        setPropertySafe(row, "reference_number", context.getReferenceNumber());
        
        // Determine source table
        String sourceTable = DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType()) ?
            DomainConstants.TABLE_BANK_TOTAL_TRX : DomainConstants.TABLE_SECU_TOTAL_TRX;
        setPropertySafe(row, "source_table", sourceTable);
    }
    
    private void populateEntityFields(FormRow row, DataContext context) {
        Map<String, Object> data = context.getAdditionalData();
        if (data == null) return;
        
        // Counterparty
        setPropertySafe(row, "counterparty_id", data.get("counterparty_id"));
        setPropertySafe(row, "counterparty_name", data.get("counterparty_name"));
        setPropertySafe(row, "counterparty_bic", data.get("counterparty_bic"));
        
        // Customer
        setPropertySafe(row, "customer_id", context.getCustomerId());
        setPropertySafe(row, "customer_name", data.get("customer_name"));
        setPropertySafe(row, "customer_type", data.get("customer_type"));
        
        // Confidence score
        Object confidence = data.get("customer_confidence");
        if (confidence != null) {
            double value = confidence instanceof Number ? 
                ((Number) confidence).doubleValue() / 100.0 : 0.0;
            setPropertySafe(row, "customer_confidence", String.valueOf(value));
        }
    }
    
    private void populateClassificationFields(FormRow row, DataContext context) {
        Map<String, Object> data = context.getAdditionalData();
        if (data == null) return;
        
        setPropertySafe(row, "internal_type", data.get("internal_type"));
        setPropertySafe(row, "f14_rule_id", data.get("f14_rule_id"));
        setPropertySafe(row, "f14_rule_name", data.get("f14_rule_name"));
        setPropertySafe(row, "f14_priority", data.get("f14_priority"));
        
        // Matching confidence
        String internalType = getStringValue(data.get("internal_type"));
        if (internalType != null && !FrameworkConstants.INTERNAL_TYPE_UNMATCHED.equals(internalType)) {
            setPropertySafe(row, "matching_confidence", "1.0");
        } else {
            setPropertySafe(row, "matching_confidence", "0.0");
        }
    }
    
    private void populateFXFields(FormRow row, DataContext context) {
        Map<String, Object> data = context.getAdditionalData();
        if (data == null) return;
        
        setPropertySafe(row, "original_amount", context.getAmount());
        setPropertySafe(row, "original_currency", context.getCurrency());
        setPropertySafe(row, "base_amount_eur", context.getBaseAmount());
        setPropertySafe(row, "base_currency", DomainConstants.BASE_CURRENCY);
        setPropertySafe(row, "fx_rate", data.get("fx_rate"));
        setPropertySafe(row, "fx_rate_date", data.get("fx_rate_date"));
        setPropertySafe(row, "fx_rate_source", data.get("fx_rate_source"));
        setPropertySafe(row, "fx_rate_type", "SPOT");
    }
    
    private void populateMetadataFields(FormRow row, DataContext context) {
        setPropertySafe(row, "pipeline_version", FrameworkConstants.PIPELINE_VERSION);
        setPropertySafe(row, "version_number", "1");
        setPropertySafe(row, "created_date", TIMESTAMP_FORMAT.format(new Date()));
        setPropertySafe(row, "created_by", FrameworkConstants.SYSTEM_USER);
        setPropertySafe(row, "processing_date", TIMESTAMP_FORMAT.format(new Date()));
        setPropertySafe(row, "pairing_status", "pending");
    }
    
    private void populateSecuritiesFields(FormRow row, DataContext context) {
        setPropertySafe(row, "asset_ticker", context.getTicker());
        setPropertySafe(row, "quantity", context.getQuantity());
        setPropertySafe(row, "price", context.getPrice());
        setPropertySafe(row, "fee", context.getFee());
        
        Map<String, Object> data = context.getAdditionalData();
        if (data != null) {
            setPropertySafe(row, "asset_id", data.get("asset_id"));
            setPropertySafe(row, "asset_name", data.get("asset_name"));
        }
    }
    
    private void populateBankingFields(FormRow row, DataContext context) {
        setPropertySafe(row, "payment_date", context.getPaymentDate());
        setPropertySafe(row, "payment_amount", context.getPaymentAmount());
        setPropertySafe(row, "debit_credit", context.getDebitCredit());
        setPropertySafe(row, "other_side_bic", context.getOtherSideBic());
        setPropertySafe(row, "other_side_account", context.getOtherSideAccount());
        setPropertySafe(row, "other_side_name", context.getOtherSideName());
        setPropertySafe(row, "payment_description", context.getPaymentDescription());
    }
    
    /**
     * Determine if manual review is needed
     */
    private boolean determineManualReviewStatus(DataContext context) {
        Map<String, Object> data = context.getAdditionalData();
        if (data == null) return true;
        
        // Check for unknown entities
        String customerId = context.getCustomerId();
        if (FrameworkConstants.ENTITY_UNKNOWN.equals(customerId)) {
            return true;
        }
        
        String counterpartyId = getStringValue(data.get("counterparty_id"));
        if (FrameworkConstants.ENTITY_UNKNOWN.equals(counterpartyId)) {
            return true;
        }
        
        // Check for unmatched classification
        String internalType = getStringValue(data.get("internal_type"));
        if (FrameworkConstants.INTERNAL_TYPE_UNMATCHED.equals(internalType)) {
            return true;
        }
        
        // Check confidence scores
        Object customerConfidence = data.get("customer_confidence");
        if (customerConfidence instanceof Number) {
            double confidence = ((Number) customerConfidence).doubleValue();
            if (confidence < 80) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Update source transaction status
     */
    private void updateSourceTransactionStatus(DataContext context, FormDataDao dao) {
        try {
            String tableName = DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType()) ?
                DomainConstants.TABLE_BANK_TOTAL_TRX : DomainConstants.TABLE_SECU_TOTAL_TRX;
            
            FormRow sourceRow = loadFormRow(dao, tableName, context.getTransactionId());
            if (sourceRow != null) {
                sourceRow.setProperty(FrameworkConstants.FIELD_STATUS, FrameworkConstants.STATUS_ENRICHED);
                sourceRow.setProperty("enrichment_date", TIMESTAMP_FORMAT.format(new Date()));
                updateFormRow(dao, tableName, sourceRow);
                
            }
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error updating source transaction status");
        }
    }
    
    /**
     * Create audit log entry
     */
    private void createAuditLog(DataContext context, FormDataDao dao, 
                               String enrichedId, boolean manualReview) {
        try {
            FormRow auditRow = createFormRow();
            auditRow.setProperty("transaction_id", context.getTransactionId());
            auditRow.setProperty("enriched_record_id", enrichedId);
            auditRow.setProperty("action", DomainConstants.AUDIT_ENRICHMENT_SAVED);
            auditRow.setProperty("details", String.format(
                "Enrichment saved: %s (Status: %s)",
                enrichedId, manualReview ? FrameworkConstants.PROCESSING_STATUS_MANUAL_REVIEW : FrameworkConstants.PROCESSING_STATUS_ENRICHED));
            auditRow.setProperty("timestamp", TIMESTAMP_FORMAT.format(new Date()));
            auditRow.setProperty("step_name", "EnrichmentPersistence");
            
            String auditTable = DomainConstants.TABLE_AUDIT_LOG;
            saveFormRow(dao, auditTable, auditRow);
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error creating audit log");
        }
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
            String finalStatus = statementFailureCount == 0 ? "processed" : "processed_with_errors";
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
     * Update statement status in database
     */
    private void updateStatementStatus(String statementId, int successCount, 
                                      int failureCount, int totalCount, FormDataDao dao) {
        try {
            FormRow statementRow = loadFormRow(dao, DomainConstants.TABLE_BANK_STATEMENT, statementId);
            if (statementRow != null) {
                String status = failureCount == 0 ? "processed" : "processed_with_errors";
                statementRow.setProperty(FrameworkConstants.FIELD_STATUS, status);
                statementRow.setProperty("processing_completed", TIMESTAMP_FORMAT.format(new Date()));
                statementRow.setProperty("transactions_processed", String.valueOf(totalCount));
                statementRow.setProperty("transactions_success", String.valueOf(successCount));
                statementRow.setProperty("transactions_failed", String.valueOf(failureCount));
                
                FormRowSet rowSet = new FormRowSet();
                rowSet.add(statementRow);
                dao.saveOrUpdate(null, DomainConstants.TABLE_BANK_STATEMENT, rowSet);
                
                LogUtil.info(CLASS_NAME, "Updated statement " + statementId + " status to: " + status);
                
                // Create audit log for statement completion
                FormRow auditRow = createFormRow();
                auditRow.setProperty("statement_id", statementId);
                auditRow.setProperty("action", "STATEMENT_PROCESSED");
                auditRow.setProperty("details", String.format(
                    "Statement processed: %d success, %d failures out of %d total",
                    successCount, failureCount, totalCount));
                auditRow.setProperty("timestamp", TIMESTAMP_FORMAT.format(new Date()));
                auditRow.setProperty("step_name", "StatementCompletion");
                saveFormRow(dao, DomainConstants.TABLE_AUDIT_LOG, auditRow);
                
            } else {
                LogUtil.warn(CLASS_NAME, "Statement not found for status update: " + statementId);
            }
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error updating statement status: " + statementId);
        }
    }
}