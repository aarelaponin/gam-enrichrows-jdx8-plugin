package com.fiscaladmin.gam.enrichrows.lib;

import com.fiscaladmin.gam.enrichrows.framework.*;
import com.fiscaladmin.gam.enrichrows.loader.TransactionDataLoader;
import com.fiscaladmin.gam.enrichrows.persister.EnrichmentDataPersister;
import com.fiscaladmin.gam.enrichrows.steps.*;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.dao.FormDataDao;
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
        return AppUtil.readPluginResource(getClass().getName(), "", null, true, null);
    }

    @Override
    public Object execute(Map properties) {
        try {
            LogUtil.info(CLASS_NAME, "Starting Rows Enrichment Plugin using DataPipeline Framework");

            FormDataDao formDataDao = (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");
            TransactionDataLoader dataLoader = new TransactionDataLoader();
            
            // Load all statements and their rows
            List<DataContext> transactions = dataLoader.loadData(formDataDao, properties);
            
            LogUtil.info(CLASS_NAME, "Loaded " + transactions.size() + " transaction rows");
            
            // Create and configure the processing pipeline
            DataPipeline pipeline = new DataPipeline(formDataDao)
                .addStep(new CurrencyValidationStep())
                .addStep(new FXConversionStep())
                .addStep(new CustomerIdentificationStep())
                .addStep(new CounterpartyDeterminationStep())
                .addStep(new F14RuleMappingStep())
                .setStopOnError(false);  // Continue processing even if a step fails
            
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
            
            // Persist all transactions and update statement statuses
            BatchPersistenceResult persistenceResult = persister.persistBatch(
                transactions, 
                batchResult, 
                formDataDao,
                properties  // Pass properties as config
            );
            
            // Log persistence results
            LogUtil.info(CLASS_NAME, "========================================");
            LogUtil.info(CLASS_NAME, "PERSISTENCE PHASE COMPLETE");
            LogUtil.info(CLASS_NAME, "  Records persisted: " + persistenceResult.getSuccessCount());
            LogUtil.info(CLASS_NAME, "  Records failed: " + persistenceResult.getFailureCount());
            LogUtil.info(CLASS_NAME, "  Statements processed: " + persistenceResult.getStatementsProcessed());
            LogUtil.info(CLASS_NAME, "  Statements with errors: " + persistenceResult.getStatementsWithErrors());
            LogUtil.info(CLASS_NAME, "========================================");
            
            // Log details for all transactions to diagnose the issue
            LogUtil.info(CLASS_NAME, "Detailed step results for diagnostics:");
            int countCurrencyPassed = 0;
            int countFxPassed = 0;
            int countF14Processed = 0;
            int countF14Passed = 0;
            
            for (PipelineResult result : batchResult.getResults()) {
                Map<String, StepResult> stepResults = result.getStepResults();
                
                // Check Currency Validation
                StepResult currencyResult = stepResults.get("Currency Validation");
                if (currencyResult != null && currencyResult.isSuccess()) {
                    countCurrencyPassed++;
                }
                
                // Check FX Conversion
                StepResult fxResult = stepResults.get("FX Conversion");
                if (fxResult != null && fxResult.isSuccess()) {
                    countFxPassed++;
                }
                
                // Check F14 Rule Mapping
                StepResult f14Result = stepResults.get("F14 Rule Mapping");
                if (f14Result != null) {
                    countF14Processed++;
                    if (f14Result.isSuccess()) {
                        countF14Passed++;
                    } else {
                        // Log transactions that failed F14 mapping
                        LogUtil.info(CLASS_NAME, "F14 failed for transaction " + 
                                   result.getTransactionId() + ": " + f14Result.getMessage());
                    }
                }
            }
            
            LogUtil.info(CLASS_NAME, "Step execution summary:");
            LogUtil.info(CLASS_NAME, "  Currency Validation passed: " + countCurrencyPassed + "/" + batchResult.getTotalTransactions());
            LogUtil.info(CLASS_NAME, "  FX Conversion passed: " + countFxPassed + "/" + batchResult.getTotalTransactions());
            LogUtil.info(CLASS_NAME, "  F14 Mapping processed: " + countF14Processed + "/" + batchResult.getTotalTransactions());
            LogUtil.info(CLASS_NAME, "  F14 Mapping passed: " + countF14Passed + "/" + countF14Processed);
            
            if (countF14Processed < batchResult.getTotalTransactions()) {
                LogUtil.warn(CLASS_NAME, "WARNING: " + (batchResult.getTotalTransactions() - countF14Processed) + 
                           " transactions did NOT reach F14 mapping step!");
                LogUtil.warn(CLASS_NAME, "These transactions likely failed in earlier steps (Currency or FX)");
            }
            
            // Note: Each step creates audit_log entries via the createAuditLog method

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
}