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
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Step 4: F14 Rule Mapping (Apply F14 Rules)
 *
 * Loads matching rules for the identified counterparty, ordered by priority.
 * Evaluates each rule against the transaction fields.
 * The first matching rule determines the internal transaction type.
 * If no rules match, marks as UNMATCHED and creates an exception.
 *
 * Rules are evaluated in this order:
 * 1. Counterparty-specific rules (by priority)
 * 2. SYSTEM rules (universal fallback rules)
 *
 * This step is critical for standardizing diverse external transaction codes
 * into consistent internal types for GL posting.
 */
public class F14RuleMappingStep extends AbstractDataStep {

    private static final String CLASS_NAME = F14RuleMappingStep.class.getName();

    // Table name for F14 matching rules
    private static final String TABLE_CP_TXN_MAPPING = "cp_txn_mapping";

    // Special counterparty ID for universal rules
    private static final String SYSTEM_COUNTERPARTY = FrameworkConstants.ENTITY_SYSTEM;
    
    // Tracking counters for summary
    private int totalProcessed = 0;
    private int successCount = 0;
    private int noRulesCount = 0;
    private int noMatchCount = 0;
    private int errorCount = 0;

    @Override
    public String getStepName() {
        return "F14 Rule Mapping";
    }

    @Override
    protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
        try {
            totalProcessed++;
            
            LogUtil.info(CLASS_NAME,
                    "Starting F14 rule mapping for transaction: " + context.getTransactionId() +
                            ", Type: " + context.getSourceType());

            // Get counterparty ID from context (set by Step 3)
            String counterpartyId = getCounterpartyIdFromContext(context);
            if (counterpartyId == null || counterpartyId.isEmpty()) {
                counterpartyId = FrameworkConstants.ENTITY_UNKNOWN;
            }

            LogUtil.info(CLASS_NAME,
                    "Using counterparty ID: " + counterpartyId + " for rule matching");

            // Load applicable rules
            List<FormRow> applicableRules = loadApplicableRules(
                    context.getSourceType(), counterpartyId, formDataDao);

            if (applicableRules.isEmpty()) {
                LogUtil.warn(CLASS_NAME,
                        "No F14 rules found for counterparty: " + counterpartyId +
                                " and source type: " + context.getSourceType());

                return handleNoRulesFound(context, formDataDao);
            }

            LogUtil.info(CLASS_NAME,
                    "Found " + applicableRules.size() + " applicable rules to evaluate");

            // Evaluate rules in order until a match is found
            String internalType = null;
            String matchedRuleId = null;
            String matchedRuleName = null;
            int rulesEvaluated = 0;

            for (FormRow rule : applicableRules) {
                rulesEvaluated++;
                String ruleId = rule.getId();
                String ruleName = rule.getProperty("mappingName");

                LogUtil.debug(CLASS_NAME,
                        "Evaluating rule " + rulesEvaluated + ": " + ruleName + " (ID: " + ruleId + ")");

                if (evaluateRule(rule, context)) {
                    internalType = rule.getProperty("internalType");
                    matchedRuleId = ruleId;
                    matchedRuleName = ruleName;

                    LogUtil.info(CLASS_NAME,
                            "Rule matched: " + ruleName + " -> Internal type: " + internalType);
                    break;
                }
            }

            // Handle the result
            if (internalType != null) {
                // Rule matched successfully
                updateContextWithInternalType(context, internalType, matchedRuleId,
                        matchedRuleName, rulesEvaluated);

                // Update processing status
                context.setProcessingStatus(DomainConstants.PROCESSING_STATUS_F14_MAPPED);
                context.addProcessedStep(DomainConstants.PROCESSING_STATUS_F14_MAPPED);

                // Create audit log
                createAuditLog(context, formDataDao,
                        DomainConstants.AUDIT_F14_MAPPED,
                        String.format("F14 rule matched: %s -> %s (Rules evaluated: %d)",
                                matchedRuleName, internalType, rulesEvaluated));

                LogUtil.info(CLASS_NAME,
                        String.format("F14 mapping successful for transaction %s: %s",
                                context.getTransactionId(), internalType));

                successCount++;
                logSummaryIfNeeded();
                
                return new StepResult(true,
                        String.format("Internal type determined: %s", internalType));

            } else {
                // No rules matched
                return handleNoMatchFound(context, formDataDao, rulesEvaluated);
            }

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Unexpected error in F14 rule mapping for transaction: " +
                            context.getTransactionId());

