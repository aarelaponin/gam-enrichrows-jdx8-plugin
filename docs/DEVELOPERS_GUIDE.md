# Data Pipeline Framework - Reuse Guide

## Overview

The plugin contains a generic data processing framework that can be reused for other plugins. 
The framework provides a pipeline pattern for processing data through a series of steps.

## Framework Components

### Core Framework Classes (Generic)

These classes are domain-agnostic and can be reused as-is:

```
com.fiscaladmin.gam.enrichrows.framework/
├── DataStep.java              # Interface for processing steps
├── AbstractDataStep.java      # Base class for steps with helper methods
├── DataContext.java          # Data container passed between steps
├── DataPipeline.java         # Pipeline orchestrator
├── DataLoader.java           # Interface for data loading
├── DataPersister.java        # Interface for data persistence
├── StepResult.java           # Result from a single step
├── PipelineResult.java       # Result from complete pipeline
└── BatchPipelineResult.java  # Result from batch processing
```

### Key Features

- **Pipeline Pattern**: Process data through a series of steps
- **Batch Processing**: Handle multiple records efficiently
- **Error Handling**: Built-in error handling and recovery
- **Audit Trail**: Automatic audit logging capability
- **Flexible Context**: Generic data container with additional data map
- **Conditional Execution**: Steps can decide whether to execute

## How to Use for Your Plugin

### 1. Create Your Data Context

Either use `DataContext` directly or extend it:

```java
// Option 1: Use directly
DataContext context = new DataContext();
context.setTransactionId("your-record-id");
context.setAdditionalDataValue("customField", value);

// Option 2: Extend for your domain
public class InvoiceContext extends DataContext {
    private String invoiceNumber;
    private String vendorId;
    // Add your domain-specific fields
}
```

### 2. Create Processing Steps

Extend `AbstractDataStep` for each processing stage:

```java
public class ValidationStep extends AbstractDataStep {
    
    @Override
    public String getStepName() {
        return "Validation";
    }
    
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        // Your validation logic
        if (isValid(context)) {
            return new StepResult(true, "Validation passed");
        } else {
            return new StepResult(false, "Validation failed");
        }
    }
    
    @Override
    public boolean shouldExecute(DataContext context) {
        // Optional: Add conditions for execution
        return true;
    }
}
```

### 3. Create Data Loader

Implement the `DataLoader` interface:

```java
public class MyDataLoader implements DataLoader<DataContext> {
    
    @Override
    public List<DataContext> loadData(FormDataDao dao, Map<String, Object> params) {
        List<DataContext> contexts = new ArrayList<>();
        
        // Load your data from database
        FormRowSet rows = dao.find(formId, tableName, condition, params, null, false, 0, -1);
        
        for (FormRow row : rows) {
            DataContext context = new DataContext();
            // Populate context from row
            contexts.add(context);
        }
        
        return contexts;
    }
    
    @Override
    public String getLoaderName() {
        return "My Data Loader";
    }
}
```

### 4. Build and Execute Pipeline

```java
public class MyProcessor extends DefaultApplicationPlugin {
    
    @Override
    public Object execute(Map properties) {
        FormDataDao dao = (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");
        
        // Load data
        MyDataLoader loader = new MyDataLoader();
        List<DataContext> data = loader.loadData(dao, properties);
        
        // Build pipeline
        DataPipeline pipeline = new DataPipeline(dao)
            .addStep(new ValidationStep())
            .addStep(new EnrichmentStep())
            .addStep(new ProcessingStep())
            .setStopOnError(true);
        
        // Execute
        BatchPipelineResult result = pipeline.executeBatch(data);
        
        // Handle results
        return String.format("Processed: %d, Success: %d, Failed: %d",
            result.getTotalTransactions(),
            result.getSuccessCount(),
            result.getFailureCount());
    }
}
```

## Helper Methods in AbstractDataStep

The base class provides useful helper methods:

```java
// Load a record from database
FormRow loadFormRow(FormDataDao dao, String tableName, String id)

// Save a record to database
boolean saveFormRow(FormDataDao dao, String tableName, FormRow row)

// Create audit log entry
void createAuditLog(DataContext context, FormDataDao dao, String action, String details)

// Parse amount string to double
double parseAmount(String amountStr)

// Update transaction status
boolean updateTransactionStatus(DataContext context, FormDataDao dao, String status)
```

