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
 * Step 3: Counterparty Determination
 *
 * Identifies the counterparty for each transaction based on:
 * - For bank transactions: The other side's bank (from other_side_bic field)
 * - For securities transactions: The statement's bank (acting as custodian/broker)
 *
 * This distinction is critical for proper F14 rule matching in the next step.
 * The counterparty context helps determine which specific transaction matching rules
 * should be applied during F14 rule processing.
 */
public class CounterpartyDeterminationStep extends AbstractDataStep {

    private static final String CLASS_NAME = CounterpartyDeterminationStep.class.getName();

    @Override
    public String getStepName() {
        return "Counterparty Determination";
    }

    @Override
    protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
        try {
            LogUtil.info(CLASS_NAME, "Starting counterparty determination for transaction: " +
                    context.getTransactionId() + ", Type: " + context.getSourceType());

            String counterpartyId = null;
            String counterpartyType = null;
            String counterpartyBic = null;
            String counterpartyName = null;

            if (DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType())) {
                // For bank transactions: use statement's bank as counterparty (for F14 rule matching)
                // The other side's bank is stored as additional info but not used for counterparty determination
                String statementBank = context.getStatementBank();
                LogUtil.info(CLASS_NAME, "Bank transaction: Using statement bank as counterparty: " + statementBank);
                counterpartyId = findCounterpartyByBic(statementBank, formDataDao);
                counterpartyType = "Bank";
                counterpartyBic = statementBank;
                counterpartyName = getStatementBankName(statementBank, formDataDao);
                
                // Store other side's information as additional data (for reference/reporting)
                String otherSideBic = context.getOtherSideBic();
                if (otherSideBic != null && !otherSideBic.trim().isEmpty()) {
                    Map<String, Object> additionalData = context.getAdditionalData();
                    if (additionalData == null) {
                        additionalData = new HashMap<>();
                        context.setAdditionalData(additionalData);
                    }
                    additionalData.put("other_side_bic", otherSideBic);
                    additionalData.put("other_side_name", context.getOtherSideName());
                    
                    LogUtil.info(CLASS_NAME, "Stored other side info: BIC=" + otherSideBic + 
                               ", Name=" + context.getOtherSideName());
                }

            } else if (DomainConstants.SOURCE_TYPE_SECU.equals(context.getSourceType())) {
                // For securities transactions: counterparty is the statement's bank (custodian/broker)
                counterpartyId = determineSecuritiesCounterparty(context, formDataDao);
                counterpartyType = determineSecuritiesCounterpartyType(context);
                // For securities, the statement bank acts as the counterparty
                counterpartyBic = context.getStatementBank();
                counterpartyName = getStatementBankName(context.getStatementBank(), formDataDao);
            }

