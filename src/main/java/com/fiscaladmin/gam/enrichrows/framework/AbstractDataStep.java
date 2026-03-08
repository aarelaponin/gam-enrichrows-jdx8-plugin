package com.fiscaladmin.gam.enrichrows.framework;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.framework.status.StatusManager;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import java.util.Date;
import java.util.Map;

public abstract class AbstractDataStep implements DataStep {

    private final String className;
    protected Map<String, Object> properties;
    protected StatusManager statusManager;
    private int batchIndex = -1;
    private int batchTotal = -1;

    public AbstractDataStep() {
        this.className = getClass().getName();
    }

    @Override
    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

    public void setStatusManager(StatusManager statusManager) {
        this.statusManager = statusManager;
    }

    @Override
    public void setBatchContext(int currentIndex, int totalCount) {
        this.batchIndex = currentIndex;
        this.batchTotal = totalCount;
    }

    protected boolean isLastInBatch() {
        return batchIndex >= 0 && batchTotal >= 0 && batchIndex == batchTotal - 1;
    }

    protected int getBatchIndex() {
        return batchIndex;
    }

    protected int getBatchTotal() {
        return batchTotal;
    }

    protected Object getProperty(String key, Object defaultValue) {
        if (properties != null && properties.containsKey(key)) {
            return properties.get(key);
        }
        return defaultValue;
    }

    @Override
    public StepResult execute(DataContext context, FormDataDao formDataDao) {
        try {
            // Check preconditions
            if (!shouldExecute(context)) {
                return new StepResult(true, "Step skipped - preconditions not met");
            }

            // Execute the actual step logic

            return performStep(context, formDataDao);

        } catch (Exception e) {
            LogUtil.error(className, e, "Unexpected error in step: " + getStepName());
            return new StepResult(false, "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Perform the actual step logic - to be implemented by subclasses
     */
    protected abstract StepResult performStep(DataContext context, FormDataDao formDataDao);

    /**
     * Default implementation - execute for all transactions
     * Override in subclasses for specific conditions
     */
    @Override
    public boolean shouldExecute(DataContext context) {
        return true;
    }

    /**
     * Helper method to load a form row by ID
     */
    protected FormRow loadFormRow(FormDataDao formDataDao, String tableName, String id) {
        try {
            FormRowSet rowSet = formDataDao.find(
                    null,
                    tableName,
                    "WHERE id = ?",
                    new Object[]{id},
                    null,
                    false,
                    0,
                    1
            );

            if (rowSet != null && !rowSet.isEmpty()) {
                return rowSet.get(0);
            }
        } catch (Exception e) {
            LogUtil.error(className, e, "Error loading row from " + tableName + " with ID: " + id);
        }
        return null;
    }

    /**
     * Helper method to save or update a form row
     */
    protected boolean saveFormRow(FormDataDao formDataDao, String tableName, FormRow row) {
        try {
            FormRowSet rowSet = new FormRowSet();
            rowSet.add(row);
            formDataDao.saveOrUpdate(null, tableName, rowSet);
            return true;
        } catch (Exception e) {
            LogUtil.error(className, e, "Error saving row to " + tableName);
            return false;
        }
    }

    /**
     * Helper method to update transaction status
     * @deprecated Use StatusManager instead for status transitions
     */
    @Deprecated
    protected boolean updateTransactionStatus(DataContext context,
                                              FormDataDao formDataDao,
                                              String status) {
        LogUtil.warn(className, "updateTransactionStatus() is deprecated — use StatusManager");
        try {
            FormRow trxRow = context.getTransactionRow();
            trxRow.setProperty("status", status);
            trxRow.setProperty("last_modified", new Date().toString());

            String tableName = DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType()) ?
                    DomainConstants.TABLE_BANK_TOTAL_TRX : DomainConstants.TABLE_SECU_TOTAL_TRX;

            return saveFormRow(formDataDao, tableName, trxRow);
        } catch (Exception e) {
            LogUtil.error(className, e, "Error updating transaction status");
            return false;
        }
    }

    /**
     * Create audit log entry for this step
     */
    protected void createAuditLog(DataContext context,
                                  FormDataDao formDataDao,
                                  String action,
                                  String details) {
        try {
            FormRow auditRow = new FormRow();
            // Generate unique ID for the audit log row
            String auditId = java.util.UUID.randomUUID().toString();
            auditRow.setId(auditId);
            
            auditRow.setProperty("transaction_id", context.getTransactionId());
            auditRow.setProperty("statement_id", context.getStatementId());
            auditRow.setProperty("action", action);
            auditRow.setProperty("details", details);
            auditRow.setProperty("step_name", getStepName());
            auditRow.setProperty("timestamp", new Date().toString());

            saveFormRow(formDataDao, DomainConstants.TABLE_AUDIT_LOG, auditRow);
        } catch (Exception e) {
            LogUtil.error(className, e, "Error creating audit log");
        }
    }

    /**
     * §9b: Check if a field in additionalData is resolved (non-null, non-empty, not a sentinel).
     * Used by idempotency guards to skip already-resolved fields on re-enrichment.
     */
    protected boolean isFieldResolved(DataContext context, String fieldName, String... sentinels) {
        Object value = context.getAdditionalDataValue(fieldName);
        if (value == null) return false;
        String s = value.toString().trim();
        if (s.isEmpty()) return false;
        for (String sentinel : sentinels) {
            if (sentinel.equals(s)) return false;
        }
        return true;
    }

    /**
     * Parse amount string to double for calculations
     */
    protected double parseAmount(String amountStr) {
        if (amountStr == null || amountStr.trim().isEmpty()) {
            return 0.0;
        }

        try {
            // Remove currency symbols and spaces
            String cleaned = amountStr.replaceAll("[^0-9.-]", "");
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            LogUtil.error(className, e, "Error parsing amount: " + amountStr);
            return 0.0;
        }
    }
}