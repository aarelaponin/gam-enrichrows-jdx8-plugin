package com.fiscaladmin.gam.enrichrows.framework;

import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import java.util.*;

/**
 * Base class for data loaders with common functionality
 */
public abstract class AbstractDataLoader<T extends DataContext> 
        implements DataLoader<T> {
    
    protected final String className;
    
    public AbstractDataLoader() {
        this.className = getClass().getName();
    }
    
    @Override
    public List<T> loadData(FormDataDao dao, Map<String, Object> parameters) {
        try {
            // Validate data source
            if (!validateDataSource(dao)) {
                return new ArrayList<>();
            }
            
            // Load the data
            List<T> data = performLoad(dao, parameters);
            
            return data;
            
        } catch (Exception e) {
            LogUtil.error(className, e, "Error loading data");
            return new ArrayList<>();
        }
    }
    
    /**
     * Perform the actual data loading - to be implemented by subclasses
     */
    protected abstract List<T> performLoad(FormDataDao dao, Map<String, Object> parameters);
    
    /**
     * Helper method to load records from a table
     */
    protected FormRowSet loadRecords(FormDataDao dao, String tableName, 
                                    String condition, Object[] params,
                                    String sortField, boolean descending,
                                    Integer limit) {
        try {
            return dao.find(null, tableName, condition, params, 
                          sortField, descending, 0, 
                          limit != null ? limit : 10000);
        } catch (Exception e) {
            LogUtil.error(className, e, "Error loading from " + tableName);
            return new FormRowSet();
        }
    }
    
    /**
     * Helper method to filter records by status
     */
    protected List<FormRow> filterByStatus(FormRowSet rows, String statusField, 
                                          String expectedStatus) {
        List<FormRow> filtered = new ArrayList<>();
        if (rows != null) {
            for (FormRow row : rows) {
                if (expectedStatus.equals(row.getProperty(statusField))) {
                    filtered.add(row);
                }
            }
        }
        return filtered;
    }
    
    /**
     * Helper method to filter records by multiple criteria
     */
    protected List<FormRow> filterByCriteria(FormRowSet rows, Map<String, String> criteria) {
        List<FormRow> filtered = new ArrayList<>();
        if (rows != null && criteria != null) {
            for (FormRow row : rows) {
                boolean matches = true;
                for (Map.Entry<String, String> criterion : criteria.entrySet()) {
                    String fieldValue = row.getProperty(criterion.getKey());
                    if (!criterion.getValue().equals(fieldValue)) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    filtered.add(row);
                }
            }
        }
        return filtered;
    }
    
    /**
     * Sort contexts by date field
     */
    protected void sortByDate(List<T> contexts, final String dateFieldGetter) {
        contexts.sort((c1, c2) -> {
            String date1 = getDateValue(c1, dateFieldGetter);
            String date2 = getDateValue(c2, dateFieldGetter);
            if (date1 == null) return -1;
            if (date2 == null) return 1;
            return date1.compareTo(date2);
        });
    }
    
    private String getDateValue(T context, String fieldName) {
        // This is a simplified version - in real implementation would use reflection
        // or a proper getter method
        if ("transactionDate".equals(fieldName)) {
            return context.getTransactionDate();
        }
        return null;
    }
}