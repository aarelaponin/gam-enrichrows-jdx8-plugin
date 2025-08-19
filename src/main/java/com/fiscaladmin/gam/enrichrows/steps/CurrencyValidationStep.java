package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;
import com.fiscaladmin.gam.enrichrows.framework.AbstractDataStep;
import com.fiscaladmin.gam.enrichrows.framework.StepResult;
import com.fiscaladmin.gam.enrichrows.framework.DataContext;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import java.util.*;

/**
 * Step 2: Currency Validation
 * Validates that the transaction currency exists in the currency master table
 * and creates exceptions for invalid currencies
 * 
 * This step directly extends AbstractDataStep for consistency with the pipeline framework
 */
public class CurrencyValidationStep extends AbstractDataStep {

    private static final String CLASS_NAME = CurrencyValidationStep.class.getName();

    @Override
    public String getStepName() {
        return "Currency Validation";
    }

    @Override
    protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
        try {
            String currency = context.getCurrency();

            // Check if currency is provided
            if (currency == null || currency.trim().isEmpty()) {
                LogUtil.error(CLASS_NAME, null,
                        "Currency is missing for transaction: " + context.getTransactionId());
                createCurrencyException(context, formDataDao,
                        "MISSING_CURRENCY", "Currency code is missing");
                return new StepResult(false, "Currency validation failed: Currency code is missing");
            }

            // Normalize currency code
            currency = currency.trim().toUpperCase();
            context.setCurrency(currency); // Update with normalized value

            LogUtil.info(CLASS_NAME, "Validating currency: " + currency +
                    " for transaction: " + context.getTransactionId());

            // Check if currency exists in master data
            if (!isValidCurrency(currency, formDataDao)) {
                LogUtil.error(CLASS_NAME, null,
                        "Invalid currency: " + currency + " for transaction: " + context.getTransactionId());
                createCurrencyException(context, formDataDao,
                        DomainConstants.EXCEPTION_INVALID_CURRENCY,
                        "Invalid currency code: " + currency);
                return new StepResult(false, "Currency validation failed: Invalid currency code: " + currency);
            }

            // Get additional currency information
            FormRow currencyRow = getCurrencyDetails(currency, formDataDao);
            if (currencyRow != null) {
                // Store currency details in context for later use
                Map<String, Object> additionalData = context.getAdditionalData();
                if (additionalData == null) {
                    additionalData = new HashMap<>();
                    context.setAdditionalData(additionalData);
                }
                // Using getProperty which handles the c_ prefix automatically
                additionalData.put("currency_name", currencyRow.getProperty("name"));
                additionalData.put("decimal_places", currencyRow.getProperty("decimal_places"));
                additionalData.put("currency_symbol", currencyRow.getProperty("symbol"));
            }

            // Update processing status
            context.setProcessingStatus(DomainConstants.PROCESSING_STATUS_CURRENCY_VALIDATED);
            context.addProcessedStep(DomainConstants.PROCESSING_STATUS_CURRENCY_VALIDATED);

            LogUtil.info(CLASS_NAME, "Currency validated successfully: " + currency +
                    " for transaction: " + context.getTransactionId());

            // Create audit log entry using the helper method from AbstractTransactionStep
            createAuditLog(context, formDataDao,
                    DomainConstants.AUDIT_CURRENCY_VALIDATED,
                    "Currency " + currency + " validated successfully");

            return new StepResult(true, "Currency validated successfully: " + currency);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Unexpected error validating currency for transaction: " + context.getTransactionId());
            createCurrencyException(context, formDataDao,
                    "CURRENCY_VALIDATION_ERROR",
                    "Error during currency validation: " + e.getMessage());
            return new StepResult(false, "Currency validation error: " + e.getMessage());
        }
    }

    @Override
    public boolean shouldExecute(DataContext context) {
        // Execute for all transactions that haven't failed yet
        return context.getErrorMessage() == null || context.getErrorMessage().isEmpty();
    }

    /**
     * Check if currency exists in currency master
     * Load all currencies and filter in memory to avoid SQL c_ prefix issues
     */
    private boolean isValidCurrency(String currency, FormDataDao formDataDao) {
        try {
            // Load all currency records - FormDataDao handles c_ prefix automatically
            FormRowSet currencyRows = formDataDao.find(
                    null,  // formDefId
                    DomainConstants.TABLE_CURRENCY_MASTER,
                    null,  // condition - null to get all records
                    null,  // params
                    null,  // sort
                    null,  // join
                    null,  // start
                    null   // rows
            );

            if (currencyRows != null && !currencyRows.isEmpty()) {
                // Search for matching currency code
                for (FormRow row : currencyRows) {
                    String code = row.getProperty("code");
                    if (currency.equals(code)) {
                        String status = row.getProperty("status");
                        // Check if currency is active
                        if (FrameworkConstants.STATUS_ACTIVE.equalsIgnoreCase(status)) {
                            return true;
                        } else {
                            LogUtil.warn(CLASS_NAME, "Currency " + currency + " exists but is not active");
                            return false;
                        }
                    }
                }
            }

            LogUtil.warn(CLASS_NAME, "Currency " + currency + " not found in master data");

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error checking currency validity for: " + currency);
        }

        return false;
    }

    /**
     * Get currency details from master data
     * Load all currencies and filter in memory to avoid SQL c_ prefix issues
     */
    private FormRow getCurrencyDetails(String currency, FormDataDao formDataDao) {
        try {
            // Load all currency records - FormDataDao handles c_ prefix automatically
            FormRowSet currencyRows = formDataDao.find(
                    null,  // formDefId
                    DomainConstants.TABLE_CURRENCY_MASTER,
                    null,  // condition - null to get all records
                    null,  // params
                    null,  // sort
                    null,  // join
                    null,  // start
                    null   // rows
            );

            if (currencyRows != null && !currencyRows.isEmpty()) {
                // Search for matching currency code with active status
                for (FormRow row : currencyRows) {
                    String code = row.getProperty("code");
                    String status = row.getProperty("status");
                    if (currency.equals(code) && FrameworkConstants.STATUS_ACTIVE.equalsIgnoreCase(status)) {
                        return row;
                    }
                }
            }

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error fetching currency details for: " + currency);
        }

        return null;
    }

    /**
     * Create exception record for invalid or missing currency
     * Uses FormDataDao.saveOrUpdate() which handles c_ prefix automatically
     */
    private void createCurrencyException(DataContext context,
                                         FormDataDao formDataDao,
                                         String exceptionType,
                                         String exceptionDetails) {
        try {
            FormRow exceptionRow = new FormRow();
            
            // Generate unique ID for the exception
            String exceptionId = UUID.randomUUID().toString();
            exceptionRow.setId(exceptionId);

            // Set exception identifiers
            exceptionRow.setProperty("transaction_id", context.getTransactionId());
            exceptionRow.setProperty("statement_id", context.getStatementId());
            exceptionRow.setProperty("source_type", context.getSourceType());

            // Set exception details
            exceptionRow.setProperty("exception_type", exceptionType);
            exceptionRow.setProperty("exception_details", exceptionDetails);
            exceptionRow.setProperty("exception_date", new Date().toString());

            // Set transaction details for reference
            exceptionRow.setProperty("amount", context.getAmount());
            exceptionRow.setProperty("currency", context.getCurrency());
            exceptionRow.setProperty("transaction_date", context.getTransactionDate());

            // Set priority based on amount
            String priority = calculateExceptionPriority(context);
            exceptionRow.setProperty("priority", priority);

            // Set status
            exceptionRow.setProperty("status", FrameworkConstants.STATUS_PENDING);

            // Additional context for resolution
            if (DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType())) {
                exceptionRow.setProperty("other_side_name", context.getOtherSideName());
                exceptionRow.setProperty("payment_description", context.getPaymentDescription());
            } else if (DomainConstants.SOURCE_TYPE_SECU.equals(context.getSourceType())) {
                exceptionRow.setProperty("ticker", context.getTicker());
                exceptionRow.setProperty("description", context.getDescription());
            }

            // Use FormDataDao.saveOrUpdate() to save - it handles c_ prefix automatically
            FormRowSet rowSet = new FormRowSet();
            rowSet.add(exceptionRow);
            formDataDao.saveOrUpdate(null, DomainConstants.TABLE_EXCEPTION_QUEUE, rowSet);

            LogUtil.info(CLASS_NAME, "Created currency exception for transaction: " +
                    context.getTransactionId() + ", Type: " + exceptionType);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error creating currency exception for transaction: " + context.getTransactionId());
        }
    }

    /**
     * Calculate exception priority based on transaction amount
     */
    private String calculateExceptionPriority(DataContext context) {
        try {
            String amountStr = context.getAmount();
            if (amountStr != null && !amountStr.trim().isEmpty()) {
                // Remove currency symbols and spaces
                String cleaned = amountStr.replaceAll("[^0-9.-]", "");
                double amount = Math.abs(Double.parseDouble(cleaned));

                // Priority based on amount thresholds
                if (amount >= 1000000) {
                    return "critical";
                } else if (amount >= 100000) {
                    return "high";
                } else if (amount >= 10000) {
                    return "medium";
                } else {
                    return "low";
                }
            }
        } catch (Exception e) {
            LogUtil.warn(CLASS_NAME, "Could not parse amount for priority calculation: " +
                    context.getAmount());
        }

        return "medium"; // Default priority
    }
}