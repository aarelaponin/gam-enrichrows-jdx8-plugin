# Step Development Guide

## Quick Start

To add a new processing step to the GL Postings pipeline:

1. Create a class extending `AbstractDataStep`
2. Implement required methods
3. Add the step to the pipeline in `TransactionProcessor`

## Step Implementation

### Basic Structure

```java
package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.framework.AbstractDataStep;
import com.fiscaladmin.gam.enrichrows.framework.StepResult;
import com.fiscaladmin.gam.enrichrows.framework.DataContext;
import org.joget.apps.form.dao.FormDataDao;

public class YourNewStep extends AbstractDataStep {

    private static final String CLASS_NAME = YourNewStep.class.getName();

    @Override
    public String getStepName() {
        return "Your Step Name";
    }

    @Override
    protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
        try {
            // Your processing logic here

            // Success
            return new StepResult(true, "Processing successful");

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error in step");
            return new StepResult(false, "Error: " + e.getMessage());
        }
    }

    @Override
    public boolean shouldExecute(DataContext context) {
        // Optional: Add execution conditions
        return true;  // Execute for all transactions by default
    }
}
```

### Add to Pipeline

In `TransactionProcessor.java`:

```java
DataPipeline pipeline = new DataPipeline(formDataDao)
    .addStep(new CurrencyValidationStep())
    .addStep(new YourNewStep())  // Add your step here
    .addStep(new CounterpartyDeterminationStep())
    // ... other steps
    .setStopOnError(Constants.STOP_ON_ERROR_DEFAULT);
```

## Available Helper Methods

The `AbstractDataStep` base class provides:

```java
// Database operations
FormRow loadFormRow(FormDataDao dao, String tableName, String id)
boolean saveFormRow(FormDataDao dao, String tableName, FormRow row)

// Status management
boolean updateTransactionStatus(DataContext context, FormDataDao dao, String status)

// Audit logging
void createAuditLog(DataContext context, FormDataDao dao, String action, String details)

// Utilities
double parseAmount(String amountStr)
```

## Common Patterns

### Validation Step

```java
@Override
protected StepResult performStep(DataContext context, FormDataDao dao) {
    String value = context.getCurrency();
    
    if (value == null || value.trim().isEmpty()) {
        createException(context, dao, "MISSING_VALUE", "Currency is required");
        return new StepResult(false, "Validation failed: Missing currency");
    }
    
    // Validate against master data
    FormRow masterRecord = loadFormRow(dao, "currency_master", value);
    if (masterRecord == null) {
        createException(context, dao, "INVALID_VALUE", "Currency not found: " + value);
        return new StepResult(false, "Validation failed: Invalid currency");
    }
    
    return new StepResult(true, "Validation passed");
}
```

### Enrichment Step

```java
@Override
protected StepResult performStep(DataContext context, FormDataDao dao) {
    // Lookup additional data
    String customerId = context.getCustomerId();
    FormRow customer = loadFormRow(dao, "customer_master", customerId);
    
    if (customer != null) {
        // Enrich context with customer data
        Map<String, Object> additionalData = context.getAdditionalData();
        additionalData.put("customerName", customer.getProperty("name"));
        additionalData.put("customerType", customer.getProperty("type"));
        additionalData.put("customerSegment", customer.getProperty("segment"));
        
        // Update processing status
        context.setProcessingStatus("customer_enriched");
        
        // Create audit log
        createAuditLog(context, dao, "CUSTOMER_ENRICHED", 
            "Customer data added: " + customer.getProperty("name"));
        
        return new StepResult(true, "Customer data enriched");
    }
    
    return new StepResult(true, "No customer data found - continuing");
}
```

### Conditional Step

```java
@Override
public boolean shouldExecute(DataContext context) {
    // Only execute for bank transactions
    return "bank".equals(context.getSourceType());
}

@Override
protected StepResult performStep(DataContext context, FormDataDao dao) {
    // This only runs for bank transactions
    String bic = context.getOtherSideBic();
    // ... process BIC code
    return new StepResult(true, "Bank-specific processing complete");
}
```

### Exception Creation

```java
private void createException(DataContext context, FormDataDao dao, 
                           String type, String details) {
    try {
        FormRow exception = new FormRow();
        exception.setId(UUID.randomUUID().toString());
        exception.setProperty("transaction_id", context.getTransactionId());
        exception.setProperty("exception_type", type);
        exception.setProperty("exception_details", details);
        exception.setProperty("status", "PENDING");
        exception.setProperty("created_date", new Date().toString());
        
        saveFormRow(dao, "exception_queue", exception);
        
        LogUtil.info(CLASS_NAME, "Exception created: " + type + " for transaction: " + 
            context.getTransactionId());
    } catch (Exception e) {
        LogUtil.error(CLASS_NAME, e, "Error creating exception");
    }
}
```

## Best Practices

1. **Always use try-catch** - Return StepResult with error message, don't throw exceptions
2. **Log important events** - Use LogUtil for debugging
3. **Create audit trail** - Use createAuditLog() for significant actions
4. **Handle null values** - Check for null before processing
5. **Use constants** - Define table names and field names in Constants class
6. **Make steps idempotent** - Steps should produce same result if run multiple times
7. **Keep steps focused** - Each step should have a single responsibility

## Testing Your Step

### Unit Test Example

```java
@Test
public void testSuccessCase() {
    // Setup
    DataContext context = new DataContext();
    context.setTransactionId("test-123");
    context.setCurrency("USD");
    
    FormDataDao mockDao = mock(FormDataDao.class);
    when(mockDao.find(any(), eq("currency_master"), any(), any(), any(), 
        anyBoolean(), anyInt(), anyInt()))
        .thenReturn(createMockCurrencyRow());
    
    // Execute
    YourStep step = new YourStep();
    StepResult result = step.execute(context, mockDao);
    
    // Assert
    assertTrue(result.isSuccess());
    assertEquals("Expected message", result.getMessage());
}
```

## Troubleshooting

### Common Issues

1. **Step not executing**
   - Check if step is added to pipeline
   - Verify shouldExecute() returns true
   - Check logs for errors

2. **Database operations failing**
   - Verify table names match actual database
   - Check FormRow has ID before saving
   - Use Constants for table/field names

3. **Data not passing between steps**
   - Use context.setAdditionalDataValue() to store data
   - Check data is retrieved with correct key

4. **Exceptions not being created**
   - Ensure exception table exists
   - Verify FormRow ID is set
   - Check database permissions

## Example Steps from GL Postings

- **CurrencyValidationStep** - Validates transaction currency
- **CounterpartyDeterminationStep** - Identifies counterparty bank
- **F14RuleMappingStep** - Maps external codes to internal types
- **CustomerIdentificationStep** - Identifies customer from various sources
- **FXConversionStep** - Converts amounts to base currency

Each step follows the patterns shown above and can be used as reference for new step development.