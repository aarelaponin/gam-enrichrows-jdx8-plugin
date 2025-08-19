package com.fiscaladmin.gam.enrichrows.framework;

import org.joget.apps.form.dao.FormDataDao;

/**
 * Generic interface for data processing steps.
 * Each step represents a single processing stage in the data pipeline.
 * 
 * This interface is domain-agnostic and can be used for processing
 * any type of data through a pipeline pattern.
 */
public interface DataStep {
    /**
     * Execute this processing step
     * @param context Data context containing all data
     * @param formDataDao DAO for database operations
     * @return StepResult indicating success/failure and any messages
     */
    StepResult execute(DataContext context, FormDataDao formDataDao);

    /**
     * Get the name of this step for logging
     */
    String getStepName();

    /**
     * Check if this step should be executed for the given data context
     */
    boolean shouldExecute(DataContext context);
}
