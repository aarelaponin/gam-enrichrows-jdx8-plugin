package com.fiscaladmin.gam.enrichrows.framework;

import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import java.util.Date;

public abstract class AbstractDataStep implements DataStep {

    protected static final String STATUS_NEW = "new";
    protected static final String STATUS_ENRICHED = "enriched";
    protected static final String STATUS_FAILED = "failed";
    protected static final String STATUS_POSTED = "posted";

    private final String className;

    public AbstractDataStep() {
        this.className = getClass().getName();
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
     */
    protected boolean updateTransactionStatus(DataContext context,
                                              FormDataDao formDataDao,
                                              String status) {
        try {
            FormRow trxRow = context.getTransactionRow();
            trxRow.setProperty("status", status);
            trxRow.setProperty("last_modified", new Date().toString());

            String tableName = "bank".equals(context.getSourceType()) ?
                    "bank_total_trx" : "secu_total_trx";

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
            // Ensure status is never null
            String status = context.getProcessingStatus();
            if (status == null || status.isEmpty()) {
                status = "processing";
            }
            auditRow.setProperty("status", status);

            saveFormRow(formDataDao, "audit_log", auditRow);
        } catch (Exception e) {
            LogUtil.error(className, e, "Error creating audit log");
        }
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