package com.fiscaladmin.gam.enrichrows.framework;

import org.joget.apps.form.dao.FormDataDao;
import java.util.List;
import java.util.Map;

/**
 * Interface for persisting processed data to storage.
 * This complements DataLoader by handling the output side of the pipeline.
 */
public interface DataPersister<T extends DataContext> {
    
    /**
     * Persist a single processed context to storage
     * @param context The processed context to persist
     * @param dao FormDataDao for database operations
     * @param parameters Optional parameters for persistence configuration
     * @return Result of the persistence operation
     */
    PersistenceResult persist(T context, FormDataDao dao, Map<String, Object> parameters);
    
    /**
     * Persist multiple processed contexts to storage
     * @param contexts List of processed contexts to persist
     * @param dao FormDataDao for database operations  
     * @param parameters Optional parameters for persistence configuration
     * @return Batch result of persistence operations
     */
    BatchPersistenceResult persistBatch(List<T> contexts, FormDataDao dao, Map<String, Object> parameters);
    
    /**
     * Get the name of this persister for logging
     */
    String getPersisterName();
    
    /**
     * Validate that target storage is available and configured
     * @return true if validation passes, false otherwise
     */
    boolean validateStorage(FormDataDao dao);
    
    /**
     * Get the target storage identifier (table name, file path, API endpoint, etc.)
     */
    String getTargetStorage();
}