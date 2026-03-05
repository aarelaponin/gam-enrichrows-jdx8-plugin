package com.fiscaladmin.gam.enrichrows.framework;

import com.fiscaladmin.gam.framework.status.StatusManager;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.commons.util.LogUtil;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class DataPipeline {

    private static final String CLASS_NAME = DataPipeline.class.getName();

    private final List<DataStep> steps;
    private final FormDataDao formDataDao;
    private boolean stopOnError;
    private StatusManager statusManager;

    public DataPipeline(FormDataDao formDataDao) {
        this.formDataDao = formDataDao;
        this.steps = new ArrayList<>();
        this.stopOnError = true;  // Default behavior
    }

    /**
     * Set StatusManager on all steps in the pipeline
     */
    public DataPipeline setStatusManager(StatusManager statusManager) {
        this.statusManager = statusManager;
        for (DataStep step : steps) {
            if (step instanceof AbstractDataStep) {
                ((AbstractDataStep) step).setStatusManager(statusManager);
            }
        }
        return this;
    }

    /**
     * Add a step to the processing pipeline
     */
    public DataPipeline addStep(DataStep step) {
        if (statusManager != null && step instanceof AbstractDataStep) {
            ((AbstractDataStep) step).setStatusManager(statusManager);
        }
        steps.add(step);
        return this;  // Fluent interface
    }

    /**
     * Configure whether pipeline should stop on first error
     */
    public DataPipeline setStopOnError(boolean stopOnError) {
        this.stopOnError = stopOnError;
        return this;
    }

    /**
     * Set properties on all steps in the pipeline
     */
    public DataPipeline setProperties(Map<String, Object> properties) {
        for (DataStep step : steps) {
            step.setProperties(properties);
        }
        return this;
    }

    /**
     * Get the list of steps in this pipeline
     */
    public List<DataStep> getSteps() {
        return new ArrayList<>(steps);  // Return defensive copy
    }

    /**
     * Get the number of steps in this pipeline
     */
    public int getStepCount() {
        return steps.size();
    }

    /**
     * Execute all steps for a single transaction
     */
    public PipelineResult execute(DataContext context) {
        PipelineResult result = new PipelineResult();
        result.setTransactionId(context.getTransactionId());
        result.setStartTime(new Date());

        if (steps.isEmpty()) {
            LogUtil.error(CLASS_NAME, null, "CRITICAL ERROR: Pipeline has NO STEPS! Nothing will be processed!");
        }

        for (DataStep step : steps) {
            try {
                StepResult stepResult = step.execute(context, formDataDao);
                result.addStepResult(step.getStepName(), stepResult);

                if (!stepResult.isSuccess()) {
                    if (stopOnError) {
                        result.setSuccess(false);
                        result.setErrorMessage("Pipeline stopped at step: " + step.getStepName());
                        break;
                    }
                }

            } catch (Exception e) {
                LogUtil.error(CLASS_NAME, e,
                        "Unexpected ERROR in step: " + step.getStepName());

                StepResult errorResult = new StepResult(false, "Unexpected error: " + e.getMessage());
                result.addStepResult(step.getStepName(), errorResult);

                if (stopOnError) {
                    result.setSuccess(false);
                    result.setErrorMessage("Pipeline stopped due to unexpected error");
                    break;
                }
            }
        }

        result.setEndTime(new Date());
        result.setSuccess(result.getErrorMessage() == null);
        return result;
    }

    /**
     * Execute pipeline for multiple transactions
     */
    public BatchPipelineResult executeBatch(List<DataContext> contexts) {
        BatchPipelineResult batchResult = new BatchPipelineResult();
        batchResult.setStartTime(new Date());
        batchResult.setTotalTransactions(contexts.size());

        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < contexts.size(); i++) {
            DataContext context = contexts.get(i);
            for (DataStep step : steps) {
                step.setBatchContext(i, contexts.size());
            }
            PipelineResult result = execute(context);
            batchResult.addResult(result);

            if (result.isSuccess()) {
                successCount++;
            } else {
                failureCount++;
            }
        }

        batchResult.setSuccessCount(successCount);
        batchResult.setFailureCount(failureCount);
        batchResult.setEndTime(new Date());
        return batchResult;
    }
}

