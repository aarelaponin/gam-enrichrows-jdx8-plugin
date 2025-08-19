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
 * Step 5: Customer Identification
 *
 * Identifies the customer for each transaction using multiple methods:
 * - Primary: Use customer_id field which maps to:
 *   - For bank transactions: registrationNumber (companies) or personalId (individuals)
 *   - For securities transactions: may be actual customer ID or registration/personal ID
 * - Secondary: Match by account number
 * - Tertiary: Extract and match registration number from reference/description fields
 * - Quaternary: Match by name pattern (with lower confidence)
 *
 * If no customer found, uses UNKNOWN and creates a high-priority exception.
 * This step is critical for GL account construction and position tracking.
 */
public class CustomerIdentificationStep extends AbstractDataStep {

    private static final String CLASS_NAME = CustomerIdentificationStep.class.getName();

    // Confidence levels for different identification methods
    private static final int CONFIDENCE_DIRECT_ID = 100;
    private static final int CONFIDENCE_ACCOUNT_NUMBER = 95;
    private static final int CONFIDENCE_REGISTRATION_NUMBER = 90;
    private static final int CONFIDENCE_NAME_MATCH = 70;
    private static final int CONFIDENCE_UNKNOWN = 0;

    @Override
    public String getStepName() {
        return "Customer Identification";
    }

