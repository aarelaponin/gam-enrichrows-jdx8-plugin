package com.fiscaladmin.gam.enrichrows.framework;

import org.joget.apps.form.dao.FormDataDao;
import java.util.List;
import java.util.Map;

/**
 * Interface for loading data to be processed by the pipeline.
 * This separates data fetching from data processing.
 */
public interface DataLoader<T extends DataContext> {
    
    /**
     * Load data that needs to be processed
     * @param dao FormDataDao for database access
     * @param parameters Optional parameters for filtering/configuration
     * @return List of contexts ready for processing
     */
    List<T> loadData(FormDataDao dao, Map<String, Object> parameters);
    
    /**
     * Get the name of this loader for logging
     */
    String getLoaderName();
    
    /**
     * Validate that required tables/data sources exist
     * @return true if validation passes, false otherwise
     */
    boolean validateDataSource(FormDataDao dao);
}