## Example: Invoice Processing Plugin

Here's a complete example of using the framework for invoice processing:

```java
// Step 1: Invoice validation
public class InvoiceValidationStep extends AbstractDataStep {
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        String invoiceNumber = (String) context.getAdditionalDataValue("invoiceNumber");
        
        // Validate invoice number format
        if (!invoiceNumber.matches("INV-\\d{6}")) {
            return new StepResult(false, "Invalid invoice number format");
        }
        
        // Check for duplicates
        FormRowSet existing = dao.find(null, "invoice_table", 
            "WHERE c_invoice_number = ?", new Object[]{invoiceNumber}, null, false, 0, 1);
        
        if (!existing.isEmpty()) {
            return new StepResult(false, "Duplicate invoice number");
        }
        
        return new StepResult(true, "Validation passed");
    }
}

// Step 2: Vendor verification
public class VendorVerificationStep extends AbstractDataStep {
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        String vendorId = (String) context.getAdditionalDataValue("vendorId");
        
        FormRow vendor = loadFormRow(dao, "vendor_master", vendorId);
        if (vendor == null) {
            createAuditLog(context, dao, "VENDOR_NOT_FOUND", 
                "Vendor ID: " + vendorId + " not found in master data");
            return new StepResult(false, "Vendor not found");
        }
        
        // Add vendor details to context
        context.setAdditionalDataValue("vendorName", vendor.getProperty("name"));
        context.setAdditionalDataValue("vendorStatus", vendor.getProperty("status"));
        
        return new StepResult(true, "Vendor verified");
    }
}

// Main processor
public class InvoiceProcessor extends DefaultApplicationPlugin {
    @Override
    public Object execute(Map properties) {
        FormDataDao dao = (FormDataDao) AppUtil.getApplicationContext().getBean("formDataDao");
        
        // Build and execute pipeline
        DataPipeline pipeline = new DataPipeline(dao)
            .addStep(new InvoiceValidationStep())
            .addStep(new VendorVerificationStep())
            .addStep(new ApprovalRoutingStep());
        
        // Process invoices
        List<DataContext> invoices = loadInvoices(dao);
        BatchPipelineResult result = pipeline.executeBatch(invoices);
        
        return "Processed " + result.getTotalTransactions() + " invoices";
    }
}
```

## Best Practices

1. **Keep Steps Focused**: Each step should do one thing well
2. **Use Audit Logging**: Call `createAuditLog()` for important events
3. **Handle Errors Gracefully**: Return `StepResult(false, message)` instead of throwing exceptions
4. **Store Enrichment Data**: Use `context.setAdditionalDataValue()` to pass data between steps
5. **Make Steps Conditional**: Override `shouldExecute()` when steps are conditional
6. **Batch Processing**: Use `executeBatch()` for better performance with multiple records

## Common Patterns

### Validation Pattern
```java
public class ValidationStep extends AbstractDataStep {
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        if (!isValid(context)) {
            createException(context, dao, "VALIDATION_FAILED", details);
            return new StepResult(false, "Validation failed");
        }
        return new StepResult(true, "Validation passed");
    }
}
```

### Enrichment Pattern
```java
public class EnrichmentStep extends AbstractDataStep {
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        // Lookup additional data
        FormRow masterData = loadFormRow(dao, "master_table", context.getTransactionId());
        
        // Enrich context
        context.setAdditionalDataValue("enrichedField", masterData.getProperty("field"));
        
        return new StepResult(true, "Enrichment complete");
    }
}
```

### Conditional Processing Pattern
```java
public class ConditionalStep extends AbstractDataStep {
    @Override
    public boolean shouldExecute(DataContext context) {
        // Only execute for specific conditions
        String type = (String) context.getAdditionalDataValue("type");
        return "SPECIAL".equals(type);
    }
    
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        // This only runs when shouldExecute() returns true
        return new StepResult(true, "Special processing complete");
    }
}
```

## Summary

The Data Pipeline Framework provides a robust, reusable foundation for building data processing plugins in Joget. By following the pipeline pattern, you can create maintainable, testable, and scalable plugins for any domain.