            createF14Exception(context, formDataDao,
                    "F14_MAPPING_ERROR",
                    "Error during F14 rule mapping: " + e.getMessage(),
                    "high");

            errorCount++;
            logSummaryIfNeeded();
            
            return new StepResult(false, "F14 rule mapping error: " + e.getMessage());
        }
    }

    @Override
    public boolean shouldExecute(DataContext context) {
        // Execute for all transactions that haven't failed yet
        return context.getErrorMessage() == null || context.getErrorMessage().isEmpty();
    }

    /**
     * Get counterparty ID from context (set by Step 3)
     */
    private String getCounterpartyIdFromContext(DataContext context) {
        LogUtil.info(CLASS_NAME, "=== DEBUG: RETRIEVING COUNTERPARTY FROM CONTEXT ===");
        LogUtil.info(CLASS_NAME, "Transaction ID: " + context.getTransactionId());
        
        Map<String, Object> additionalData = context.getAdditionalData();
        if (additionalData != null) {
            LogUtil.info(CLASS_NAME, "additionalData is NOT null");
            LogUtil.info(CLASS_NAME, "additionalData contents: " + additionalData.toString());
            
            Object cpId = additionalData.get("counterparty_id");
            if (cpId != null) {
                LogUtil.info(CLASS_NAME, "Found counterparty_id: '" + cpId.toString() + "'");
                LogUtil.info(CLASS_NAME, "====================================================");
                return cpId.toString();
            } else {
                LogUtil.info(CLASS_NAME, "counterparty_id is NULL in additionalData!");
            }
        } else {
            LogUtil.info(CLASS_NAME, "additionalData is NULL!");
        }
        LogUtil.info(CLASS_NAME, "Returning NULL counterparty_id");
        LogUtil.info(CLASS_NAME, "====================================================");
        return null;
    }

    /**
     * Load all applicable rules for the transaction
     * Returns rules sorted by: counterparty-specific first, then SYSTEM, then by priority
     */
    private List<FormRow> loadApplicableRules(String sourceType, String counterpartyId,
                                              FormDataDao formDataDao) {
        List<FormRow> applicableRules = new ArrayList<>();

        try {
            // Load all rules from the cp_txn_mapping table
            FormRowSet allRules = formDataDao.find(
                    null,
                    TABLE_CP_TXN_MAPPING,
                    null,  // Load all and filter in memory
                    null,
                    null,
                    null,
                    null,
                    null
            );

            LogUtil.info(CLASS_NAME, "=== DEBUG: F14 RULES LOADING ===");
            LogUtil.info(CLASS_NAME, "Looking for rules matching counterpartyId: '" + counterpartyId + "' and sourceType: '" + sourceType + "'");
            
            if (allRules != null && !allRules.isEmpty()) {
                LogUtil.info(CLASS_NAME, "Total rules in cp_txn_mapping table: " + allRules.size());
                // Filter for applicable rules
                for (FormRow rule : allRules) {
                    String ruleSourceType = rule.getProperty("sourceType");
                    String ruleCounterpartyId = rule.getProperty("counterpartyId");
                    String status = rule.getProperty("status");

                    LogUtil.info(CLASS_NAME, String.format(
                        "Checking rule: id=%s, sourceType='%s', counterpartyId='%s', status='%s'",
                        rule.getId(), ruleSourceType, ruleCounterpartyId, status));

                    // Check if rule is active
                    if (!FrameworkConstants.STATUS_ACTIVE_CAPITAL.equalsIgnoreCase(status) && !FrameworkConstants.STATUS_ACTIVE.equalsIgnoreCase(status)) {
                        LogUtil.info(CLASS_NAME, "  -> Skipped: not active");
                        continue;
                    }

                    // Check source type matches
                    if (!sourceType.equals(ruleSourceType)) {
                        LogUtil.info(CLASS_NAME, "  -> Skipped: source type mismatch");
                        continue;
                    }

                    // Check if rule applies to this counterparty
                    if (counterpartyId.equals(ruleCounterpartyId) ||
                            SYSTEM_COUNTERPARTY.equals(ruleCounterpartyId)) {
                        
                        LogUtil.info(CLASS_NAME, String.format(
                            "Rule %s matches counterparty: rule_cp='%s', current_cp='%s', source='%s'",
                            rule.getId(), ruleCounterpartyId, counterpartyId, sourceType));

                        // Check effective date if specified
                        if (isRuleEffective(rule)) {
                            applicableRules.add(rule);
                            LogUtil.info(CLASS_NAME, "Added rule to applicable list: " + 
                                rule.getProperty("mappingName") + " (priority: " + rule.getProperty("priority") + ")");
                        }
                    } else {
                        LogUtil.debug(CLASS_NAME, String.format(
                            "Rule %s skipped: rule_cp='%s' doesn't match current_cp='%s'",
                            rule.getId(), ruleCounterpartyId, counterpartyId));
                    }
                }
            }

            // Sort rules: counterparty-specific first, then SYSTEM, then by priority
            applicableRules.sort((r1, r2) -> {
                String cp1 = r1.getProperty("counterpartyId");
                String cp2 = r2.getProperty("counterpartyId");

                // Counterparty-specific rules come before SYSTEM rules
                if (!cp1.equals(cp2)) {
                    if (SYSTEM_COUNTERPARTY.equals(cp1)) return 1;
                    if (SYSTEM_COUNTERPARTY.equals(cp2)) return -1;
                }

                // Then sort by priority (lower number = higher priority)
                String priority1 = r1.getProperty("priority");
                String priority2 = r2.getProperty("priority");

                int p1 = priority1 != null ? Integer.parseInt(priority1) : 999;
                int p2 = priority2 != null ? Integer.parseInt(priority2) : 999;

                return Integer.compare(p1, p2);
            });

            LogUtil.info(CLASS_NAME,
                    "Loaded " + applicableRules.size() + " applicable rules for counterparty: " +
                            counterpartyId + " and source type: " + sourceType);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error loading F14 rules for counterparty: " + counterpartyId);
        }

        return applicableRules;
    }

    /**
     * Check if rule is effective based on date
     */
    private boolean isRuleEffective(FormRow rule) {
        String effectiveDateStr = rule.getProperty("effectiveDate");
        if (effectiveDateStr == null || effectiveDateStr.isEmpty()) {
            return true; // No effective date means always effective
        }

        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            Date effectiveDate = sdf.parse(effectiveDateStr);
            Date today = new Date();

            return !today.before(effectiveDate);
        } catch (Exception e) {
            LogUtil.warn(CLASS_NAME,
                    "Could not parse effective date: " + effectiveDateStr + " for rule: " + rule.getId());
            return true; // If we can't parse, assume effective
        }
    }

    /**
     * Evaluate a single rule against the transaction
     */
    private boolean evaluateRule(FormRow rule, DataContext context) {
        try {
            String matchingField = rule.getProperty("matchingField");
            String matchOperator = rule.getProperty("matchOperator");
            String matchValue = rule.getProperty("matchValue");
            String caseSensitive = rule.getProperty("caseSensitive");
            
            LogUtil.info(CLASS_NAME, String.format(
                "Evaluating rule: field='%s', operator='%s', value='%s', caseSensitive='%s'",
                matchingField, matchOperator, matchValue, caseSensitive));

            // Handle complex rules
            if ("combined".equals(matchingField)) {
                return evaluateComplexRule(rule, context);
            }

            // Get the field value from transaction
            String fieldValue = getFieldValue(matchingField, context);
            LogUtil.info(CLASS_NAME, String.format(
                "Field value from transaction: field='%s', value='%s'",
                matchingField, fieldValue));
                
            if (fieldValue == null) {
                LogUtil.info(CLASS_NAME, "Field value is null, rule does not match");
                return false;
            }

            // Apply case sensitivity
            boolean isCaseSensitive = "true".equalsIgnoreCase(caseSensitive) ||
                    "Y".equalsIgnoreCase(caseSensitive);
            if (!isCaseSensitive) {
                fieldValue = fieldValue.toUpperCase();
                matchValue = matchValue != null ? matchValue.toUpperCase() : "";
                LogUtil.info(CLASS_NAME, String.format(
                    "After case conversion: fieldValue='%s', matchValue='%s'",
                    fieldValue, matchValue));
            }

            // Evaluate based on operator
            boolean matches = evaluateOperator(fieldValue, matchOperator, matchValue);
            LogUtil.info(CLASS_NAME, String.format(
                "Operator evaluation: operator='%s', result=%s",
                matchOperator, matches));

            // Check arithmetic conditions if field matching passed
            if (matches) {
                String arithmeticCondition = rule.getProperty("arithmeticCondition");
                if (arithmeticCondition != null && !arithmeticCondition.trim().isEmpty()) {
                    matches = evaluateArithmeticCondition(arithmeticCondition, context);
                    LogUtil.info(CLASS_NAME, String.format(
                        "Arithmetic condition: '%s', result=%s",
                        arithmeticCondition, matches));
                }
            }

            return matches;

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error evaluating rule: " + rule.getId());
            return false;
        }
    }

    /**
     * Get field value from transaction context
     */
    private String getFieldValue(String fieldName, DataContext context) {
        if (fieldName == null) {
            return null;
        }

        // Handle bank transaction fields
        if (DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType())) {
            switch (fieldName) {
                case "payment_description":
                    return context.getPaymentDescription();
                case "other_side_account":
                    FormRow trxRow = context.getTransactionRow();
                    return trxRow != null ? trxRow.getProperty("other_side_account") : null;
                case "other_side_name":
                    return context.getOtherSideName();
                case "d_c":
                    return context.getDebitCredit();
                case "reference_number":
                    return context.getReferenceNumber();
                case "other_side_bic":
                    return context.getOtherSideBic();
                case "amount":
                    return context.getAmount();
                default:
                    // Try to get from transaction row
                    FormRow row = context.getTransactionRow();
                    return row != null ? row.getProperty(fieldName) : null;
            }
        }

        // Handle securities transaction fields
        if (DomainConstants.SOURCE_TYPE_SECU.equals(context.getSourceType())) {
            switch (fieldName) {
                case "type":
                    return context.getType();
                case "ticker":
                    return context.getTicker();
                case "description":
                    return context.getDescription();
                case "amount":
                    return context.getAmount();
                case "reference":
                    return context.getReference();
                default:
                    // Try to get from transaction row
                    FormRow row = context.getTransactionRow();
                    return row != null ? row.getProperty(fieldName) : null;
            }
        }

        return null;
    }

    /**
     * Evaluate match operator
     */
    private boolean evaluateOperator(String fieldValue, String operator, String matchValue) {
        if (fieldValue == null || operator == null || matchValue == null) {
            return false;
        }

        switch (operator.toLowerCase()) {
            case "equals":
                return fieldValue.equals(matchValue);

            case "contains":
                return fieldValue.contains(matchValue);

            case "startswith":
            case "starts_with":
                return fieldValue.startsWith(matchValue);

            case "endswith":
            case "ends_with":
                return fieldValue.endsWith(matchValue);

            case "regex":
                try {
                    Pattern pattern = Pattern.compile(matchValue);
                    Matcher matcher = pattern.matcher(fieldValue);
                    return matcher.find();
                } catch (Exception e) {
                    LogUtil.error(CLASS_NAME, e, "Invalid regex pattern: " + matchValue);
                    return false;
                }

            case "in":
                // Match value is comma-separated list
                String[] values = matchValue.split(",");
                for (String value : values) {
                    if (fieldValue.equals(value.trim())) {
                        return true;
                    }
                }
                return false;

            default:
                LogUtil.warn(CLASS_NAME, "Unknown operator: " + operator);
                return false;
        }
    }

    /**
     * Evaluate complex rule with multiple conditions
     */
    private boolean evaluateComplexRule(FormRow rule, DataContext context) {
        String complexExpression = rule.getProperty("complexRuleExpression");
        if (complexExpression == null || complexExpression.trim().isEmpty()) {
            return false;
        }

        try {
            // Parse and evaluate complex expression
            // Example: "d_c = 'C' AND payment_description CONTAINS 'wire'"

            // This is a simplified implementation
            // In production, you might want to use a proper expression parser

            String expression = complexExpression.toUpperCase();

            // Handle AND conditions
            if (expression.contains(" AND ")) {
                String[] conditions = expression.split(" AND ");
                for (String condition : conditions) {
                    if (!evaluateSingleCondition(condition.trim(), context)) {
                        return false;
                    }
                }
                return true;
            }

            // Handle OR conditions
            if (expression.contains(" OR ")) {
                String[] conditions = expression.split(" OR ");
                for (String condition : conditions) {
                    if (evaluateSingleCondition(condition.trim(), context)) {
                        return true;
                    }
                }
                return false;
            }

            // Single condition
            return evaluateSingleCondition(expression, context);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error evaluating complex rule: " + complexExpression);
            return false;
        }
    }

    /**
     * Evaluate a single condition from complex expression
     */
    private boolean evaluateSingleCondition(String condition, DataContext context) {
        // Parse condition like "field = 'value'" or "field CONTAINS 'value'"

        if (condition.contains(" CONTAINS ")) {
            String[] parts = condition.split(" CONTAINS ");
            if (parts.length == 2) {
                String fieldName = parts[0].trim().toLowerCase();
                String value = parts[1].trim().replace("'", "");
                String fieldValue = getFieldValue(fieldName, context);
                return fieldValue != null && fieldValue.toUpperCase().contains(value);
            }
        }

        if (condition.contains(" = ")) {
            String[] parts = condition.split(" = ");
            if (parts.length == 2) {
                String fieldName = parts[0].trim().toLowerCase();
                String value = parts[1].trim().replace("'", "");
                String fieldValue = getFieldValue(fieldName, context);
                return fieldValue != null && fieldValue.toUpperCase().equals(value);
            }
        }

        return false;
    }

    /**
     * Evaluate arithmetic condition
     */
    private boolean evaluateArithmeticCondition(String condition, DataContext context) {
        try {
            // Example: "payment_amount > 100"
            // This is a simplified implementation

            double amount = parseAmount(context.getAmount());

            if (condition.contains(">")) {
                String[] parts = condition.split(">");
                if (parts.length == 2) {
                    double threshold = Double.parseDouble(parts[1].trim());
                    return amount > threshold;
                }
            }

            if (condition.contains("<")) {
                String[] parts = condition.split("<");
                if (parts.length == 2) {
                    double threshold = Double.parseDouble(parts[1].trim());
                    return amount < threshold;
                }
            }

            if (condition.contains(">=")) {
                String[] parts = condition.split(">=");
                if (parts.length == 2) {
                    double threshold = Double.parseDouble(parts[1].trim());
                    return amount >= threshold;
                }
            }

            if (condition.contains("<=")) {
                String[] parts = condition.split("<=");
                if (parts.length == 2) {
                    double threshold = Double.parseDouble(parts[1].trim());
                    return amount <= threshold;
                }
            }

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error evaluating arithmetic condition: " + condition);
        }

        return true; // If we can't evaluate, don't fail the rule
    }

    /**
     * Update context with internal type and mapping details
     */
    private void updateContextWithInternalType(DataContext context, String internalType,
                                               String ruleId, String ruleName, int rulesEvaluated) {
        // Get or create additional data map
        Map<String, Object> additionalData = context.getAdditionalData();
        if (additionalData == null) {
            additionalData = new HashMap<>();
            context.setAdditionalData(additionalData);
        }

        // Store F14 mapping information
        additionalData.put("internal_type", internalType);
        additionalData.put("f14_rule_id", ruleId);
        additionalData.put("f14_rule_name", ruleName);
        additionalData.put("f14_rules_evaluated", rulesEvaluated);

        // DO NOT update the source transaction record - all findings will be saved 
        // to a new enrichment record in Step 8
        // Just store in context for downstream processing

        LogUtil.info(CLASS_NAME,
                String.format("Context updated with internal type: %s (Rule: %s)",
                        internalType, ruleName));
    }

    /**
     * Handle case when no rules are found
     */
    private StepResult handleNoRulesFound(DataContext context, FormDataDao formDataDao) {
        LogUtil.warn(CLASS_NAME,
                "No F14 rules configured for transaction: " + context.getTransactionId());

        // Set status FIRST before creating any logs or exceptions
        context.setProcessingStatus("f14_no_rules");
        
        String counterpartyId = getCounterpartyIdFromContext(context);
        
        createF14Exception(context, formDataDao,
                "NO_F14_RULES",
                String.format("No F14 rules configured for counterparty '%s' and transaction type '%s'",
                             counterpartyId != null ? counterpartyId : "UNKNOWN",
                             context.getSourceType()),
                "high");

        // Set UNMATCHED as internal type
        updateContextWithInternalType(context, FrameworkConstants.INTERNAL_TYPE_UNMATCHED, null, null, 0);

        noRulesCount++;
        logSummaryIfNeeded();
        
        return new StepResult(true,
                "No F14 rules found - marked as UNMATCHED, continuing processing");
    }

    /**
     * Handle case when no rules match
     */
    private StepResult handleNoMatchFound(DataContext context, FormDataDao formDataDao,
                                          int rulesEvaluated) {
        LogUtil.warn(CLASS_NAME,
                String.format("No F14 rule matched for transaction %s after evaluating %d rules",
                        context.getTransactionId(), rulesEvaluated));

        // Set status FIRST before creating any logs or exceptions
        context.setProcessingStatus("f14_no_match");
        
        // Create detailed exception message
        StringBuilder details = new StringBuilder();
        details.append("No matching F14 rule found. ");
        details.append("Rules evaluated: ").append(rulesEvaluated).append(". ");

        if (DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType())) {
            details.append("Description: ").append(context.getPaymentDescription()).append(", ");
            details.append("D/C: ").append(context.getDebitCredit()).append(", ");
            details.append("Other side: ").append(context.getOtherSideName());
        } else {
            details.append("Type: ").append(context.getType()).append(", ");
            details.append("Ticker: ").append(context.getTicker()).append(", ");
            details.append("Description: ").append(context.getDescription());
        }

        createF14Exception(context, formDataDao,
                "NO_RULE_MATCH",
                details.toString(),
                "medium");

        // Set UNMATCHED as internal type
        updateContextWithInternalType(context, FrameworkConstants.INTERNAL_TYPE_UNMATCHED, null, null, rulesEvaluated);

        noMatchCount++;
        logSummaryIfNeeded();
        
        return new StepResult(true,
                "No F14 rule matched - marked as UNMATCHED, continuing processing");
    }

    /**
     * Create exception record for F14 mapping issues
     */
    private void createF14Exception(DataContext context, FormDataDao formDataDao,
                                    String exceptionType, String exceptionDetails,
                                    String priority) {
        try {
            FormRow exceptionRow = new FormRow();

            // Generate unique ID for the exception
            String exceptionId = UUID.randomUUID().toString();
            exceptionRow.setId(exceptionId);

            // Set exception identifiers - with null checks
            setPropertySafe(exceptionRow, "transaction_id", context.getTransactionId());
            setPropertySafe(exceptionRow, "statement_id", context.getStatementId());
            setPropertySafe(exceptionRow, "source_type", context.getSourceType());

            // Set exception details
            setPropertySafe(exceptionRow, "exception_type", exceptionType);
            setPropertySafe(exceptionRow, "exception_details", exceptionDetails);
            setPropertySafe(exceptionRow, "exception_date", new Date().toString());

            // Set transaction details for reference
            setPropertySafe(exceptionRow, "amount", context.getAmount());
            setPropertySafe(exceptionRow, "currency", context.getCurrency());
            setPropertySafe(exceptionRow, "transaction_date", context.getTransactionDate());

            // Set priority
            setPropertySafe(exceptionRow, "priority", priority);

            // Set status
            setPropertySafe(exceptionRow, "status", FrameworkConstants.STATUS_PENDING);

            // Additional context for manual rule creation
            String counterpartyId = getCounterpartyIdFromContext(context);
            setPropertySafe(exceptionRow, "counterparty_id", counterpartyId != null ? counterpartyId : "UNKNOWN");

            if (DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType())) {
                setPropertySafe(exceptionRow, "payment_description", context.getPaymentDescription());
                setPropertySafe(exceptionRow, "d_c", context.getDebitCredit());
                setPropertySafe(exceptionRow, "other_side_name", context.getOtherSideName());
            } else {
                setPropertySafe(exceptionRow, "type", context.getType());
                setPropertySafe(exceptionRow, "ticker", context.getTicker());
                setPropertySafe(exceptionRow, "description", context.getDescription());
            }

            // Set assignment
            if ("high".equals(priority)) {
                setPropertySafe(exceptionRow, "assigned_to", "supervisor");
                setPropertySafe(exceptionRow, "due_date", calculateDueDate(1));
            } else {
                setPropertySafe(exceptionRow, "assigned_to", "operations");
                setPropertySafe(exceptionRow, "due_date", calculateDueDate(3));
            }

            // Save exception
            FormRowSet rowSet = new FormRowSet();
            rowSet.add(exceptionRow);
            formDataDao.saveOrUpdate(null, DomainConstants.TABLE_EXCEPTION_QUEUE, rowSet);

            LogUtil.info(CLASS_NAME,
                    String.format("Created F14 exception for transaction %s: Type=%s, Priority=%s",
                            context.getTransactionId(), exceptionType, priority));

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error creating F14 exception for transaction: " + context.getTransactionId());
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
    
    /**
     * Safely set property on FormRow, handling null values
     */
    private void setPropertySafe(FormRow row, String key, String value) {
        if (value != null) {
            row.setProperty(key, value);
        } else {
            row.setProperty(key, "");  // Set empty string instead of null
        }
    }
    
    /**
     * Log summary statistics periodically and at completion
     */
    private void logSummaryIfNeeded() {
        // Log summary every 50 transactions and when all are processed
        if (totalProcessed % 50 == 0 || isLastTransaction()) {
            int failureCount = noRulesCount + noMatchCount + errorCount;
            double successRate = totalProcessed > 0 ? 
                (successCount * 100.0 / totalProcessed) : 0;
            
            LogUtil.info(CLASS_NAME, "========================================");
            LogUtil.info(CLASS_NAME, "F14 MAPPING PROGRESS SUMMARY:");
            LogUtil.info(CLASS_NAME, "  Total processed: " + totalProcessed);
            LogUtil.info(CLASS_NAME, "  Successful mappings: " + successCount);
            LogUtil.info(CLASS_NAME, "  No rules found: " + noRulesCount);
            LogUtil.info(CLASS_NAME, "  No match found: " + noMatchCount);
            LogUtil.info(CLASS_NAME, "  Errors: " + errorCount);
            LogUtil.info(CLASS_NAME, "  Success rate: " + String.format("%.1f%%", successRate));
            LogUtil.info(CLASS_NAME, "========================================");
            
            // Log warning if success rate is below threshold
            if (successRate < 90.0 && totalProcessed >= 10) {
                LogUtil.warn(CLASS_NAME, 
                    "WARNING: F14 mapping success rate is below 90% (" + 
                    String.format("%.1f%%", successRate) + 
                    "). Check exception_queue for details:");
                LogUtil.warn(CLASS_NAME, 
                    "Query: SELECT * FROM app_fd_exception_queue WHERE c_exception_type IN " +
                    "('NO_F14_RULES', 'NO_RULE_MATCH', 'F14_MAPPING_ERROR') " +
                    "ORDER BY dateCreated DESC");
            }
        }
    }
    
    /**
     * Check if this is likely the last transaction (heuristic)
     */
    private boolean isLastTransaction() {
        // This is a simple heuristic - in a real implementation,
        // you might pass the total count from the pipeline
        return false;
    }
}