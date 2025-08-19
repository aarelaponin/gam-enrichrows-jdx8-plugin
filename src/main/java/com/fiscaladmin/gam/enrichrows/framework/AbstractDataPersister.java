package com.fiscaladmin.gam.enrichrows.framework;

import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import java.util.*;

/**
 * Base class for data persisters with common functionality
 */
public abstract class AbstractDataPersister<T extends DataContext> 
        implements DataPersister<T> {
    
    protected final String className;
    
    public AbstractDataPersister() {
        this.className = getClass().getName();
    }
    
    @Override
    public BatchPersistenceResult persistBatch(List<T> contexts, FormDataDao dao, 
                                              Map<String, Object> parameters) {
        BatchPersistenceResult batchResult = new BatchPersistenceResult();
        batchResult.setStartTime(new Date());
        batchResult.setTargetStorage(getTargetStorage());
        
        for (T context : contexts) {
            try {
                PersistenceResult result = persist(context, dao, parameters);
                batchResult.addResult(result);
                
            } catch (Exception e) {
                LogUtil.error(className, e, "Error persisting record");
                PersistenceResult errorResult = new PersistenceResult(false);
                errorResult.setMessage("Error: " + e.getMessage());
                batchResult.addResult(errorResult);
            }
        }
        
        batchResult.setEndTime(new Date());
        return batchResult;
    }
    
    /**
     * Helper method to create a FormRow with proper ID
     */
    protected FormRow createFormRow() {
        FormRow row = new FormRow();
        row.setId(generateRecordId());
        return row;
    }
    
    /**
     * Generate a unique record ID
     */
    protected String generateRecordId() {
        return UUID.randomUUID().toString();
    }
    
    /**
     * Save a FormRow to database
     */
    protected boolean saveFormRow(FormDataDao dao, String tableName, FormRow row) {
        try {
            if (row.getId() == null || row.getId().isEmpty()) {
                row.setId(generateRecordId());
            }
            
            FormRowSet rowSet = new FormRowSet();
            rowSet.add(row);
            dao.saveOrUpdate(null, tableName, rowSet);
            return true;
        } catch (Exception e) {
            LogUtil.error(className, e, "Error saving to " + tableName);
            return false;
        }
    }
    
    /**
     * Update an existing FormRow in database
     */
    protected boolean updateFormRow(FormDataDao dao, String tableName, FormRow row) {
        try {
            FormRowSet rowSet = new FormRowSet();
            rowSet.add(row);
            dao.saveOrUpdate(null, tableName, rowSet);
            return true;
        } catch (Exception e) {
            LogUtil.error(className, e, "Error updating " + tableName);
            return false;
        }
    }
    
    /**
     * Load a record by ID
     */
    protected FormRow loadFormRow(FormDataDao dao, String tableName, String id) {
        try {
            FormRowSet rowSet = dao.find(null, tableName, "WHERE id = ?", 
                new Object[]{id}, null, false, 0, 1);
            if (rowSet != null && !rowSet.isEmpty()) {
                return rowSet.get(0);
            }
        } catch (Exception e) {
            LogUtil.error(className, e, "Error loading from " + tableName);
        }
        return null;
    }
    
    /**
     * Set a property value safely (handles null values)
     */
    protected void setPropertySafe(FormRow row, String property, Object value) {
        if (row != null && property != null && value != null) {
            row.setProperty(property, value.toString());
        }
    }
    
    /**
     * Get string value safely
     */
    protected String getStringValue(Object value) {
        return value != null ? value.toString() : null;
    }
}