            // Store counterparty information in context for subsequent steps
            if (counterpartyId != null) {
                updateContextWithCounterparty(context, counterpartyId, counterpartyType,
                        counterpartyBic, counterpartyName, formDataDao);

                // Update processing status
                context.setProcessingStatus(DomainConstants.PROCESSING_STATUS_COUNTERPARTY_DETERMINED);
                context.addProcessedStep(DomainConstants.PROCESSING_STATUS_COUNTERPARTY_DETERMINED);

                // Create audit log
                createAuditLog(context, formDataDao,
                        "COUNTERPARTY_DETERMINED",
                        String.format("Counterparty identified: %s (Type: %s, BIC: %s)",
                                counterpartyId, counterpartyType, counterpartyBic));

                LogUtil.info(CLASS_NAME,
                        String.format("Counterparty determined successfully for transaction %s: %s",
                                context.getTransactionId(), counterpartyId));

                return new StepResult(true,
                        String.format("Counterparty determined: %s", counterpartyId));

            } else {
                // Counterparty not found - create exception but continue processing
                LogUtil.warn(CLASS_NAME,
                        "Counterparty not found for transaction: " + context.getTransactionId());

                createCounterpartyException(context, formDataDao,
                        "COUNTERPARTY_NOT_FOUND",
                        String.format("Could not determine counterparty. BIC: %s, Statement Bank: %s",
                                counterpartyBic, context.getStatementBank()));

                // Set default values to allow processing to continue
                updateContextWithCounterparty(context, FrameworkConstants.ENTITY_UNKNOWN, "Unknown",
                        counterpartyBic != null ? counterpartyBic : FrameworkConstants.ENTITY_UNKNOWN,
                        counterpartyName != null ? counterpartyName : "Unknown Counterparty", formDataDao);

                return new StepResult(true,
                        "Counterparty not found - exception created, continuing with UNKNOWN");
            }

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Unexpected error determining counterparty for transaction: " +
                            context.getTransactionId());

            createCounterpartyException(context, formDataDao,
                    "COUNTERPARTY_DETERMINATION_ERROR",
                    "Error during counterparty determination: " + e.getMessage());

            return new StepResult(false,
                    "Counterparty determination error: " + e.getMessage());
        }
    }

    @Override
    public boolean shouldExecute(DataContext context) {
        // Execute for all transactions that haven't failed yet
        return context.getErrorMessage() == null || context.getErrorMessage().isEmpty();
    }

    /**
     * Determine counterparty for bank transactions
     * Looks up the other side's bank using BIC code
     */
    private String determineBankCounterparty(DataContext context, FormDataDao formDataDao) {
        String otherSideBic = context.getOtherSideBic();

        if (otherSideBic == null || otherSideBic.trim().isEmpty()) {
            LogUtil.warn(CLASS_NAME,
                    "No BIC code available for bank transaction: " + context.getTransactionId());
            return null;
        }

        // Normalize BIC code
        otherSideBic = otherSideBic.trim().toUpperCase();

        LogUtil.info(CLASS_NAME, "Looking up counterparty for BIC: " + otherSideBic);

        // First, try to find in counterparty_master table
        String counterpartyId = findCounterpartyByBic(otherSideBic, formDataDao);

        if (counterpartyId == null) {
            // If not found in counterparty_master, check if bank exists in bank table
            if (bankExistsByBic(otherSideBic, formDataDao)) {
                LogUtil.info(CLASS_NAME,
                        "Bank exists but no counterparty record for BIC: " + otherSideBic);
                // Bank exists but no counterparty record - this needs manual setup
                return null;
            }
        }

        return counterpartyId;
    }

    /**
     * Determine counterparty for securities transactions
     * Uses the statement's bank as the counterparty (custodian/broker)
     */
    private String determineSecuritiesCounterparty(DataContext context, FormDataDao formDataDao) {
        String statementBank = context.getStatementBank();

        if (statementBank == null || statementBank.trim().isEmpty()) {
            LogUtil.warn(CLASS_NAME,
                    "No statement bank for securities transaction: " + context.getTransactionId());
            return null;
        }

        // Normalize statement bank (it's already a BIC code from the statement)
        statementBank = statementBank.trim().toUpperCase();

        LogUtil.info(CLASS_NAME,
                "Securities transaction: Looking up counterparty for statement bank BIC: " + statementBank);

        // Find counterparty by bank BIC
        String counterpartyId = findCounterpartyByBic(statementBank, formDataDao);
        
        LogUtil.info(CLASS_NAME, String.format(
            "Securities counterparty determination result: BIC='%s' -> CounterpartyId='%s'",
            statementBank, counterpartyId));
            
        return counterpartyId;
    }

    /**
     * Find counterparty in counterparty_master table by BIC code
     * Handles the relationship between counterparty and bank/broker/custodian entities
     */
    private String findCounterpartyByBic(String bic, FormDataDao formDataDao) {
        try {
            LogUtil.info(CLASS_NAME, "Finding counterparty for BIC: " + bic);
            
            // Load all counterparty records
            FormRowSet counterpartyRows = formDataDao.find(
                    null,
                    DomainConstants.TABLE_COUNTERPARTY_MASTER,
                    null,  // No condition - load all and filter in memory
                    null,
                    null,
                    null,
                    null,
                    null
            );

            if (counterpartyRows != null && !counterpartyRows.isEmpty()) {
                LogUtil.info(CLASS_NAME, "Found " + counterpartyRows.size() + " counterparty records to search");
                
                for (FormRow row : counterpartyRows) {
                    // Debug: Log all properties for first few records
                    LogUtil.info(CLASS_NAME, String.format(
                        "Checking counterparty record: id=%s, counterpartyId=%s, bankId=%s, counterpartyType=%s, isActive=%s",
                        row.getId(), 
                        row.getProperty("counterpartyId"),
                        row.getProperty("bankId"),
                        row.getProperty("counterpartyType"),
                        row.getProperty("isActive")));
                    
                    // Check if this counterparty is active
                    String isActive = row.getProperty("isActive");
                    if (!"true".equals(isActive)) {
                        LogUtil.info(CLASS_NAME, "Skipping inactive counterparty: " + row.getId());
                        continue;  // Skip inactive counterparties
                    }

                    String counterpartyType = row.getProperty("counterpartyType");

                    // Based on counterparty type, check the appropriate ID field
                    if ("Bank".equals(counterpartyType)) {
                        String bankId = row.getProperty("bankId");
                        LogUtil.info(CLASS_NAME, String.format(
                            "Checking Bank type: bankId='%s' vs BIC='%s'", bankId, bic));
                        
                        if (bic.equals(bankId)) {  // bankId stores the BIC code
                            String businessCounterpartyId = row.getProperty("counterpartyId");
                            LogUtil.info(CLASS_NAME,
                                    "MATCH FOUND! Bank counterparty: " + businessCounterpartyId + " (record ID: " + row.getId() + ") for BIC: " + bic);
                            return businessCounterpartyId;
                        }
                    } else if ("Custodian".equals(counterpartyType)) {
                        String custodianId = row.getProperty("custodianId");
                        LogUtil.info(CLASS_NAME, String.format(
                            "Checking Custodian type: custodianId='%s' vs BIC='%s'", custodianId, bic));
                        
                        if (bic.equals(custodianId)) {  // custodianId stores the BIC code
                            String businessCounterpartyId = row.getProperty("counterpartyId");
                            LogUtil.info(CLASS_NAME,
                                    "MATCH FOUND! Custodian counterparty: " + businessCounterpartyId + " (record ID: " + row.getId() + ") for BIC: " + bic);
                            return businessCounterpartyId;
                        }
                    } else if ("Broker".equals(counterpartyType)) {
                        String brokerId = row.getProperty("brokerId");
                        LogUtil.info(CLASS_NAME, String.format(
                            "Checking Broker type: brokerId='%s' vs BIC='%s'", brokerId, bic));
                        
                        // For brokers, we might need to look up the broker's BIC separately
                        if (brokerMatchesBic(brokerId, bic, formDataDao)) {
                            String businessCounterpartyId = row.getProperty("counterpartyId");
                            LogUtil.info(CLASS_NAME,
                                    "MATCH FOUND! Broker counterparty: " + businessCounterpartyId + " (record ID: " + row.getId() + ") for BIC: " + bic);
                            return businessCounterpartyId;
                        }
                    } else {
                        LogUtil.info(CLASS_NAME, "Unknown counterparty type: " + counterpartyType);
                    }
                }
            } else {
                LogUtil.warn(CLASS_NAME, "No counterparty records found in table!");
            }

            LogUtil.warn(CLASS_NAME, "No counterparty found for BIC: " + bic + " after checking all records");

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error finding counterparty for BIC: " + bic);
        }

        return null;
    }

    /**
     * Check if a bank exists in the bank master table
     */
    private boolean bankExistsByBic(String bic, FormDataDao formDataDao) {
        try {
            FormRowSet bankRows = formDataDao.find(
                    null,
                    "bank",  // Bank master table
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            if (bankRows != null && !bankRows.isEmpty()) {
                for (FormRow row : bankRows) {
                    String swiftCode = row.getProperty("swift_code_bic");
                    if (bic.equals(swiftCode)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error checking bank existence for BIC: " + bic);
        }

        return false;
    }

    /**
     * Check if a broker matches the given BIC
     * This might require looking up the broker record to get its BIC
     */
    private boolean brokerMatchesBic(String brokerId, String bic, FormDataDao formDataDao) {
        if (brokerId == null || brokerId.trim().isEmpty()) {
            return false;
        }

        try {
            // Load broker record to check its BIC
            FormRowSet brokerRows = formDataDao.find(
                    null,
                    "broker",  // Broker master table
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            if (brokerRows != null && !brokerRows.isEmpty()) {
                for (FormRow row : brokerRows) {
                    if (brokerId.equals(row.getId()) || brokerId.equals(row.getProperty("brokerId"))) {
                        // Check if this broker's BIC matches
                        String brokerBic = row.getProperty("swift_code_bic");
                        if (brokerBic == null) {
                            brokerBic = row.getProperty("bic_code");
                        }
                        return bic.equals(brokerBic);
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error checking broker BIC for broker ID: " + brokerId);
        }

        return false;
    }

    /**
     * Determine the type of counterparty for securities transactions
     * Based on transaction type, this could be a custodian or broker
     */
    private String determineSecuritiesCounterpartyType(DataContext context) {
        String transactionType = context.getType();

        if (transactionType != null) {
            transactionType = transactionType.toUpperCase();

            // Trading activities typically involve brokers
            if (transactionType.contains("BUY") || transactionType.contains("SELL") ||
                    transactionType.contains("TRADE")) {
                return "Broker";
            }

            // Custody activities
            if (transactionType.contains("CUSTODY") || transactionType.contains("SAFEKEEPING") ||
                    transactionType.contains("DIVIDEND") || transactionType.contains("CORPORATE")) {
                return "Custodian";
            }
        }

        // Default to Custodian for securities transactions
        return "Custodian";
    }

    /**
     * Get the name of the statement bank
     */
    private String getStatementBankName(String bankBic, FormDataDao formDataDao) {
        if (bankBic == null || bankBic.trim().isEmpty()) {
            return "Unknown Bank";
        }

        try {
            FormRowSet bankRows = formDataDao.find(
                    null,
                    "bank",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            if (bankRows != null && !bankRows.isEmpty()) {
                for (FormRow row : bankRows) {
                    String swiftCode = row.getProperty("swift_code_bic");
                    if (bankBic.equals(swiftCode)) {
                        return row.getProperty("name");
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error getting bank name for BIC: " + bankBic);
        }

        return bankBic;  // Return BIC if name not found
    }

    /**
     * Update transaction context with counterparty information
     */
    private void updateContextWithCounterparty(DataContext context, String counterpartyId,
                                               String counterpartyType, String counterpartyBic,
                                               String counterpartyName, FormDataDao formDataDao) {
        // Get or create additional data map
        Map<String, Object> additionalData = context.getAdditionalData();
        if (additionalData == null) {
            additionalData = new HashMap<>();
            context.setAdditionalData(additionalData);
        }

        // DEBUG: Log what we're storing
        LogUtil.info(CLASS_NAME, "=== DEBUG: STORING COUNTERPARTY IN CONTEXT ===");
        LogUtil.info(CLASS_NAME, "Transaction ID: " + context.getTransactionId());
        LogUtil.info(CLASS_NAME, "Counterparty ID being stored: '" + counterpartyId + "'");
        LogUtil.info(CLASS_NAME, "Counterparty Type: '" + counterpartyType + "'");
        LogUtil.info(CLASS_NAME, "Counterparty BIC: '" + counterpartyBic + "'");
        LogUtil.info(CLASS_NAME, "Counterparty Name: '" + counterpartyName + "'");
        
        // Store counterparty information for use in subsequent steps
        additionalData.put("counterparty_id", counterpartyId);
        additionalData.put("counterparty_type", counterpartyType);
        additionalData.put("counterparty_bic", counterpartyBic);
        additionalData.put("counterparty_name", counterpartyName);
        
        // DEBUG: Verify it was stored
        LogUtil.info(CLASS_NAME, "Verifying storage - counterparty_id in additionalData: '" + additionalData.get("counterparty_id") + "'");
        LogUtil.info(CLASS_NAME, "==============================================");

        // Also store the short code if available (used for GL account construction)
        String shortCode = getCounterpartyShortCode(counterpartyId, formDataDao);
        if (shortCode != null) {
            additionalData.put("counterparty_short_code", shortCode);
        }

        LogUtil.info(CLASS_NAME,
                String.format("Context updated with counterparty: ID=%s, Type=%s, BIC=%s, ShortCode=%s",
                        counterpartyId, counterpartyType, counterpartyBic, shortCode));
    }

    /**
     * Get the short code for a counterparty (used in GL account construction)
     */
    private String getCounterpartyShortCode(String counterpartyId, FormDataDao formDataDao) {
        if ("UNKNOWN".equals(counterpartyId)) {
            return "UNK";
        }

        try {
            // Now counterpartyId is the business ID (e.g., "CPT0143"), not the record ID
            // We need to find the counterparty by its business ID
            // Note: The database column name is "c_counterpartyId" (with c_ prefix and 'y')
            FormRowSet counterpartyRows = formDataDao.find(
                    null,
                    DomainConstants.TABLE_COUNTERPARTY_MASTER,
                    "WHERE c_counterpartyId = ?",
                    new Object[]{counterpartyId},
                    null,
                    null,
                    null,
                    null
            );

            if (counterpartyRows != null && !counterpartyRows.isEmpty()) {
                FormRow counterpartyRow = counterpartyRows.get(0);
                String shortCode = counterpartyRow.getProperty("shortCode");
                if (shortCode != null && !shortCode.trim().isEmpty()) {
                    return shortCode.trim().toUpperCase();
                }
            }
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error getting short code for counterparty: " + counterpartyId);
        }

        return null;
    }

    /**
     * Create exception record for counterparty issues
     */
    private void createCounterpartyException(DataContext context, FormDataDao formDataDao,
                                             String exceptionType, String exceptionDetails) {
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

            // Set priority based on amount and type
            String priority = calculateExceptionPriority(context, exceptionType);
            exceptionRow.setProperty("priority", priority);

            // Set status
            exceptionRow.setProperty("status", FrameworkConstants.STATUS_PENDING);

            // Additional context for resolution
            if (DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType())) {
                exceptionRow.setProperty("other_side_bic", context.getOtherSideBic());
                exceptionRow.setProperty("other_side_name", context.getOtherSideName());
                exceptionRow.setProperty("payment_description", context.getPaymentDescription());
            } else if (DomainConstants.SOURCE_TYPE_SECU.equals(context.getSourceType())) {
                exceptionRow.setProperty("statement_bank", context.getStatementBank());
                exceptionRow.setProperty("ticker", context.getTicker());
                exceptionRow.setProperty("type", context.getType());
                exceptionRow.setProperty("description", context.getDescription());
            }

            // Save exception
            FormRowSet rowSet = new FormRowSet();
            rowSet.add(exceptionRow);
            formDataDao.saveOrUpdate(null, DomainConstants.TABLE_EXCEPTION_QUEUE, rowSet);

            LogUtil.info(CLASS_NAME,
                    String.format("Created counterparty exception for transaction %s: Type=%s",
                            context.getTransactionId(), exceptionType));

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error creating counterparty exception for transaction: " +
                            context.getTransactionId());
        }
    }

    /**
     * Calculate exception priority based on transaction amount and exception type
     */
    private String calculateExceptionPriority(DataContext context, String exceptionType) {
        try {
            // Counterparty exceptions are generally medium priority
            // but can be elevated based on amount
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
            LogUtil.warn(CLASS_NAME,
                    "Could not parse amount for priority calculation: " + context.getAmount());
        }

        return "medium";  // Default priority for counterparty exceptions
    }
}