    @Override
    protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
        try {
            String customerIdValue = context.getCustomerId();
            LogUtil.info(CLASS_NAME, "Starting customer identification for transaction: " +
                    context.getTransactionId() + ", Type: " + context.getSourceType() + 
                    ", Customer ID field value: " + (customerIdValue != null ? customerIdValue : "NULL"));

            String customerId = null;
            String customerName = null;
            String customerCode = null;
            int confidenceLevel = 0;
            String identificationMethod = null;

            // Try multiple identification methods in order of preference

            // Method 1: Direct customer ID (highest confidence)
            if (customerId == null) {
                customerId = identifyByDirectId(context, formDataDao);
                if (customerId != null) {
                    confidenceLevel = CONFIDENCE_DIRECT_ID;
                    identificationMethod = "DIRECT_ID";
                    LogUtil.info(CLASS_NAME, "Customer identified by direct ID: " + customerId);
                }
            }

            // Method 2: Account number matching
            if (customerId == null) {
                customerId = identifyByAccountNumber(context, formDataDao);
                if (customerId != null) {
                    confidenceLevel = CONFIDENCE_ACCOUNT_NUMBER;
                    identificationMethod = "ACCOUNT_NUMBER";
                    LogUtil.info(CLASS_NAME, "Customer identified by account number: " + customerId);
                }
            }

            // Method 3: Registration number extraction from reference/description
            if (customerId == null) {
                customerId = identifyByRegistrationNumber(context, formDataDao);
                if (customerId != null) {
                    confidenceLevel = CONFIDENCE_REGISTRATION_NUMBER;
                    identificationMethod = "REGISTRATION_NUMBER_EXTRACTED";
                    LogUtil.info(CLASS_NAME,
                            "Customer identified by extracted registration number: " + customerId);
                }
            }

            // Method 4: Name pattern matching (lower confidence)
            if (customerId == null) {
                customerId = identifyByNamePattern(context, formDataDao);
                if (customerId != null) {
                    confidenceLevel = CONFIDENCE_NAME_MATCH;
                    identificationMethod = "NAME_PATTERN";
                    LogUtil.info(CLASS_NAME, "Customer identified by name pattern: " + customerId);
                }
            }

            // Get customer details if identified
            if (customerId != null) {
                FormRow customerRow = loadFormRow(formDataDao, DomainConstants.TABLE_CUSTOMER_MASTER, customerId);
                if (customerRow != null) {
                    customerName = customerRow.getProperty("name");
                    customerCode = customerRow.getProperty("customer_code");

                    // Verify customer is active
                    String status = customerRow.getProperty("status");
                    if (!FrameworkConstants.STATUS_ACTIVE.equalsIgnoreCase(status)) {
                        LogUtil.warn(CLASS_NAME, "Customer " + customerId + " is not active");
                        // Create exception for inactive customer
                        createCustomerException(context, formDataDao,
                                "INACTIVE_CUSTOMER",
                                String.format("Customer %s is inactive", customerId),
                                "high");
                    }
                }
            }

            // Update context with customer information
            if (customerId != null) {
                updateContextWithCustomer(context, customerId, customerName, customerCode,
                        confidenceLevel, identificationMethod, formDataDao);

                // Update processing status
                context.setProcessingStatus(DomainConstants.PROCESSING_STATUS_CUSTOMER_IDENTIFIED);
                context.addProcessedStep(DomainConstants.PROCESSING_STATUS_CUSTOMER_IDENTIFIED);

                // DO NOT update the source transaction record - all findings will be saved 
                // to a new enrichment record in Step 8
                // Just update the context for downstream processing
                context.setCustomerId(customerId);

                // Create audit log
                createAuditLog(context, formDataDao,
                        DomainConstants.AUDIT_CUSTOMER_IDENTIFIED,
                        String.format("Customer identified: %s (Method: %s, Confidence: %d%%)",
                                customerId, identificationMethod, confidenceLevel));

                LogUtil.info(CLASS_NAME,
                        String.format("Customer identified successfully for transaction %s: %s (Confidence: %d%%)",
                                context.getTransactionId(), customerId, confidenceLevel));

                // If confidence is low, create a warning exception
                if (confidenceLevel < 80) {
                    createCustomerException(context, formDataDao,
                            "LOW_CONFIDENCE_IDENTIFICATION",
                            String.format("Customer identified with low confidence (%d%%) using %s method",
                                    confidenceLevel, identificationMethod),
                            "low");
                }

                return new StepResult(true,
                        String.format("Customer identified: %s (Confidence: %d%%)",
                                customerId, confidenceLevel));

            } else {
                // Customer not found - create high-priority exception
                String customerIdFieldValue = context.getCustomerId();
                LogUtil.error(CLASS_NAME, null,
                        String.format("Customer not found for transaction: %s, customer_id field was: '%s', other_side_name: '%s'",
                                context.getTransactionId(), 
                                customerIdFieldValue != null ? customerIdFieldValue : "NULL",
                                context.getOtherSideName()));

                createCustomerException(context, formDataDao,
                        DomainConstants.EXCEPTION_MISSING_CUSTOMER,
                        String.format("Could not identify customer. customer_id='%s', other_side_name='%s'",
                                customerIdFieldValue, context.getOtherSideName()),
                        "high");

                // Set UNKNOWN customer to allow processing to continue
                updateContextWithCustomer(context, "UNKNOWN", "Unknown Customer", "UNK",
                        CONFIDENCE_UNKNOWN, "NONE", formDataDao);

                context.setCustomerId("UNKNOWN");

                return new StepResult(true,
                        "Customer not found - high-priority exception created, continuing with UNKNOWN");
            }

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Unexpected error identifying customer for transaction: " +
                            context.getTransactionId());

            createCustomerException(context, formDataDao,
                    "CUSTOMER_IDENTIFICATION_ERROR",
                    "Error during customer identification: " + e.getMessage(),
                    "high");

