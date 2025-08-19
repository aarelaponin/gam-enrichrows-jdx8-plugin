# Step Development Guide - GAM Rows Enrichment Plugin

## Table of Contents
1. [Introduction](#introduction)
2. [Quick Start](#quick-start)
3. [Step Anatomy](#step-anatomy)
4. [Implementation Guide](#implementation-guide)
5. [Data Context Management](#data-context-management)
6. [Helper Methods](#helper-methods)
7. [Common Patterns](#common-patterns)
8. [Exception Handling](#exception-handling)
9. [Testing Steps](#testing-steps)
10. [Performance Considerations](#performance-considerations)
11. [Real-World Examples](#real-world-examples)
12. [Troubleshooting](#troubleshooting)

## Introduction

Steps are the core processing units in the enrichment pipeline. Each step performs a specific transformation or validation on transaction data. This guide provides comprehensive instructions for developing new steps and maintaining existing ones.

### What is a Step?

A step is a self-contained processing unit that:
- Receives a `DataContext` containing transaction data
- Performs specific processing (validation, enrichment, transformation)
- Returns a `StepResult` indicating success or failure
- Can optionally decide whether to execute based on context

### Step Lifecycle

```
1. shouldExecute() → Decide if step should run
   ↓ (if true)
2. execute() → Framework method (calls performStep)
   ↓
3. performStep() → Your implementation
   ↓
4. Return StepResult → Success/failure with message
```

## Quick Start

### Create a New Step in 3 Minutes

1. **Create the step class**:
```java
package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.framework.*;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.commons.util.LogUtil;

public class MyNewStep extends AbstractDataStep {
    
    private static final String CLASS_NAME = MyNewStep.class.getName();
    
    @Override
    public String getStepName() {
        return "My New Step";
    }
    
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        // Your logic here
        LogUtil.info(CLASS_NAME, "Processing: " + context.getTransactionId());
        
        // Add data to context
        context.setAdditionalDataValue("myData", "processed");
        
        return new StepResult(true, "Processing successful");
    }
}
```

2. **Add to pipeline** in `RowsEnricher.java`:
```java
DataPipeline pipeline = new DataPipeline(dao)
    .addStep(new CurrencyValidationStep())
    .addStep(new MyNewStep())  // ← Add here
    .addStep(new FXConversionStep())
    // ... other steps
```

3. **Build and deploy**:
```bash
mvn clean package
cp target/rows-enrichment-8.1-SNAPSHOT.jar /path/to/joget/wflow/app_plugins/
```

## Step Anatomy

### Required Components

Every step must have:

```java
public class YourStep extends AbstractDataStep {
    
    // 1. Class name for logging
    private static final String CLASS_NAME = YourStep.class.getName();
    
    // 2. Step name for identification
    @Override
    public String getStepName() {
        return "Your Step Name";  // Used in logs and results
    }
    
    // 3. Processing logic
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        // Your implementation
        return new StepResult(success, message);
    }
}
```

### Optional Components

```java
public class YourStep extends AbstractDataStep {
    
    // 4. Conditional execution (optional)
    @Override
    public boolean shouldExecute(DataContext context) {
        // Return false to skip this step
        return "BANK".equals(context.getSourceType());
    }
    
    // 5. Constants (recommended)
    private static final String TABLE_NAME = "app_fd_your_table";
    private static final String ERROR_CODE = "YOUR_ERROR";
    
    // 6. Helper methods (as needed)
    private boolean validateSomething(String value) {
        // Validation logic
        return value != null && !value.isEmpty();
    }
}
```

## Implementation Guide

### Step-by-Step Implementation

#### 1. Define the Purpose
Be clear about what your step does:
```java
/**
 * Validates that the transaction amount is within acceptable limits
 * and flags high-value transactions for additional review.
 * 
 * Business Rules:
 * - Amounts > 1,000,000 require senior approval
 * - Amounts > 10,000,000 are blocked
 * - Negative amounts are rejected
 */
public class AmountValidationStep extends AbstractDataStep {
```

#### 2. Implement Core Logic
```java
@Override
protected StepResult performStep(DataContext context, FormDataDao dao) {
    try {
        // 1. Extract data from context
        String amountStr = context.getAmount();
        
        // 2. Validate input
        if (amountStr == null || amountStr.isEmpty()) {
            return handleMissingAmount(context, dao);
        }
        
        // 3. Process data
        double amount = parseAmount(amountStr);
        
        // 4. Apply business rules
        if (amount < 0) {
            return handleNegativeAmount(context, dao, amount);
        }
        
        if (amount > 10_000_000) {
            return handleBlockedAmount(context, dao, amount);
        }
        
        if (amount > 1_000_000) {
            flagForReview(context, dao, amount);
        }
        
        // 5. Enrich context
        context.setAdditionalDataValue("amountValidated", true);
        context.setAdditionalDataValue("amountCategory", categorizeAmount(amount));
        
        // 6. Create audit trail
        createAuditLog(context, dao, "AMOUNT_VALIDATED", 
            String.format("Amount %.2f validated", amount));
        
        // 7. Return success
        return new StepResult(true, "Amount validation passed");
        
    } catch (Exception e) {
        LogUtil.error(CLASS_NAME, e, "Error validating amount");
        return new StepResult(false, "Amount validation failed: " + e.getMessage());
    }
}
```

#### 3. Handle Edge Cases
```java
private StepResult handleMissingAmount(DataContext context, FormDataDao dao) {
    createException(context, dao, "MISSING_AMOUNT", 
        "HIGH", "Transaction has no amount specified");
    return new StepResult(false, "Missing amount");
}

private StepResult handleNegativeAmount(DataContext context, FormDataDao dao, double amount) {
    createException(context, dao, "NEGATIVE_AMOUNT", 
        "CRITICAL", String.format("Negative amount: %.2f", amount));
    return new StepResult(false, "Negative amount not allowed");
}
```

## Data Context Management

### Reading from Context

```java
// Core fields
String transactionId = context.getTransactionId();
String sourceType = context.getSourceType();  // "BANK" or "SECU"
String currency = context.getCurrency();
String amount = context.getAmount();

// Raw data access
FormRow transactionRow = context.getTransactionRow();
String customField = transactionRow.getProperty("c_custom_field");

// Additional data from previous steps
Map<String, Object> additionalData = context.getAdditionalData();
Object previousStepData = additionalData.get("dataFromPreviousStep");

// Safe retrieval with type checking
String safeValue = null;
if (additionalData.containsKey("myKey")) {
    Object value = additionalData.get("myKey");
    if (value instanceof String) {
        safeValue = (String) value;
    }
}
```

### Writing to Context

```java
// Set core fields (if your step is responsible)
context.setCustomerId("CUST-123");
context.setBaseAmount("920.50");

// Add enrichment data for downstream steps
context.setAdditionalDataValue("validationPassed", true);
context.setAdditionalDataValue("riskScore", 75);
context.setAdditionalDataValue("processingTimestamp", new Date());

// Store complex objects
Map<String, String> metadata = new HashMap<>();
metadata.put("validator", "AmountValidationStep");
metadata.put("version", "1.0");
context.setAdditionalDataValue("validationMetadata", metadata);

// Update lists
List<String> warnings = (List<String>) context.getAdditionalDataValue("warnings");
if (warnings == null) {
    warnings = new ArrayList<>();
}
warnings.add("High amount transaction");
context.setAdditionalDataValue("warnings", warnings);
```

## Helper Methods

### Inherited from AbstractDataStep

```java
// Database Operations
FormRow loadFormRow(FormDataDao dao, String tableName, String id)
boolean saveFormRow(FormDataDao dao, String tableName, FormRow row)
boolean updateFormRow(FormDataDao dao, String tableName, FormRow row)
FormRow createFormRow()  // Creates new FormRow with UUID

// Query Operations
FormRowSet loadRecords(FormDataDao dao, String tableName, 
                      String condition, Object[] params,
                      String sort, boolean desc, int limit)

// Filtering
List<FormRow> filterByStatus(FormRowSet rows, String statusField, String status)
List<FormRow> filterByCriteria(FormRowSet rows, Map<String, String> criteria)

// Exception Management
void createException(DataContext context, FormDataDao dao,
                    String errorCode, String priority, String details)

// Audit Logging
void createAuditLog(DataContext context, FormDataDao dao,
                   String action, String details)

// Status Management
boolean updateTransactionStatus(DataContext context, FormDataDao dao, String status)

// Utilities
double parseAmount(String amountStr)  // Handles various number formats
String getStringValue(Object obj)     // Safe string conversion
void setPropertySafe(FormRow row, String property, Object value)  // Null-safe setter
```

### Creating Custom Helpers

```java
public class YourStep extends AbstractDataStep {
    
    // Date formatting
    private String formatDate(String dateStr) {
        try {
            SimpleDateFormat input = new SimpleDateFormat("yyyy-MM-dd");
            SimpleDateFormat output = new SimpleDateFormat("dd/MM/yyyy");
            Date date = input.parse(dateStr);
            return output.format(date);
        } catch (Exception e) {
            return dateStr;  // Return original if parsing fails
        }
    }
    
    // Amount validation
    private boolean isValidAmount(String amountStr) {
        try {
            double amount = Double.parseDouble(amountStr);
            return amount > 0 && amount < 100_000_000;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    // Master data lookup with caching
    private Map<String, FormRow> currencyCache = new HashMap<>();
    
    private FormRow getCurrencyMaster(FormDataDao dao, String currency) {
        if (!currencyCache.containsKey(currency)) {
            FormRow row = loadFormRow(dao, "app_fd_currency_master", currency);
            currencyCache.put(currency, row);
        }
        return currencyCache.get(currency);
    }
}
```

## Common Patterns

### 1. Validation Pattern

```java
public class ValidationStep extends AbstractDataStep {
    
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        // Get value to validate
        String value = context.getSomeValue();
        
        // Check required field
        if (value == null || value.trim().isEmpty()) {
            createException(context, dao, "MISSING_VALUE", "HIGH", 
                          "Required field is missing");
            return new StepResult(false, "Validation failed: Missing value");
        }
        
        // Validate format
        if (!value.matches("^[A-Z]{3}$")) {
            createException(context, dao, "INVALID_FORMAT", "MEDIUM",
                          "Value must be 3 uppercase letters");
            return new StepResult(false, "Validation failed: Invalid format");
        }
        
        // Validate against master data
        FormRow masterRecord = loadFormRow(dao, "master_table", value);
        if (masterRecord == null) {
            createException(context, dao, "NOT_IN_MASTER", "HIGH",
                          "Value not found in master data: " + value);
            return new StepResult(false, "Validation failed: Not in master data");
        }
        
        // Check status
        String status = masterRecord.getProperty("c_status");
        if (!"active".equals(status)) {
            createException(context, dao, "INACTIVE_RECORD", "MEDIUM",
                          "Master record is inactive: " + value);
            return new StepResult(false, "Validation failed: Inactive record");
        }
        
        // Validation passed - enrich context
        context.setAdditionalDataValue("validated", true);
        context.setAdditionalDataValue("masterRecordName", 
                                      masterRecord.getProperty("c_name"));
        
        return new StepResult(true, "Validation successful");
    }
}
```

### 2. Enrichment Pattern

```java
public class EnrichmentStep extends AbstractDataStep {
    
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        String entityId = context.getEntityId();
        
        // Load enrichment data
        FormRow entity = loadFormRow(dao, "app_fd_entity_master", entityId);
        
        if (entity == null) {
            // Not found - continue without enrichment
            LogUtil.warn(CLASS_NAME, "Entity not found: " + entityId);
            context.setAdditionalDataValue("entityFound", false);
            return new StepResult(true, "No enrichment data available");
        }
        
        // Enrich context with all relevant fields
        Map<String, Object> enrichmentData = new HashMap<>();
        enrichmentData.put("entityName", entity.getProperty("c_name"));
        enrichmentData.put("entityType", entity.getProperty("c_type"));
        enrichmentData.put("entityCategory", entity.getProperty("c_category"));
        enrichmentData.put("entityStatus", entity.getProperty("c_status"));
        
        // Add computed fields
        enrichmentData.put("riskLevel", calculateRiskLevel(entity));
        enrichmentData.put("requiresReview", determineReviewRequired(entity));
        
        // Store in context
        context.setAdditionalDataValue("entityEnrichment", enrichmentData);
        context.setAdditionalDataValue("entityFound", true);
        
        // Audit the enrichment
        createAuditLog(context, dao, "ENTITY_ENRICHED",
            "Added entity data: " + entity.getProperty("c_name"));
        
        return new StepResult(true, "Entity data enriched successfully");
    }
    
    private String calculateRiskLevel(FormRow entity) {
        // Business logic for risk calculation
        String type = entity.getProperty("c_type");
        String category = entity.getProperty("c_category");
        
        if ("HIGH_RISK".equals(category)) {
            return "HIGH";
        } else if ("FINANCIAL".equals(type)) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
```

### 3. Transformation Pattern

```java
public class TransformationStep extends AbstractDataStep {
    
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        try {
            // Get source data
            String sourceFormat = context.getProperty("format");
            String sourceData = context.getProperty("data");
            
            // Transform based on format
            String transformedData;
            switch (sourceFormat) {
                case "XML":
                    transformedData = transformFromXML(sourceData);
                    break;
                case "JSON":
                    transformedData = transformFromJSON(sourceData);
                    break;
                case "CSV":
                    transformedData = transformFromCSV(sourceData);
                    break;
                default:
                    transformedData = sourceData;  // No transformation
            }
            
            // Store transformed data
            context.setAdditionalDataValue("transformedData", transformedData);
            context.setAdditionalDataValue("transformationApplied", true);
            
            // Record transformation
            createAuditLog(context, dao, "DATA_TRANSFORMED",
                String.format("Transformed from %s format", sourceFormat));
            
            return new StepResult(true, "Data transformed successfully");
            
        } catch (TransformationException e) {
            createException(context, dao, "TRANSFORMATION_FAILED", "HIGH",
                          "Failed to transform data: " + e.getMessage());
            return new StepResult(false, "Transformation failed");
        }
    }
}
```

### 4. Conditional Execution Pattern

```java
public class ConditionalStep extends AbstractDataStep {
    
    @Override
    public boolean shouldExecute(DataContext context) {
        // Multiple conditions for execution
        
        // 1. Check source type
        if (!"BANK".equals(context.getSourceType())) {
            LogUtil.info(CLASS_NAME, "Skipping - not a bank transaction");
            return false;
        }
        
        // 2. Check amount threshold
        double amount = parseAmount(context.getAmount());
        if (amount < 1000) {
            LogUtil.info(CLASS_NAME, "Skipping - amount below threshold");
            return false;
        }
        
        // 3. Check if already processed
        Map<String, Object> data = context.getAdditionalData();
        if (Boolean.TRUE.equals(data.get("alreadyProcessed"))) {
            LogUtil.info(CLASS_NAME, "Skipping - already processed");
            return false;
        }
        
        return true;  // Execute the step
    }
    
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        // This only runs when all conditions are met
        // ... processing logic ...
        return new StepResult(true, "Conditional processing complete");
    }
}
```

## Exception Handling

### Creating Exceptions

```java
protected void createException(DataContext context, FormDataDao dao,
                             String errorCode, String priority, String details) {
    try {
        FormRow exception = createFormRow();
        
        // Core exception data
        exception.setProperty("c_transaction_id", context.getTransactionId());
        exception.setProperty("c_statement_id", context.getStatementId());
        exception.setProperty("c_error_code", errorCode);
        exception.setProperty("c_priority", priority);  // CRITICAL, HIGH, MEDIUM, LOW
        exception.setProperty("c_details", details);
        
        // Context data
        exception.setProperty("c_source_type", context.getSourceType());
        exception.setProperty("c_amount", context.getAmount());
        exception.setProperty("c_currency", context.getCurrency());
        
        // Processing metadata
        exception.setProperty("c_step_name", getStepName());
        exception.setProperty("c_status", "PENDING");
        exception.setProperty("c_created_date", new Date().toString());
        
        // Assignment logic
        String assignedTo = determineAssignment(priority, errorCode);
        exception.setProperty("c_assigned_to", assignedTo);
        
        // Due date based on priority
        Date dueDate = calculateDueDate(priority);
        exception.setProperty("c_due_date", dueDate.toString());
        
        // Save exception
        saveFormRow(dao, "app_fd_exception_queue", exception);
        
        // Audit the exception
        createAuditLog(context, dao, "EXCEPTION_CREATED",
            String.format("Exception %s: %s", errorCode, details));
        
        LogUtil.info(CLASS_NAME, String.format(
            "Exception created: %s for transaction %s", 
            errorCode, context.getTransactionId()));
            
    } catch (Exception e) {
        LogUtil.error(CLASS_NAME, e, "Failed to create exception");
    }
}

private String determineAssignment(String priority, String errorCode) {
    if ("CRITICAL".equals(priority)) {
        return "supervisor_team";
    } else if ("HIGH".equals(priority)) {
        return "senior_team";
    } else if (errorCode.startsWith("TECH_")) {
        return "it_support";
    }
    return "processing_team";
}

private Date calculateDueDate(String priority) {
    Calendar cal = Calendar.getInstance();
    switch (priority) {
        case "CRITICAL":
            cal.add(Calendar.HOUR, 4);
            break;
        case "HIGH":
            cal.add(Calendar.DAY_OF_MONTH, 1);
            break;
        case "MEDIUM":
            cal.add(Calendar.DAY_OF_MONTH, 2);
            break;
        default:
            cal.add(Calendar.DAY_OF_MONTH, 5);
    }
    return cal.getTime();
}
```

### Exception Recovery

```java
@Override
protected StepResult performStep(DataContext context, FormDataDao dao) {
    try {
        // Main processing
        return processNormally(context, dao);
        
    } catch (RecoverableException e) {
        // Try recovery
        LogUtil.warn(CLASS_NAME, "Attempting recovery: " + e.getMessage());
        
        try {
            return attemptRecovery(context, dao);
        } catch (Exception recoveryError) {
            createException(context, dao, "RECOVERY_FAILED", "HIGH",
                "Recovery failed: " + recoveryError.getMessage());
            return new StepResult(false, "Processing failed after recovery attempt");
        }
        
    } catch (Exception e) {
        // Unrecoverable error
        createException(context, dao, "CRITICAL_ERROR", "CRITICAL",
            "Unrecoverable error: " + e.getMessage());
        return new StepResult(false, "Critical error: " + e.getMessage());
    }
}
```

## Testing Steps

### Unit Test Template

```java
package com.fiscaladmin.gam.enrichrows.steps;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class YourStepTest {
    
    private YourStep step;
    private DataContext context;
    
    @Mock
    private FormDataDao mockDao;
    
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        step = new YourStep();
        context = new DataContext();
    }
    
    @Test
    public void testSuccessfulProcessing() {
        // Arrange
        context.setTransactionId("TEST-001");
        context.setCurrency("USD");
        context.setAmount("1000.00");
        
        FormRow mockMasterData = new FormRow();
        mockMasterData.setProperty("c_status", "active");
        mockMasterData.setProperty("c_name", "US Dollar");
        
        when(mockDao.load(isNull(), eq("app_fd_currency_master"), eq("USD")))
            .thenReturn(mockMasterData);
        
        // Act
        StepResult result = step.execute(context, mockDao);
        
        // Assert
        assertTrue("Step should succeed", result.isSuccess());
        assertEquals("Currency validated", result.getMessage());
        assertEquals(Boolean.TRUE, context.getAdditionalDataValue("currencyValid"));
        
        // Verify interactions
        verify(mockDao, times(1)).load(any(), any(), eq("USD"));
    }
    
    @Test
    public void testMissingCurrency() {
        // Arrange
        context.setTransactionId("TEST-002");
        context.setCurrency(null);
        
        // Act
        StepResult result = step.execute(context, mockDao);
        
        // Assert
        assertFalse("Step should fail", result.isSuccess());
        assertTrue("Should contain error message", 
                  result.getMessage().contains("Missing"));
        
        // Verify exception was created
        verify(mockDao, times(1)).saveOrUpdate(any(), 
            eq("app_fd_exception_queue"), any());
    }
    
    @Test
    public void testConditionalExecution() {
        // Arrange
        context.setSourceType("SECU");
        
        // Act
        boolean shouldExecute = step.shouldExecute(context);
        
        // Assert
        assertFalse("Should not execute for securities", shouldExecute);
    }
}
```

### Integration Test Template

```java
@Test
public void testStepInPipeline() {
    // Setup
    FormDataDao dao = getTestDao();
    DataContext context = createTestContext();
    
    // Build pipeline with your step
    DataPipeline pipeline = new DataPipeline(dao)
        .addStep(new YourStep())
        .addStep(new NextStep());
    
    // Execute
    PipelineResult result = pipeline.execute(context);
    
    // Verify your step executed
    assertTrue(result.getStepResults().containsKey("Your Step Name"));
    StepResult stepResult = result.getStepResults().get("Your Step Name");
    assertTrue(stepResult.isSuccess());
    
    // Verify data was passed to next step
    assertNotNull(context.getAdditionalDataValue("yourData"));
}
```

### Testing Checklist

- [ ] Test successful case
- [ ] Test null/empty input
- [ ] Test invalid input
- [ ] Test database errors
- [ ] Test conditional execution
- [ ] Test exception creation
- [ ] Test audit logging
- [ ] Test data enrichment
- [ ] Test edge cases
- [ ] Test performance with large data

## Performance Considerations

### 1. Database Query Optimization

```java
public class OptimizedStep extends AbstractDataStep {
    
    // Cache frequently accessed data
    private Map<String, FormRow> masterDataCache = new HashMap<>();
    
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        // Use cached data when possible
        String key = context.getKey();
        FormRow masterData = masterDataCache.get(key);
        
        if (masterData == null) {
            masterData = loadFormRow(dao, "master_table", key);
            if (masterData != null) {
                masterDataCache.put(key, masterData);
            }
        }
        
        // Process with cached data
        // ...
    }
    
    // Clear cache periodically
    public void clearCache() {
        masterDataCache.clear();
    }
}
```

### 2. Batch Processing

```java
public class BatchProcessingStep extends AbstractDataStep {
    
    private List<DataContext> batchBuffer = new ArrayList<>();
    private static final int BATCH_SIZE = 100;
    
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        // Add to batch
        batchBuffer.add(context);
        
        // Process when batch is full
        if (batchBuffer.size() >= BATCH_SIZE) {
            processBatch(dao);
        }
        
        return new StepResult(true, "Added to batch");
    }
    
    private void processBatch(FormDataDao dao) {
        // Process entire batch at once
        List<String> ids = batchBuffer.stream()
            .map(DataContext::getTransactionId)
            .collect(Collectors.toList());
        
        // Single query for all records
        String condition = "WHERE c_id IN (" + 
            String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
        FormRowSet results = dao.find(null, "table", condition, 
                                     ids.toArray(), null, false, 0, -1);
        
        // Process results
        // ...
        
        // Clear batch
        batchBuffer.clear();
    }
}
```

### 3. Lazy Loading

```java
public class LazyLoadingStep extends AbstractDataStep {
    
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        // Only load data if needed
        if (requiresEnrichment(context)) {
            FormRow enrichmentData = loadFormRow(dao, "heavy_table", 
                                                context.getId());
            processEnrichment(context, enrichmentData);
        }
        
        return new StepResult(true, "Processing complete");
    }
    
    private boolean requiresEnrichment(DataContext context) {
        // Check if enrichment is actually needed
        return context.getAdditionalDataValue("enrichmentRequired") != null;
    }
}
```

## Real-World Examples

### Example 1: CurrencyValidationStep (From Actual Implementation)

```java
public class CurrencyValidationStep extends AbstractDataStep {
    
    private static final String CLASS_NAME = CurrencyValidationStep.class.getName();
    
    @Override
    public String getStepName() {
        return "Currency Validation";
    }
    
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        String currency = context.getCurrency();
        
        // Validate currency exists
        if (currency == null || currency.trim().isEmpty()) {
            createException(context, dao, "MISSING_CURRENCY", 
                determinePriority(context), "Transaction has no currency");
            return new StepResult(false, "Missing currency");
        }
        
        // Normalize and validate
        currency = currency.toUpperCase().trim();
        FormRow currencyMaster = loadFormRow(dao, 
            DomainConstants.TABLE_CURRENCY_MASTER, currency);
        
        if (currencyMaster == null) {
            createException(context, dao, "INVALID_CURRENCY",
                determinePriority(context), "Unknown currency: " + currency);
            return new StepResult(false, "Invalid currency: " + currency);
        }
        
        // Check if active
        String status = currencyMaster.getProperty("c_status");
        if (!"active".equals(status)) {
            createException(context, dao, "INACTIVE_CURRENCY",
                "MEDIUM", "Currency is inactive: " + currency);
            return new StepResult(false, "Inactive currency: " + currency);
        }
        
        // Enrich context
        context.setAdditionalDataValue("currencyValid", true);
        context.setAdditionalDataValue("currencyName", 
            currencyMaster.getProperty("c_name"));
        context.setAdditionalDataValue("currencyDecimals",
            currencyMaster.getProperty("c_decimal_places"));
        
        // Audit
        createAuditLog(context, dao, "CURRENCY_VALIDATED",
            "Currency " + currency + " validated successfully");
        
        return new StepResult(true, "Currency validated");
    }
    
    private String determinePriority(DataContext context) {
        double amount = parseAmount(context.getAmount());
        if (amount >= 1_000_000) return "CRITICAL";
        if (amount >= 100_000) return "HIGH";
        if (amount >= 10_000) return "MEDIUM";
        return "LOW";
    }
}
```

### Example 2: F14RuleMappingStep (Complex Business Logic)

```java
public class F14RuleMappingStep extends AbstractDataStep {
    
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        try {
            // Load applicable rules
            List<FormRow> rules = loadF14Rules(dao, context);
            
            if (rules.isEmpty()) {
                handleNoRules(context, dao);
                return new StepResult(true, "No F14 rules found");
            }
            
            // Evaluate rules in priority order
            for (FormRow rule : rules) {
                if (evaluateRule(rule, context)) {
                    applyRule(rule, context, dao);
                    return new StepResult(true, "F14 rule applied: " + 
                        rule.getProperty("c_rule_name"));
                }
            }
            
            // No matching rule
            handleNoMatch(context, dao);
            return new StepResult(true, "No matching F14 rule");
            
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error in F14 mapping");
            return new StepResult(false, "F14 mapping failed");
        }
    }
    
    private List<FormRow> loadF14Rules(FormDataDao dao, DataContext context) {
        String counterpartyId = (String) context.getAdditionalDataValue("counterpartyId");
        
        // Load rules for specific counterparty and system rules
        String condition = "WHERE c_status = 'active' AND " +
                         "(c_counterpartyId = ? OR c_counterpartyId = 'SYSTEM') " +
                         "ORDER BY c_priority ASC";
        
        FormRowSet rowSet = dao.find(null, DomainConstants.TABLE_F14_RULES,
            condition, new Object[]{counterpartyId}, null, false, 0, -1);
        
        return new ArrayList<>(rowSet);
    }
    
    private boolean evaluateRule(FormRow rule, DataContext context) {
        String conditions = rule.getProperty("c_conditions");
        // Parse and evaluate conditions
        // ... complex evaluation logic ...
        return true;  // Simplified
    }
    
    private void applyRule(FormRow rule, DataContext context, FormDataDao dao) {
        String internalType = rule.getProperty("c_internal_type");
        
        context.setAdditionalDataValue("internal_type", internalType);
        context.setAdditionalDataValue("f14_rule_id", rule.getId());
        context.setAdditionalDataValue("f14_rule_name", 
            rule.getProperty("c_rule_name"));
        
        createAuditLog(context, dao, "F14_MAPPED",
            "Mapped to " + internalType + " using rule " + rule.getId());
    }
}
```

## Troubleshooting

### Common Issues and Solutions

#### 1. Step Not Executing

**Symptoms**: Step appears to be skipped, no logs from step

**Check**:
```java
// 1. Verify step is added to pipeline
DataPipeline pipeline = new DataPipeline(dao)
    .addStep(new YourStep())  // ← Is this present?

// 2. Check shouldExecute() method
@Override
public boolean shouldExecute(DataContext context) {
    boolean willExecute = /* your condition */;
    LogUtil.info(CLASS_NAME, "Will execute: " + willExecute);
    return willExecute;
}

// 3. Add logging at start of performStep()
@Override
protected StepResult performStep(DataContext context, FormDataDao dao) {
    LogUtil.info(CLASS_NAME, "Step starting for: " + context.getTransactionId());
    // ...
}
```

#### 2. Database Operations Failing

**Symptoms**: Null results, save operations not working

**Check**:
```java
// 1. Verify table name includes prefix
String TABLE = "app_fd_your_table";  // Must include app_fd_

// 2. Verify field names include prefix
row.setProperty("c_field_name", value);  // Must include c_

// 3. Check FormRow has ID before saving
FormRow row = createFormRow();  // This sets UUID automatically
// OR
row.setId(UUID.randomUUID().toString());

// 4. Use correct save method
FormRowSet rowSet = new FormRowSet();
rowSet.add(row);
dao.saveOrUpdate(null, TABLE, rowSet);  // Use null for formId
```

#### 3. Data Not Passing Between Steps

**Symptoms**: Next step can't access data from previous step

**Check**:
```java
// In first step - setting data
context.setAdditionalDataValue("myKey", myValue);
LogUtil.info(CLASS_NAME, "Set myKey = " + myValue);

// In next step - getting data
Object value = context.getAdditionalDataValue("myKey");
LogUtil.info(CLASS_NAME, "Got myKey = " + value);

// Check for typos in keys
// Use constants for shared keys:
public class SharedKeys {
    public static final String CUSTOMER_DATA = "customerData";
    public static final String VALIDATION_RESULT = "validationResult";
}
```

#### 4. Performance Issues

**Symptoms**: Step takes too long, timeouts

**Solutions**:
```java
// 1. Add timing logs
long start = System.currentTimeMillis();
// ... processing ...
long elapsed = System.currentTimeMillis() - start;
LogUtil.info(CLASS_NAME, "Step took " + elapsed + "ms");

// 2. Cache frequently used data
private static Map<String, FormRow> cache = new HashMap<>();

// 3. Use batch queries
String ids = String.join(",", idList);
String condition = "WHERE c_id IN (" + ids + ")";

// 4. Limit query results
FormRowSet rows = dao.find(null, table, condition, params,
                          sort, false, 0, 100);  // Limit to 100
```

#### 5. Exceptions Not Created

**Symptoms**: Errors occur but no exception records created

**Check**:
```java
// 1. Verify exception table exists
String EXCEPTION_TABLE = "app_fd_exception_queue";

// 2. Add logging in exception creation
protected void createException(...) {
    LogUtil.info(CLASS_NAME, "Creating exception: " + errorCode);
    try {
        // ... create exception ...
        LogUtil.info(CLASS_NAME, "Exception created successfully");
    } catch (Exception e) {
        LogUtil.error(CLASS_NAME, e, "Failed to create exception");
    }
}

// 3. Ensure FormRow has required fields
exception.setId(UUID.randomUUID().toString());
exception.setProperty("c_transaction_id", context.getTransactionId());
// ... other required fields
```

### Debug Checklist

When a step isn't working:

1. **Check Logs**
   - [ ] Joget logs: `[JOGET_HOME]/wflow/logs/`
   - [ ] Look for your CLASS_NAME in logs
   - [ ] Check for exceptions and stack traces

2. **Verify Configuration**
   - [ ] Step added to pipeline
   - [ ] Table names correct (with app_fd_ prefix)
   - [ ] Field names correct (with c_ prefix)
   - [ ] Master data exists in database

3. **Test in Isolation**
   - [ ] Create unit test for the step
   - [ ] Test with mock data
   - [ ] Test edge cases

4. **Add Debug Logging**
   - [ ] Log at entry point
   - [ ] Log before/after database operations
   - [ ] Log data transformations
   - [ ] Log exit points

5. **Review Data Flow**
   - [ ] Check DataContext has required fields
   - [ ] Verify previous steps completed
   - [ ] Check additional data map

## Summary

Steps are the building blocks of the enrichment pipeline. By following this guide:

1. **Start Simple**: Begin with basic validation or enrichment
2. **Use Helpers**: Leverage AbstractDataStep methods
3. **Handle Errors**: Always use try-catch and create exceptions
4. **Test Thoroughly**: Unit test each step independently
5. **Document Well**: Add comments explaining business logic
6. **Monitor Performance**: Add timing logs for optimization

Remember: Each step should do one thing well, be testable in isolation, and handle errors gracefully.

---

*For more information, see DEVELOPERS_GUIDE.md for overall architecture and BUSINESS_LOGIC.md for business rules.*