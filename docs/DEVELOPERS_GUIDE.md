# GAM Rows Enrichment Plugin - Developer's Guide

## Table of Contents
1. [Introduction](#introduction)
2. [Architecture Overview](#architecture-overview)
3. [Project Structure](#project-structure)
4. [Core Concepts](#core-concepts)
5. [Data Flow](#data-flow)
6. [Key Classes and Components](#key-classes-and-components)
7. [Development Workflow](#development-workflow)
8. [Testing](#testing)
9. [Debugging](#debugging)
10. [Common Tasks](#common-tasks)
11. [Best Practices](#best-practices)
12. [Troubleshooting](#troubleshooting)

## Introduction

This guide is designed for developers working on the GAM Rows Enrichment Plugin for Joget DX8. Whether you're a junior developer learning the codebase or an AI assistant helping with development, this guide provides comprehensive information about the plugin's architecture, implementation, and development practices.

### What This Plugin Does

The plugin processes financial transactions from bank and securities statements through an enrichment pipeline to prepare them for General Ledger (GL) posting. It:
- Loads unprocessed transactions
- Validates and enriches data through multiple steps
- Manages state transitions at statement and transaction levels
- Persists enriched data for downstream processing

## Architecture Overview

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Joget Platform                        │
│  ┌───────────────────────────────────────────────────┐  │
│  │              RowsEnricher Plugin                  │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌──────────┐ │  │
│  │  │ DataLoader  │→ │ DataPipeline│→ │Persister │ │  │
│  │  └─────────────┘  └─────────────┘  └──────────┘ │  │
│  │         ↓                ↓               ↓       │  │
│  │  ┌─────────────────────────────────────────────┐ │  │
│  │  │            Processing Steps                  │ │  │
│  │  │  Currency→FX→Customer→Counterparty→F14      │ │  │
│  │  └─────────────────────────────────────────────┘ │  │
│  └───────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────┐  │
│  │              Joget Database                       │  │
│  │  Statements | Transactions | Enrichments | Audit  │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### Design Patterns Used

1. **Pipeline Pattern**: Sequential processing through steps
2. **Strategy Pattern**: Each step implements a common interface
3. **Template Method**: AbstractDataStep provides common functionality
4. **Builder Pattern**: DataPipeline uses fluent interface
5. **Data Transfer Object**: DataContext carries data between steps

## Project Structure

```
src/main/java/com/fiscaladmin/gam/
├── Activator.java                    # OSGi bundle activator
└── enrichrows/
    ├── constants/
    │   ├── DomainConstants.java      # Business domain constants
    │   └── FrameworkConstants.java   # Framework-level constants
    ├── framework/                    # Reusable framework components
    │   ├── AbstractDataLoader.java   # Base class for data loaders
    │   ├── AbstractDataPersister.java # Base class for persisters
    │   ├── AbstractDataStep.java     # Base class for processing steps
    │   ├── BatchPersistenceResult.java # Batch persistence results
    │   ├── BatchPipelineResult.java  # Batch processing results
    │   ├── DataContext.java          # Data container
    │   ├── DataLoader.java           # Loader interface
    │   ├── DataPersister.java        # Persister interface
    │   ├── DataPipeline.java         # Pipeline orchestrator
    │   ├── DataStep.java             # Step interface
    │   ├── PersistenceResult.java    # Single persistence result
    │   ├── PipelineResult.java       # Single pipeline result
    │   └── StepResult.java           # Single step result
    ├── lib/
    │   └── RowsEnricher.java         # Main plugin entry point
    ├── loader/
    │   └── TransactionDataLoader.java # Loads transactions
    ├── persister/
    │   └── EnrichmentDataPersister.java # Persists enriched data
    └── steps/                        # Processing step implementations
        ├── CounterpartyDeterminationStep.java
        ├── CurrencyValidationStep.java
        ├── CustomerIdentificationStep.java
        ├── F14RuleMappingStep.java
        └── FXConversionStep.java
```

## Core Concepts

### 1. DataContext

The `DataContext` is the central data container that flows through the pipeline:

```java
public class DataContext {
    // Core identifiers
    private String transactionId;      // Unique transaction ID
    private String statementId;        // Parent statement ID
    private String sourceType;         // "BANK" or "SECU"
    
    // Transaction data
    private String currency;           // Transaction currency
    private String amount;            // Transaction amount
    private String transactionDate;   // Date of transaction
    
    // Enrichment data (added by steps)
    private String customerId;        // Identified customer
    private String counterpartyId;    // Identified counterparty
    private String baseAmount;        // Amount in EUR
    
    // Raw data references
    private FormRow transactionRow;   // Original transaction record
    private FormRow statementRow;     // Original statement record
    
    // Additional data map for flexibility
    private Map<String, Object> additionalData;
}
```

### 2. Processing Steps

Each step extends `AbstractDataStep` and implements:

```java
public abstract class AbstractDataStep implements DataStep {
    // Required: Define step name
    public abstract String getStepName();
    
    // Required: Implement processing logic
    protected abstract StepResult performStep(DataContext context, FormDataDao dao);
    
    // Optional: Conditional execution
    public boolean shouldExecute(DataContext context) {
        return true; // Override for conditional logic
    }
    
    // Provided: Helper methods
    protected FormRow loadFormRow(FormDataDao dao, String table, String id);
    protected boolean saveFormRow(FormDataDao dao, String table, FormRow row);
    protected void createAuditLog(...);
    protected void createException(...);
}
```

### 3. State Management

The plugin manages state at multiple levels:

#### Statement States
```
new → processing → processed/processed_with_errors
```

#### Transaction States
```
new → enriched
```

#### Tracking Implementation
```java
// In TransactionDataLoader
statementRow.setProperty("c_status", "processing");
statementRow.setProperty("processing_started", timestamp);

// In EnrichmentDataPersister
transactionRow.setProperty("c_status", "enriched");
statementRow.setProperty("c_status", allSuccess ? "processed" : "processed_with_errors");
```

## Data Flow

### Complete Processing Flow

```
1. RowsEnricher.execute()
   ↓
2. TransactionDataLoader.loadData()
   - Query statements WHERE c_status = 'new'
   - Mark each statement as 'processing'
   - Load transactions WHERE c_status = 'new'
   - Create DataContext for each transaction
   ↓
3. DataPipeline.executeBatch()
   - For each DataContext:
     a. CurrencyValidationStep
     b. FXConversionStep
     c. CustomerIdentificationStep (bank only)
     d. CounterpartyDeterminationStep
     e. F14RuleMappingStep
   ↓
4. EnrichmentDataPersister.persistBatch()
   - Group transactions by statement
   - Save to trx_enrichment table
   - Update transaction status to 'enriched'
   - Update statement status based on results
   ↓
5. Return processing summary
```

### Data Transformation Example

```java
// Initial DataContext (from loader)
{
  transactionId: "BNK-001",
  statementId: "STMT-001",
  sourceType: "BANK",
  currency: "USD",
  amount: "1000.00",
  transactionDate: "2024-01-15"
}

// After CurrencyValidationStep
{
  ...previous data,
  additionalData: {
    currencyValid: true,
    currencyName: "US Dollar"
  }
}

// After FXConversionStep
{
  ...previous data,
  baseAmount: "920.50",  // Converted to EUR
  additionalData: {
    ...previous,
    fxRate: 0.9205,
    fxRateDate: "2024-01-15"
  }
}

// After all steps (ready for persistence)
{
  ...all accumulated data,
  customerId: "CUST-123",
  counterpartyId: "BANK001",
  additionalData: {
    ...all enrichment data,
    internalType: "PAYMENT_CUSTOMER",
    f14RuleId: "RULE-001"
  }
}
```

## Key Classes and Components

### 1. RowsEnricher (Entry Point)

```java
public class RowsEnricher extends DefaultApplicationPlugin {
    @Override
    public Object execute(Map properties) {
        // 1. Get FormDataDao from Spring context
        FormDataDao dao = (FormDataDao) AppUtil.getApplicationContext()
            .getBean("formDataDao");
        
        // 2. Load transactions
        TransactionDataLoader loader = new TransactionDataLoader();
        List<DataContext> transactions = loader.loadData(dao, properties);
        
        // 3. Build pipeline
        DataPipeline pipeline = new DataPipeline(dao)
            .addStep(new CurrencyValidationStep())
            .addStep(new FXConversionStep())
            // ... more steps
            .setStopOnError(false);
        
        // 4. Process transactions
        BatchPipelineResult pipelineResult = pipeline.executeBatch(transactions);
        
        // 5. Persist results
        EnrichmentDataPersister persister = new EnrichmentDataPersister();
        BatchPersistenceResult persistResult = persister.persistBatch(
            transactions, pipelineResult, dao, properties);
        
        // 6. Return summary
        return formatResults(pipelineResult, persistResult);
    }
}
```

### 2. DataPipeline (Orchestrator)

```java
public class DataPipeline {
    private List<DataStep> steps;
    private FormDataDao dao;
    
    public PipelineResult execute(DataContext context) {
        PipelineResult result = new PipelineResult();
        
        for (DataStep step : steps) {
            if (step.shouldExecute(context)) {
                StepResult stepResult = step.execute(context, dao);
                result.addStepResult(step.getStepName(), stepResult);
                
                if (!stepResult.isSuccess() && stopOnError) {
                    break;
                }
            }
        }
        
        return result;
    }
}
```

### 3. Example Step Implementation

```java
public class CurrencyValidationStep extends AbstractDataStep {
    
    @Override
    public String getStepName() {
        return "Currency Validation";
    }
    
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        String currency = context.getCurrency();
        
        // Validate currency exists
        if (currency == null || currency.isEmpty()) {
            createException(context, dao, "MISSING_CURRENCY", 
                          "No currency specified");
            return new StepResult(false, "Missing currency");
        }
        
        // Check against master data
        FormRow currencyMaster = loadFormRow(dao, 
            "app_fd_currency_master", currency.toUpperCase());
        
        if (currencyMaster == null) {
            createException(context, dao, "INVALID_CURRENCY",
                          "Currency not found: " + currency);
            return new StepResult(false, "Invalid currency");
        }
        
        // Enrich context with currency details
        context.setAdditionalDataValue("currencyValid", true);
        context.setAdditionalDataValue("currencyName", 
            currencyMaster.getProperty("c_name"));
        
        return new StepResult(true, "Currency validated");
    }
}
```

## Development Workflow

### Adding a New Processing Step

1. **Create the Step Class**:
```java
package com.fiscaladmin.gam.enrichrows.steps;

public class MyNewStep extends AbstractDataStep {
    @Override
    public String getStepName() {
        return "My New Step";
    }
    
    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        // Your processing logic here
        return new StepResult(true, "Processing complete");
    }
}
```

2. **Add to Pipeline**:
```java
// In RowsEnricher.java
DataPipeline pipeline = new DataPipeline(dao)
    .addStep(new CurrencyValidationStep())
    .addStep(new MyNewStep())  // Add your step
    .addStep(new FXConversionStep())
    // ...
```

3. **Test the Step**:
```java
@Test
public void testMyNewStep() {
    DataContext context = new DataContext();
    context.setTransactionId("TEST-001");
    
    FormDataDao mockDao = mock(FormDataDao.class);
    MyNewStep step = new MyNewStep();
    
    StepResult result = step.execute(context, mockDao);
    
    assertTrue(result.isSuccess());
    assertEquals("Expected value", 
        context.getAdditionalDataValue("myField"));
}
```

### Modifying Database Queries

When working with Joget's FormDataDao:

```java
// Query with conditions
String condition = "WHERE c_status = ? AND c_amount > ?";
Object[] params = new Object[]{"new", 1000};
FormRowSet rows = dao.find(formId, tableName, condition, params, 
                          "c_date DESC", false, 0, 100);

// Load single record
FormRow row = dao.load(formId, tableName, recordId);

// Save or update
FormRowSet rowSet = new FormRowSet();
rowSet.add(row);
dao.saveOrUpdate(formId, tableName, rowSet);

// Delete
dao.delete(formId, tableName, new String[]{recordId});
```

## Testing

### Unit Testing Pattern

```java
public class CurrencyValidationStepTest {
    
    private CurrencyValidationStep step;
    private FormDataDao mockDao;
    private DataContext context;
    
    @Before
    public void setUp() {
        step = new CurrencyValidationStep();
        mockDao = mock(FormDataDao.class);
        context = new DataContext();
    }
    
    @Test
    public void testValidCurrency() {
        // Arrange
        context.setCurrency("USD");
        FormRow currencyRow = new FormRow();
        currencyRow.setProperty("c_status", "active");
        
        when(mockDao.load(anyString(), eq("app_fd_currency_master"), eq("USD")))
            .thenReturn(currencyRow);
        
        // Act
        StepResult result = step.execute(context, mockDao);
        
        // Assert
        assertTrue(result.isSuccess());
        assertEquals(Boolean.TRUE, 
            context.getAdditionalDataValue("currencyValid"));
    }
}
```

### Integration Testing

```java
@Test
public void testFullPipeline() {
    // Setup test data
    FormDataDao dao = getTestDao();
    createTestStatement(dao, "STMT-001", "new");
    createTestTransaction(dao, "TRX-001", "STMT-001");
    
    // Execute pipeline
    RowsEnricher enricher = new RowsEnricher();
    Object result = enricher.execute(new HashMap<>());
    
    // Verify results
    FormRow enrichedRow = dao.load(null, "app_fd_trx_enrichment", "TRX-001");
    assertNotNull(enrichedRow);
    assertEquals("enriched", enrichedRow.getProperty("c_status"));
}
```

## Debugging

### Enable Debug Logging

```java
// Add debug statements in your code
import org.joget.commons.util.LogUtil;

LogUtil.info(getClass().getName(), "Processing transaction: " + transactionId);
LogUtil.debug(getClass().getName(), "Context data: " + context.toString());
LogUtil.error(getClass().getName(), e, "Error processing: " + e.getMessage());
```

### Common Debug Points

1. **Check Data Loading**:
```java
// In TransactionDataLoader
LogUtil.info(CLASS_NAME, "Found " + statements.size() + " statements");
LogUtil.info(CLASS_NAME, "Loaded " + transactions.size() + " transactions");
```

2. **Track Pipeline Progress**:
```java
// In each step
LogUtil.info(CLASS_NAME, "Step " + getStepName() + " processing: " + 
            context.getTransactionId());
```

3. **Monitor State Changes**:
```java
// Before and after state updates
LogUtil.info(CLASS_NAME, "Updating statement " + statementId + 
            " from " + oldStatus + " to " + newStatus);
```

### SQL Debugging

Use the provided SQL scripts:
```sql
-- Check processing status
SELECT s.id, s.c_status, COUNT(t.id) as tx_count
FROM app_fd_bank_statement s
LEFT JOIN app_fd_bank_total_trx t ON t.c_statementId = s.id
GROUP BY s.id, s.c_status;

-- Check enrichment results
SELECT * FROM app_fd_trx_enrichment 
WHERE dateCreated > DATE_SUB(NOW(), INTERVAL 1 HOUR)
ORDER BY dateCreated DESC;

-- Check audit trail
SELECT * FROM app_fd_audit_log
WHERE c_transaction_id = 'TRX-001'
ORDER BY c_timestamp;
```

## Common Tasks

### 1. Adding a New Field to Enrichment

```java
// Step 1: Add to DataContext
public class DataContext {
    private String newField;
    // Add getter/setter
}

// Step 2: Set in processing step
context.setNewField(calculatedValue);

// Step 3: Save in persister
enrichedRow.setProperty("c_new_field", context.getNewField());
```

### 2. Adding Exception Handling

```java
protected StepResult performStep(DataContext context, FormDataDao dao) {
    try {
        // Processing logic
    } catch (SpecificException e) {
        // Create exception record
        createException(context, dao, "ERROR_CODE", e.getMessage());
        
        // Create audit log
        createAuditLog(context, dao, "STEP_FAILED", 
                      "Error: " + e.getMessage());
        
        // Return failure
        return new StepResult(false, "Processing failed: " + e.getMessage());
    }
}
```

### 3. Implementing Conditional Logic

```java
@Override
public boolean shouldExecute(DataContext context) {
    // Skip securities transactions for customer identification
    return "BANK".equals(context.getSourceType());
}
```

### 4. Adding Performance Monitoring

```java
protected StepResult performStep(DataContext context, FormDataDao dao) {
    long startTime = System.currentTimeMillis();
    
    try {
        // Processing logic
        
        long elapsed = System.currentTimeMillis() - startTime;
        LogUtil.info(CLASS_NAME, "Step completed in " + elapsed + "ms");
        
        return new StepResult(true, "Success");
    } catch (Exception e) {
        long elapsed = System.currentTimeMillis() - startTime;
        LogUtil.error(CLASS_NAME, e, "Step failed after " + elapsed + "ms");
        return new StepResult(false, e.getMessage());
    }
}
```

## Best Practices

### 1. Error Handling
- Never throw unchecked exceptions from steps
- Always return StepResult with appropriate success flag
- Create exception records for manual review
- Log errors with sufficient context

### 2. State Management
- Always update states atomically
- Use transactions where possible
- Record timestamps for all state changes
- Maintain audit trail

### 3. Performance
- Use batch operations for database queries
- Cache master data within processing batch
- Avoid N+1 query problems
- Process in reasonable batch sizes (100-500 records)

### 4. Code Quality
- Keep steps focused on single responsibility
- Use meaningful variable and method names
- Add JavaDoc comments for public methods
- Write unit tests for each step

### 5. Data Integrity
- Validate all inputs
- Handle null values gracefully
- Preserve original data
- Maintain referential integrity

## Troubleshooting

### Common Issues and Solutions

#### 1. NullPointerException in Steps
**Problem**: Step fails with NPE
**Solution**: 
```java
// Always check for null
String value = context.getProperty();
if (value != null && !value.isEmpty()) {
    // Process value
}

// Use safe getter with default
String value = StringUtils.defaultString(context.getProperty(), "");
```

#### 2. Transaction Not Found
**Problem**: Cannot load transaction from database
**Solution**:
```java
// Check table name includes prefix
String tableName = "app_fd_bank_total_trx"; // Include app_fd_ prefix

// Verify ID format
FormRow row = dao.load(null, tableName, transactionId);
if (row == null) {
    LogUtil.warn(CLASS_NAME, "Transaction not found: " + transactionId);
}
```

#### 3. State Not Updating
**Problem**: Status field not updating in database
**Solution**:
```java
// Ensure using correct field name
row.setProperty("c_status", "processed"); // Note the c_ prefix

// Always use saveOrUpdate, not just save
FormRowSet rowSet = new FormRowSet();
rowSet.add(row);
dao.saveOrUpdate(null, tableName, rowSet);
```

#### 4. Pipeline Stops Unexpectedly
**Problem**: Pipeline stops after first error
**Solution**:
```java
// Set pipeline to continue on error
DataPipeline pipeline = new DataPipeline(dao)
    .addStep(new Step1())
    .addStep(new Step2())
    .setStopOnError(false); // Continue processing on errors
```

#### 5. Memory Issues with Large Batches
**Problem**: OutOfMemoryError with large datasets
**Solution**:
```java
// Process in smaller batches
int batchSize = 100;
for (int i = 0; i < allTransactions.size(); i += batchSize) {
    List<DataContext> batch = allTransactions.subList(i, 
        Math.min(i + batchSize, allTransactions.size()));
    BatchPipelineResult result = pipeline.executeBatch(batch);
    // Process results
}
```

### Debug Checklist

When debugging issues:

1. ✓ Check Joget logs: `[JOGET_HOME]/wflow/logs/`
2. ✓ Verify database connectivity
3. ✓ Confirm table names include `app_fd_` prefix
4. ✓ Check field names include `c_` prefix
5. ✓ Verify master data is populated
6. ✓ Check user permissions in Joget
7. ✓ Review audit_log table for processing history
8. ✓ Verify OSGi bundle is activated
9. ✓ Check for duplicate plugin versions
10. ✓ Review exception_queue for errors

## Appendix: Quick Reference

### Important Tables
```sql
app_fd_bank_statement      -- F01.00 Statements
app_fd_bank_total_trx      -- F01.03 Bank transactions
app_fd_secu_total_trx      -- F01.04 Securities transactions
app_fd_trx_enrichment      -- Enriched output
app_fd_exception_queue     -- Exceptions for review
app_fd_audit_log          -- Processing audit trail
app_fd_currency_master    -- Currency reference
app_fd_counterparty_master -- Counterparty reference
app_fd_customer           -- Customer master
app_fd_cp_txn_mapping     -- F14 rules
app_fd_fx_rates_eur       -- Exchange rates
```

### Key Constants
```java
// Source types
DomainConstants.SOURCE_TYPE_BANK = "BANK"
DomainConstants.SOURCE_TYPE_SECU = "SECU"

// Statuses
FrameworkConstants.STATUS_NEW = "new"
FrameworkConstants.STATUS_PROCESSING = "processing"
FrameworkConstants.STATUS_ENRICHED = "enriched"

// Special values
FrameworkConstants.ENTITY_UNKNOWN = "UNKNOWN"
FrameworkConstants.INTERNAL_TYPE_UNMATCHED = "UNMATCHED"
```

### Maven Commands
```bash
mvn clean package          # Build plugin
mvn test                   # Run tests
mvn clean package -DskipTests # Build without tests
```

---

*This guide is part of the GAM Rows Enrichment Plugin documentation. For business logic details, see BUSINESS_LOGIC.md. For step development specifics, see STEP_DEVELOPMENT_GUIDE.md.*