            return new StepResult(false,
                    "Customer identification error: " + e.getMessage());
        }
    }

    @Override
    public boolean shouldExecute(DataContext context) {
        // Only execute for bank transactions that haven't failed yet
        // Securities transactions don't have individual customers (they are bank's own portfolio operations)
        if (!DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType())) {
            LogUtil.info(CLASS_NAME, "Skipping customer identification for securities transaction: " + 
                        context.getTransactionId() + " (securities are bank portfolio operations)");
            return false;
        }
        
        // Execute for bank transactions that haven't failed yet
        return context.getErrorMessage() == null || context.getErrorMessage().isEmpty();
    }

    /**
     * Method 1: Identify customer by direct ID field
     * The customer_id field may contain:
     * - Actual customer ID (CUST-XXXXXX format)
     * - Registration number (for companies)
     * - Personal ID (for individuals)
     */
    private String identifyByDirectId(DataContext context, FormDataDao formDataDao) {
        String customerIdField = context.getCustomerId();

        if (customerIdField == null || customerIdField.trim().isEmpty()) {
            LogUtil.info(CLASS_NAME, "No customer_id field value for transaction: " + context.getTransactionId());
            return null;
        }

        customerIdField = customerIdField.trim();
        LogUtil.info(CLASS_NAME, "Transaction " + context.getTransactionId() + " - Checking customer ID field: " + customerIdField);

        // First check if it's an actual customer ID (CUST-XXXXXX format or similar)
        if (customerIdField.startsWith("CUST-") || customerIdField.matches("^[A-Z]+-\\d+$")) {
            // This looks like an actual customer ID
            if (customerExists(customerIdField, formDataDao)) {
                LogUtil.info(CLASS_NAME, "Transaction " + context.getTransactionId() + " - Found customer by direct ID: " + customerIdField);
                return customerIdField;
            } else {
                LogUtil.warn(CLASS_NAME, "Transaction " + context.getTransactionId() + " - Customer ID not found in database: " + customerIdField);
                return null;
            }
        }
        
        // Not a customer ID format, try as registration number or personal ID
        String customerId = findCustomerByRegistrationOrPersonalId(customerIdField, formDataDao);
        if (customerId != null) {
            LogUtil.info(CLASS_NAME,
                    "Transaction " + context.getTransactionId() + " - Found customer by registration/personal ID: " + customerId);
            return customerId;
        }

        LogUtil.warn(CLASS_NAME, "Transaction " + context.getTransactionId() + " - Customer not found for ID field: " + customerIdField);
        return null;
    }

    /**
     * Find customer by registration number (companies) or personal ID (individuals)
     */
    private String findCustomerByRegistrationOrPersonalId(String idValue, FormDataDao formDataDao) {
        if (idValue == null || idValue.trim().isEmpty()) {
            return null;
        }

        idValue = idValue.trim();

        try {
            FormRowSet customerRows = formDataDao.find(
                    null,
                    DomainConstants.TABLE_CUSTOMER_MASTER,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            if (customerRows != null && !customerRows.isEmpty()) {
                LogUtil.info(CLASS_NAME, "Searching " + customerRows.size() + " customer records for ID: " + idValue);
                
                // Debug: Log first few customer records to see field values
                int debugCount = 0;
                for (FormRow row : customerRows) {
                    if (debugCount++ < 3) {
                        LogUtil.info(CLASS_NAME, 
                            String.format("Sample customer %s: registrationNumber=%s, personalId=%s, tax_id=%s", 
                                row.getId(), 
                                row.getProperty("registrationNumber"),
                                row.getProperty("personalId"),
                                row.getProperty("tax_id")));
                    }
                    
                    // Check registrationNumber for companies
                    // Note: Joget stores this as registrationNumber (without c_ prefix in getProperty)
                    String registrationNumber = row.getProperty("registrationNumber");
                    if (registrationNumber != null) {
                        registrationNumber = registrationNumber.trim();
                        if (registrationNumber.equals(idValue)) {
                            String customerType = row.getProperty("customer_type");
                            LogUtil.info(CLASS_NAME,
                                    String.format("Found company customer by registrationNumber: %s (Type: %s)",
                                            row.getId(), customerType));
                            return row.getId();
                        }
                    }

                    // Check personalId for individuals
                    String personalId = row.getProperty("personalId");
                    if (personalId != null) {
                        personalId = personalId.trim();
                        if (personalId.equals(idValue)) {
                            String customerType = row.getProperty("customer_type");
                            LogUtil.info(CLASS_NAME,
                                    String.format("Found individual customer by personalId: %s (Type: %s)",
                                            row.getId(), customerType));
                            return row.getId();
                        }
                    }

                    // Also check tax_id as a fallback
                    String taxId = row.getProperty("tax_id");
                    if (taxId != null) {
                        taxId = taxId.trim();
                        if (taxId.equals(idValue)) {
                            LogUtil.info(CLASS_NAME,
                                    "Found customer by tax_id: " + row.getId());
                            return row.getId();
                        }
                    }
                }
                
                LogUtil.warn(CLASS_NAME, "No customer found with registrationNumber, personalId, or tax_id = " + idValue);
            }
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error finding customer by registration/personal ID: " + idValue);
        }

        return null;
    }

    /**
     * Method 2: Identify customer by account number
     */
    private String identifyByAccountNumber(DataContext context, FormDataDao formDataDao) {
        String accountNumber = null;

        if ("bank".equals(context.getSourceType())) {
            // For bank transactions, use the account number from transaction
            FormRow trxRow = context.getTransactionRow();
            if (trxRow != null) {
                accountNumber = trxRow.getProperty("account_number");
            }
        }

        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            return null;
        }

        accountNumber = accountNumber.trim();
        LogUtil.info(CLASS_NAME, "Looking up customer by account number: " + accountNumber);

        try {
            // Search in customer_account table for account number
            FormRowSet accountRows = formDataDao.find(
                    null,
                    "customer_account",  // Customer account mapping table
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            if (accountRows != null && !accountRows.isEmpty()) {
                for (FormRow row : accountRows) {
                    String accNumber = row.getProperty("account_number");
                    String accStatus = row.getProperty("status");

                    if (accountNumber.equals(accNumber) && "active".equalsIgnoreCase(accStatus)) {
                        String customerId = row.getProperty("customer_id");
                        if (customerId != null && !customerId.trim().isEmpty()) {
                            LogUtil.info(CLASS_NAME,
                                    "Found customer by account number: " + customerId);
                            return customerId;
                        }
                    }
                }
            }

            // Alternative: Check in customer master for account numbers
            FormRowSet customerRows = formDataDao.find(
                    null,
                    DomainConstants.TABLE_CUSTOMER_MASTER,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            if (customerRows != null && !customerRows.isEmpty()) {
                for (FormRow row : customerRows) {
                    // Check if customer has this account number in any field
                    String bankAccount = row.getProperty("bank_account_number");
                    String primaryAccount = row.getProperty("primary_account");

                    if (accountNumber.equals(bankAccount) || accountNumber.equals(primaryAccount)) {
                        LogUtil.info(CLASS_NAME,
                                "Found customer by account in master: " + row.getId());
                        return row.getId();
                    }
                }
            }

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error searching by account number: " + accountNumber);
        }

        return null;
    }

    /**
     * Method 3: Identify customer by registration number extracted from reference/description
     * This is different from Method 1 - here we're extracting registration numbers from text fields
     */
    private String identifyByRegistrationNumber(DataContext context, FormDataDao formDataDao) {
        // Extract registration number from transaction reference or description
        String reference = context.getReference();
        String description = context.getDescription();

        String registrationNumber = extractRegistrationNumber(reference, description);

        if (registrationNumber == null || registrationNumber.trim().isEmpty()) {
            return null;
        }

        LogUtil.info(CLASS_NAME,
                "Looking up customer by extracted registration number: " + registrationNumber);

        // Use the same search method as in Method 1
        return findCustomerByRegistrationOrPersonalId(registrationNumber, formDataDao);
    }

    /**
     * Method 4: Identify customer by name pattern matching
     */
    private String identifyByNamePattern(DataContext context, FormDataDao formDataDao) {
        String searchName = null;

        if ("bank".equals(context.getSourceType())) {
            // Use other side name for pattern matching
            searchName = context.getOtherSideName();
        } else if ("secu".equals(context.getSourceType())) {
            // Extract name from description
            searchName = extractNameFromDescription(context.getDescription());
        }

        if (searchName == null || searchName.trim().isEmpty()) {
            return null;
        }

        searchName = searchName.trim().toUpperCase();
        LogUtil.info(CLASS_NAME, "Attempting name pattern match: " + searchName);

        try {
            FormRowSet customerRows = formDataDao.find(
                    null,
                    DomainConstants.TABLE_CUSTOMER_MASTER,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            if (customerRows != null && !customerRows.isEmpty()) {
                // Try exact match first
                for (FormRow row : customerRows) {
                    String customerName = row.getProperty("name");
                    String shortName = row.getProperty("short_name");

                    if (customerName != null &&
                            customerName.trim().toUpperCase().equals(searchName)) {
                        LogUtil.info(CLASS_NAME,
                                "Found customer by exact name match: " + row.getId());
                        return row.getId();
                    }

                    if (shortName != null &&
                            shortName.trim().toUpperCase().equals(searchName)) {
                        LogUtil.info(CLASS_NAME,
                                "Found customer by short name match: " + row.getId());
                        return row.getId();
                    }
                }

                // Try partial match (contains)
                for (FormRow row : customerRows) {
                    String customerName = row.getProperty("name");

                    if (customerName != null) {
                        String upperCustomerName = customerName.trim().toUpperCase();

                        // Check if search name contains customer name or vice versa
                        if (upperCustomerName.contains(searchName) ||
                                searchName.contains(upperCustomerName)) {

                            // Additional validation for partial matches
                            if (isReasonableMatch(searchName, upperCustomerName)) {
                                LogUtil.info(CLASS_NAME,
                                        "Found customer by partial name match: " + row.getId());
                                return row.getId();
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error in name pattern matching: " + searchName);
        }

        return null;
    }

    /**
     * Check if customer exists in master data
     */
    private boolean customerExists(String customerId, FormDataDao formDataDao) {
        try {
            FormRow customerRow = loadFormRow(formDataDao,
                    DomainConstants.TABLE_CUSTOMER_MASTER, customerId);
            return customerRow != null;
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error checking customer existence: " + customerId);
        }
        return false;
    }

    /**
     * Extract registration number from reference or description
     */
    private String extractRegistrationNumber(String reference, String description) {
        if (reference != null && !reference.trim().isEmpty()) {
            // Look for patterns like REG:12345 or REG-12345
            String[] patterns = {"REG:", "REG-", "REGNUM:", "REGISTRATION:"};
            for (String pattern : patterns) {
                int index = reference.toUpperCase().indexOf(pattern);
                if (index >= 0) {
                    String regNum = reference.substring(index + pattern.length()).trim();
                    // Extract until next space or end
                    int spaceIndex = regNum.indexOf(' ');
                    if (spaceIndex > 0) {
                        regNum = regNum.substring(0, spaceIndex);
                    }
                    return regNum;
                }
            }
        }

        // Try the same with description
        if (description != null && !description.trim().isEmpty()) {
            String[] patterns = {"REG:", "REG-", "REGNUM:", "REGISTRATION:"};
            for (String pattern : patterns) {
                int index = description.toUpperCase().indexOf(pattern);
                if (index >= 0) {
                    String regNum = description.substring(index + pattern.length()).trim();
                    int spaceIndex = regNum.indexOf(' ');
                    if (spaceIndex > 0) {
                        regNum = regNum.substring(0, spaceIndex);
                    }
                    return regNum;
                }
            }
        }

        return null;
    }

    /**
     * Extract potential customer name from description
     */
    private String extractNameFromDescription(String description) {
        if (description == null || description.trim().isEmpty()) {
            return null;
        }

        // Common patterns in descriptions
        // "FOR: CUSTOMER NAME" or "CLIENT: CUSTOMER NAME"
        String[] patterns = {"FOR:", "CLIENT:", "CUSTOMER:", "ACCOUNT:"};

        for (String pattern : patterns) {
            int index = description.toUpperCase().indexOf(pattern);
            if (index >= 0) {
                String name = description.substring(index + pattern.length()).trim();
                // Extract until next delimiter
                int delimIndex = name.indexOf(',');
                if (delimIndex > 0) {
                    name = name.substring(0, delimIndex);
                }
                delimIndex = name.indexOf(';');
                if (delimIndex > 0) {
                    name = name.substring(0, delimIndex);
                }
                return name.trim();
            }
        }

        return null;
    }

    /**
     * Check if a partial name match is reasonable
     */
    private boolean isReasonableMatch(String searchName, String customerName) {
        // Avoid false positives on very short strings
        if (searchName.length() < 5 || customerName.length() < 5) {
            return false;
        }

        // Calculate similarity ratio
        int matchLength = 0;
        if (searchName.contains(customerName)) {
            matchLength = customerName.length();
        } else if (customerName.contains(searchName)) {
            matchLength = searchName.length();
        }

        // Require at least 70% match
        double ratio = (double) matchLength / Math.max(searchName.length(), customerName.length());
        return ratio >= 0.7;
    }

    /**
     * Update transaction context with customer information
     */
    private void updateContextWithCustomer(DataContext context, String customerId,
                                           String customerName, String customerCode,
                                           int confidenceLevel, String identificationMethod,
                                           FormDataDao formDataDao) {
        // Get or create additional data map
        Map<String, Object> additionalData = context.getAdditionalData();
        if (additionalData == null) {
            additionalData = new HashMap<>();
            context.setAdditionalData(additionalData);
        }

        // Store customer information for use in subsequent steps
        additionalData.put("customer_id", customerId);
        additionalData.put("customer_name", customerName);
        additionalData.put("customer_code", customerCode);
        additionalData.put("customer_confidence", confidenceLevel);
        additionalData.put("customer_identification_method", identificationMethod);

        // Get additional customer details if not UNKNOWN
        if (!"UNKNOWN".equals(customerId)) {
            try {
                FormRow customerRow = loadFormRow(formDataDao,
                        DomainConstants.TABLE_CUSTOMER_MASTER, customerId);
                if (customerRow != null) {
                    // Store useful customer attributes
                    additionalData.put("customer_type", customerRow.getProperty("customer_type"));
                    additionalData.put("customer_currency", customerRow.getProperty("base_currency"));
                    additionalData.put("customer_risk_level", customerRow.getProperty("risk_level"));
                }
            } catch (Exception e) {
                LogUtil.error(CLASS_NAME, e,
                        "Error loading additional customer details: " + customerId);
            }
        }

        LogUtil.info(CLASS_NAME,
                String.format("Context updated with customer: ID=%s, Name=%s, Code=%s, Confidence=%d%%",
                        customerId, customerName, customerCode, confidenceLevel));
    }

    /**
     * Create exception record for customer identification issues
     */
    private void createCustomerException(DataContext context, FormDataDao formDataDao,
                                         String exceptionType, String exceptionDetails,
                                         String priority) {
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

            // Set priority
            exceptionRow.setProperty("priority", priority);

            // Set status
            exceptionRow.setProperty("status", FrameworkConstants.STATUS_PENDING);

            // Additional context for resolution
            if ("bank".equals(context.getSourceType())) {
                exceptionRow.setProperty("other_side_name", context.getOtherSideName());
                exceptionRow.setProperty("payment_description", context.getPaymentDescription());
                exceptionRow.setProperty("reference_number", context.getReferenceNumber());
            } else if ("secu".equals(context.getSourceType())) {
                exceptionRow.setProperty("ticker", context.getTicker());
                exceptionRow.setProperty("description", context.getDescription());
                exceptionRow.setProperty("reference", context.getReference());
            }

            // Set assignment based on priority
            if ("high".equals(priority) || "critical".equals(priority)) {
                exceptionRow.setProperty("assigned_to", "supervisor");
                exceptionRow.setProperty("due_date", calculateDueDate(1)); // Due in 1 day
            } else if ("medium".equals(priority)) {
                exceptionRow.setProperty("assigned_to", "operations");
                exceptionRow.setProperty("due_date", calculateDueDate(3)); // Due in 3 days
            } else {
                exceptionRow.setProperty("assigned_to", "operations");
                exceptionRow.setProperty("due_date", calculateDueDate(7)); // Due in 7 days
            }

            // Save exception
            FormRowSet rowSet = new FormRowSet();
            rowSet.add(exceptionRow);
            formDataDao.saveOrUpdate(null, DomainConstants.TABLE_EXCEPTION_QUEUE, rowSet);

            LogUtil.info(CLASS_NAME,
                    String.format("Created customer exception for transaction %s: Type=%s, Priority=%s",
                            context.getTransactionId(), exceptionType, priority));

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error creating customer exception for transaction: " +
                            context.getTransactionId());
        }
    }

    /**
     * Calculate due date based on number of days
     */
    private String calculateDueDate(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, days);
        return new java.text.SimpleDateFormat("yyyy-MM-dd").format(cal.getTime());
    }
}