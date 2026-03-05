package com.fiscaladmin.gam.enrichrows.lib;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.framework.*;
import com.fiscaladmin.gam.enrichrows.loader.TransactionDataLoader;
import com.fiscaladmin.gam.enrichrows.persister.EnrichmentDataPersister;
import com.fiscaladmin.gam.enrichrows.steps.*;
import com.fiscaladmin.gam.framework.status.Status;
import com.fiscaladmin.gam.framework.status.StatusManager;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import org.joget.plugin.base.DefaultApplicationPlugin;

import java.util.*;

/**
 * Transaction processor - starting from scratch
 */
public class RowsEnricher extends DefaultApplicationPlugin {

    private static final String CLASS_NAME = RowsEnricher.class.getName();

    @Override
    public String getName() {
        return "Rows Enrichment";
    }

    @Override
    public String getDescription() {
        return "This plugin will enrich statement rows";
    }

    @Override
    public String getVersion() {
        return "8.1-SNAPSHOT";
    }

    @Override
    public String getLabel() {
        return "Rows Enrichment";
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClass().getName(),
                "/properties/app/RowsEnricher.json", null, true, null);
    }

    @Override
    public Object execute(Map properties) {
        try {
            LogUtil.info(CLASS_NAME, "Starting Rows Enrichment Plugin using DataPipeline Framework");

            FormDataDao formDataDao = (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");
            StatusManager statusManager = new StatusManager();

            TransactionDataLoader dataLoader = new TransactionDataLoader();
            dataLoader.setStatusManager(statusManager);

            // Load all statements and their rows
            List<DataContext> transactions = dataLoader.loadData(formDataDao, properties);
            
            LogUtil.info(CLASS_NAME, "Loaded " + transactions.size() + " transaction rows");
            
            // Create and configure the processing pipeline
            boolean stopOnError = "true".equalsIgnoreCase(
                    properties != null ? String.valueOf(properties.get("stopOnError")) : "false");
            DataPipeline pipeline = new DataPipeline(formDataDao)
                .addStep(new CurrencyValidationStep())
                .addStep(new CounterpartyDeterminationStep())
                .addStep(new CustomerIdentificationStep())
                .addStep(new AssetResolutionStep())
                .addStep(new F14RuleMappingStep())
                .addStep(new FXConversionStep())
                .setStopOnError(stopOnError)
                .setProperties(properties)
                .setStatusManager(statusManager);
            
            LogUtil.info(CLASS_NAME, "Pipeline configured with " + pipeline.getStepCount() + " steps");
            
            // Execute the pipeline for all transactions
            BatchPipelineResult batchResult = pipeline.executeBatch(transactions);
            
            // Log the pipeline results
            LogUtil.info(CLASS_NAME, "========================================");
            LogUtil.info(CLASS_NAME, "PIPELINE PROCESSING COMPLETE");
            LogUtil.info(CLASS_NAME, "  Total transactions: " + batchResult.getTotalTransactions());
            LogUtil.info(CLASS_NAME, "  Successful: " + batchResult.getSuccessCount());
            LogUtil.info(CLASS_NAME, "  Failed: " + batchResult.getFailureCount());
            LogUtil.info(CLASS_NAME, "  Processing time: " + batchResult.getElapsedTimeMillis() + " ms");
            LogUtil.info(CLASS_NAME, "========================================");
            
            // Persist the enriched data with state management
            LogUtil.info(CLASS_NAME, "Starting persistence phase with state management...");
            EnrichmentDataPersister persister = new EnrichmentDataPersister();
            persister.setStatusManager(statusManager);
            
            // Persist all transactions and update statement statuses
            BatchPersistenceResult persistenceResult = persister.persistBatch(
                transactions,
                batchResult,
                formDataDao,
                properties  // Pass properties as config
            );

            // Post-persistence: create operational exceptions for secu ENRICHED records
            createPostEnrichmentExceptions(transactions, formDataDao);

            // Phase 2b: Cross-statement pairing (amount + date matching)
            TransactionPairingStep pairingStep = new TransactionPairingStep();
            pairingStep.setStatusManager(statusManager);
            int pairsCreated = pairingStep.executePairing(formDataDao);
            LogUtil.info(CLASS_NAME, "Cross-statement pairing: " + pairsCreated + " pairs created");

            // Log persistence results
            LogUtil.info(CLASS_NAME, "========================================");
            LogUtil.info(CLASS_NAME, "PERSISTENCE PHASE COMPLETE");
            LogUtil.info(CLASS_NAME, "  Records persisted: " + persistenceResult.getSuccessCount());
            LogUtil.info(CLASS_NAME, "  Records failed: " + persistenceResult.getFailureCount());
            LogUtil.info(CLASS_NAME, "  Statements processed: " + persistenceResult.getStatementsProcessed());
            LogUtil.info(CLASS_NAME, "  Statements with errors: " + persistenceResult.getStatementsWithErrors());
            LogUtil.info(CLASS_NAME, "========================================");
            
            return String.format(
                "Processing complete: Pipeline[%d successful, %d failed], " +
                "Persistence[%d saved, %d failed], " +
                "Statements[%d processed, %d with errors] " +
                "(Total time: %dms)",
                batchResult.getSuccessCount(), batchResult.getFailureCount(),
                persistenceResult.getSuccessCount(), persistenceResult.getFailureCount(),
                persistenceResult.getStatementsProcessed(), persistenceResult.getStatementsWithErrors(),
                batchResult.getElapsedTimeMillis() + 
                (persistenceResult.getEndTime().getTime() - persistenceResult.getStartTime().getTime()));

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error in Rows Enrichment");
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Create post-enrichment operational exceptions.
     * Runs after persistence phase to signal downstream workflows.
     *
     * Currently: creates PORTFOLIO_ALLOCATION_REQUIRED for secu records
     * that reached ENRICHED status (all automated dimensions resolved).
     * This tells operations that the record is ready for customer
     * portfolio allocation via the enrichment-workspace split function.
     */
    private void createPostEnrichmentExceptions(List<DataContext> transactions,
                                                FormDataDao formDataDao) {
        int count = 0;
        for (DataContext context : transactions) {
            if (!DomainConstants.SOURCE_TYPE_SECU.equals(context.getSourceType())) {
                continue;
            }

            Map<String, Object> data = context.getAdditionalData();
            if (data == null) continue;

            // enriched_record_id is set by the persister after successful save
            String enrichedRecordId = data.get("enriched_record_id") != null
                    ? data.get("enriched_record_id").toString() : null;
            if (enrichedRecordId == null) continue;

            // needs_manual_review flag is set by the persister (§2.5a)
            Object needsReview = data.get("needs_manual_review");
            if (Boolean.FALSE.equals(needsReview)) {
                // Record went to ENRICHED → needs portfolio allocation
                createOperationalException(context, formDataDao, enrichedRecordId,
                        DomainConstants.EXCEPTION_PORTFOLIO_ALLOCATION_REQUIRED,
                        "Securities transaction enriched successfully. " +
                        "Customer portfolio allocation required via workspace split.",
                        "medium");
                count++;
            }
        }
        if (count > 0) {
            LogUtil.info(CLASS_NAME, "Created " + count +
                    " PORTFOLIO_ALLOCATION_REQUIRED exceptions for secu ENRICHED records");
        }
    }

    /**
     * Create an operational exception in the exception_queue table.
     * Follows the same field pattern as step-level exception creation.
     */
    private void createOperationalException(DataContext context, FormDataDao formDataDao,
                                            String enrichedRecordId, String exceptionType,
                                            String exceptionDetails, String priority) {
        try {
            FormRow exceptionRow = new FormRow();
            exceptionRow.setId(UUID.randomUUID().toString());

            // Exception identifiers
            exceptionRow.setProperty("transaction_id", context.getTransactionId());
            exceptionRow.setProperty("statement_id", context.getStatementId());
            exceptionRow.setProperty("source_type", context.getSourceType());
            exceptionRow.setProperty("enriched_record_id", enrichedRecordId);

            // Exception details
            exceptionRow.setProperty("exception_type", exceptionType);
            exceptionRow.setProperty("exception_details", exceptionDetails);
            exceptionRow.setProperty("exception_date", new Date().toString());

            // Transaction context for resolution
            exceptionRow.setProperty("amount", context.getAmount());
            exceptionRow.setProperty("currency", context.getCurrency());
            exceptionRow.setProperty("transaction_date", context.getTransactionDate());
            exceptionRow.setProperty("ticker", context.getTicker());
            exceptionRow.setProperty("description", context.getDescription());

            // Priority and assignment
            exceptionRow.setProperty("priority", priority);
            exceptionRow.setProperty("status", Status.OPEN.getCode());
            exceptionRow.setProperty("assigned_to", "operations");

            FormRowSet rowSet = new FormRowSet();
            rowSet.add(exceptionRow);
            formDataDao.saveOrUpdate(null, DomainConstants.TABLE_EXCEPTION_QUEUE, rowSet);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error creating operational exception for: " + context.getTransactionId());
        }
    }
}