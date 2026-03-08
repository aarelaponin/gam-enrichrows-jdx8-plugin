package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;
import com.fiscaladmin.gam.enrichrows.framework.AbstractDataStep;
import com.fiscaladmin.gam.enrichrows.framework.StepResult;
import com.fiscaladmin.gam.enrichrows.framework.DataContext;
import com.fiscaladmin.gam.framework.status.EntityType;
import com.fiscaladmin.gam.framework.status.Status;
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
    private static final int CONFIDENCE_CUSTOMER_ACCOUNT = 93;
    private static final int CONFIDENCE_REGISTRATION_NUMBER = 90;
    private static final int CONFIDENCE_FUND_FALLBACK = 80;
    private static final int CONFIDENCE_NAME_MATCH = 70;
    private static final int CONFIDENCE_UNKNOWN = 0;
    private static final int DEFAULT_CONFIDENCE_THRESHOLD_HIGH = 80;

    // CHANGES-08: Fund fallback cache — resolved once per enrichment run
    private String cachedFundCustomerId = null;
    private boolean fundCustomerLookedUp = false;

    @Override
    public String getStepName() {
        return "Customer Identification";
    }

    /**
     * Get the high confidence threshold from properties, falling back to default.
     */
    private int getConfidenceThresholdHigh() {
        Object value = getProperty("confidenceThresholdHigh", DEFAULT_CONFIDENCE_THRESHOLD_HIGH);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return DEFAULT_CONFIDENCE_THRESHOLD_HIGH;
        }
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

            // Method 3: Customer account lookup (other_side_account → customer_account → customerId)
            if (customerId == null) {
                customerId = identifyByCustomerAccount(context, formDataDao);
                if (customerId != null) {
                    confidenceLevel = CONFIDENCE_CUSTOMER_ACCOUNT;
                    identificationMethod = "CUSTOMER_ACCOUNT";
                    LogUtil.info(CLASS_NAME, "Customer identified by customer account: " + customerId);
                }
            }

            // Method 4: Registration number extraction from reference/description
            if (customerId == null) {
                customerId = identifyByRegistrationNumber(context, formDataDao);
                if (customerId != null) {
                    confidenceLevel = CONFIDENCE_REGISTRATION_NUMBER;
                    identificationMethod = "REGISTRATION_NUMBER_EXTRACTED";
                    LogUtil.info(CLASS_NAME,
                            "Customer identified by extracted registration number: " + customerId);
                }
            }

            // Method 5: Name pattern matching (lower confidence)
            if (customerId == null) {
                customerId = identifyByNamePattern(context, formDataDao);
                if (customerId != null) {
                    confidenceLevel = CONFIDENCE_NAME_MATCH;
                    identificationMethod = "NAME_PATTERN";
                    LogUtil.info(CLASS_NAME, "Customer identified by name pattern: " + customerId);
                }
            }

            // Method 6 (CHANGES-08): Fund fallback — no counterparty means fund-level transaction
            if (customerId == null) {
                customerId = identifyAsFundTransaction(context, formDataDao);
                if (customerId != null) {
                    confidenceLevel = CONFIDENCE_FUND_FALLBACK;
                    identificationMethod = "FUND_FALLBACK";
                    LogUtil.info(CLASS_NAME, "No counterparty — assigned to fund customer: " + customerId);
                }
            }

            // Get customer details if identified
            if (customerId != null) {
                FormRow customerRow = loadFormRow(formDataDao, DomainConstants.TABLE_CUSTOMER_MASTER, customerId);
                if (customerRow != null) {
                    customerName = customerRow.getProperty("name");
                    customerCode = customerRow.getProperty("customer_code");

                    // §4.0b: Verify customer KYC is completed
                    String kycStatus = customerRow.getProperty("kycStatus");
                    if (!"completed".equalsIgnoreCase(kycStatus)) {
                        LogUtil.warn(CLASS_NAME, "Customer " + customerId + " KYC not completed: " + kycStatus);
                        createCustomerException(context, formDataDao,
                                DomainConstants.EXCEPTION_INACTIVE_CUSTOMER,
                                String.format("Customer %s KYC status: %s (expected: completed)", customerId, kycStatus),
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
                if (confidenceLevel < getConfidenceThresholdHigh()) {
                    createCustomerException(context, formDataDao,
                            DomainConstants.EXCEPTION_LOW_CONFIDENCE,
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
                    DomainConstants.EXCEPTION_CUSTOMER_IDENTIFICATION_ERROR,
                    "Error during customer identification: " + e.getMessage(),
                    "high");

            return new StepResult(false,
                    "Customer identification error: " + e.getMessage());
        }
    }

    @Override
    public boolean shouldExecute(DataContext context) {
        // §9b: Skip if customer already resolved (UNKNOWN = re-evaluate)
        if (isFieldResolved(context, "customer_id", FrameworkConstants.ENTITY_UNKNOWN)) {
            return false;
        }
        // Bank only: secu transactions have no customer data (investment bank acts on behalf of customers).
        // Customer-to-portfolio allocation for secu is a manual operations process.
        if (!"bank".equals(context.getSourceType())) {
            return false;
        }
        // §4.0a: Securities-related bank transactions skip customer identification
        // (customer will be resolved during cross-statement pairing with secu side)
        if (isSecuritiesRelatedBankTransaction(context)) {
            return false;
        }
        return context.getErrorMessage() == null || context.getErrorMessage().isEmpty();
    }

    /**
     * §4.0a: Check if a bank transaction is securities-related based on payment description.
     * These transactions skip customer identification because the customer will be
     * resolved during cross-statement pairing with the securities side.
     */
    private boolean isSecuritiesRelatedBankTransaction(DataContext context) {
        String description = context.getPaymentDescription();
        if (description == null) return false;
        String lower = description.toLowerCase();
        return lower.startsWith("securities buy")
                || lower.startsWith("securities sell")
                || lower.startsWith("securities commission")
                || lower.startsWith("dividends")
                || lower.startsWith("income tax withheld");
    }

    /**
     * Method 1: Identify customer by direct ID field
     * The customer_id field from bank CSV (Isikukood või registrikood) always contains
     * a registrationNumber (8-digit, for companies) or personalId (11-digit, for individuals).
     * It never contains an internal customer ID format.
     */
    private String identifyByDirectId(DataContext context, FormDataDao formDataDao) {
        String customerIdField = context.getCustomerId();
        if (customerIdField == null || customerIdField.trim().isEmpty()) {
            LogUtil.info(CLASS_NAME, "No customer_id field value for transaction: " + context.getTransactionId());
            return null;
        }
        customerIdField = customerIdField.trim();
        LogUtil.info(CLASS_NAME, "Transaction " + context.getTransactionId() + " - Checking customer ID field: " + customerIdField);

        // Bank CSV "Isikukood või registrikood" is always registrationNumber or personalId
        return findCustomerByRegistrationOrPersonalId(customerIdField, formDataDao);
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
     * Method 2: Identify customer by account number (bank transactions only)
     *
     * Resolution chain:
     * 1. Get account_number from bank transaction (IBAN from CSV "Kliendi konto")
     * 2. Find matching account in customer_account table by accountNumber
     * 3. Follow business key chain: corporateCustomerId -> customer.registrationNumber
     *    or individualCustomerId -> customer.personalId
     *
     * Note: secu transactions have no account_number — customer allocation for
     * securities is a manual process (investment bank acts on behalf of customers).
     */
    private String identifyByAccountNumber(DataContext context, FormDataDao formDataDao) {
        if (!"bank".equals(context.getSourceType())) {
            return null;
        }

        FormRow trxRow = context.getTransactionRow();
        if (trxRow == null) {
            return null;
        }

        String accountNumber = trxRow.getProperty("account_number");
        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            return null;
        }

        accountNumber = accountNumber.trim();
        LogUtil.info(CLASS_NAME, "Looking up customer by account number: " + accountNumber);

        try {
            FormRowSet accountRows = formDataDao.find(
                    null,
                    DomainConstants.TABLE_CUSTOMER_ACCOUNT,
                    null, null, null, null, null, null
            );

            if (accountRows != null && !accountRows.isEmpty()) {
                for (FormRow row : accountRows) {
                    String accNumber = row.getProperty("account_number");
                    String accStatus = row.getProperty("status");

                    if (accountNumber.equals(accNumber) && "active".equalsIgnoreCase(accStatus)) {
                        LogUtil.info(CLASS_NAME, "Found matching account: " + accNumber);

                        // Resolve customer via business key chain
                        String customerId = resolveCustomerFromAccount(row, formDataDao);
                        if (customerId != null) {
                            return customerId;
                        }

                        LogUtil.warn(CLASS_NAME,
                                "Account found but no customer reference (corporateCustomerId/individualCustomerId) for: " + accNumber);
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
     * Method 3: Identify customer via customer_account table.
     * Maps other_side_account (counterparty IBAN) → customer_account.accountNumber → customerId.
     */
    private String identifyByCustomerAccount(DataContext context, FormDataDao formDataDao) {
        String otherSideAccount = context.getOtherSideAccount();
        if (otherSideAccount == null || otherSideAccount.trim().isEmpty()) {
            return null;
        }

        otherSideAccount = otherSideAccount.trim();
        LogUtil.info(CLASS_NAME, "Looking up customer by customer_account for IBAN: " + otherSideAccount);

        try {
            String condition = "WHERE c_accountNumber = ? AND c_status = 'Active'";
            FormRowSet rows = formDataDao.find(null,
                    DomainConstants.TABLE_CUSTOMER_ACCOUNT,
                    condition,
                    new String[] { otherSideAccount },
                    null, false, 0, 1);

            if (rows != null && !rows.isEmpty()) {
                String customerId = rows.get(0).getProperty("customerId");
                if (customerId != null && !customerId.trim().isEmpty()) {
                    LogUtil.info(CLASS_NAME,
                            "Found customer via customer_account: " + customerId + " for IBAN: " + otherSideAccount);
                    return customerId;
                }
            }
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error searching customer_account by IBAN: " + otherSideAccount);
        }

        return null;
    }

    /**
     * CHANGES-08: Method 6 — Fund fallback.
     *
     * When a transaction has no counterparty (no other_side_account), the transaction
     * is a fund-level operational event: bank fee, account interest, FX conversion,
     * bond coupon via broker, VAT, etc. The customer is the fund itself.
     *
     * The fund is identified by the is_fund flag on the customer form, which is
     * constrained by DuplicateValueValidator to exactly one customer entity.
     * The result is cached for the duration of the enrichment run.
     *
     * @return fund customerId, or null if counterparty is present or no fund entity exists
     */
    private String identifyAsFundTransaction(DataContext context, FormDataDao formDataDao) {
        // Only applies when there is NO counterparty
        String otherSideAccount = context.getOtherSideAccount();
        if (otherSideAccount != null && !otherSideAccount.trim().isEmpty()) {
            return null;  // Has counterparty — not a fund-level transaction
        }

        // Return cached result if already looked up in this run
        if (fundCustomerLookedUp) {
            return cachedFundCustomerId;
        }

        fundCustomerLookedUp = true;

        try {
            // Full table scan + Java filter (avoids SQL column dependency on c_is_fund
            // which would poison the JTA transaction if the column doesn't exist yet)
            FormRowSet rows = formDataDao.find(null,
                    DomainConstants.TABLE_CUSTOMER_MASTER,
                    null, null, null, null, null, null);

            if (rows != null) {
                for (FormRow row : rows) {
                    String isFund = row.getProperty("is_fund");
                    if ("yes".equalsIgnoreCase(isFund)) {
                        cachedFundCustomerId = row.getId();
                        LogUtil.info(CLASS_NAME,
                                "Fund customer resolved: " + cachedFundCustomerId
                                + " (is_fund=yes)");
                        return cachedFundCustomerId;
                    }
                }
            }

            LogUtil.warn(CLASS_NAME, "No fund customer found (is_fund=yes). "
                    + "Fund fallback will not resolve any transactions this run.");
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error querying fund customer (is_fund=yes)");
        }

        return null;
    }

    /**
     * Resolve customer from a customer_account row using business key chain:
     * corporateCustomerId -> customer.registrationNumber
     * individualCustomerId -> customer.personalId
     */
    private String resolveCustomerFromAccount(FormRow accountRow, FormDataDao formDataDao) {
        // Corporate accounts: corporateCustomerId = registrationNumber
        String corpId = accountRow.getProperty("corporateCustomerId");
        if (corpId != null && !corpId.trim().isEmpty()) {
            LogUtil.info(CLASS_NAME,
                    "Account has corporateCustomerId: " + corpId + ", searching by registrationNumber");
            String resolved = findCustomerByRegistrationOrPersonalId(corpId, formDataDao);
            if (resolved != null) {
                LogUtil.info(CLASS_NAME,
                        "Resolved customer via corporateCustomerId -> registrationNumber: " + resolved);
                return resolved;
            }
        }

        // Individual accounts: individualCustomerId = personalId
        String indivId = accountRow.getProperty("individualCustomerId");
        if (indivId != null && !indivId.trim().isEmpty()) {
            LogUtil.info(CLASS_NAME,
                    "Account has individualCustomerId: " + indivId + ", searching by personalId");
            String resolved = findCustomerByRegistrationOrPersonalId(indivId, formDataDao);
            if (resolved != null) {
                LogUtil.info(CLASS_NAME,
                        "Resolved customer via individualCustomerId -> personalId: " + resolved);
                return resolved;
            }
        }

        return null;
    }

    /**
     * Method 4: Identify customer by registration number extracted from reference/description
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
     * Method 5: Identify customer by name pattern matching
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
            exceptionRow.setProperty("status", Status.OPEN.getCode());

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

            if (statusManager != null) {
                try {
                    statusManager.transition(formDataDao, EntityType.EXCEPTION, exceptionId,
                            Status.OPEN, "rows-enrichment", exceptionDetails);
                } catch (Exception e) {
                    LogUtil.warn(CLASS_NAME, "Could not transition exception status: " + e.getMessage());
                }
            }

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