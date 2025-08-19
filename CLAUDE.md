# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Development Commands

### Maven Build Commands
```bash
# Build the plugin JAR
mvn clean package

# Build without running tests
mvn clean package -DskipTests

# Run tests only
mvn test

# Run a single test class
mvn test -Dtest=YourTestClass

# Run integration tests
mvn integration-test

# Clean build artifacts
mvn clean
```

### Deployment
```bash
# Deploy to local Joget (replace path with your Joget installation)
cp target/gl-postings-8.1-SNAPSHOT.jar /path/to/joget/wflow/app_plugins/

# The plugin will be automatically loaded by Joget's OSGi container
```

## Architecture Overview

This is a Joget DX8 plugin that implements a data processing pipeline for financial transaction enrichment. The system processes bank and securities transactions through validation and enrichment steps to prepare them for General Ledger posting.

### Core Pipeline Architecture

The processing pipeline consists of:
1. **Data Loading** - Loads unprocessed transactions from `bank_total_trx` and `secu_total_trx`
2. **Currency Validation** - Validates against `currency_master`
3. **Counterparty Determination** - Identifies counterparties using BIC codes from `counterparty_master`
4. **F14 Rule Mapping** - Maps transaction types using rules from `cp_txn_mapping`
5. **Customer Identification** - Identifies customers from `customer` table
6. **FX Conversion** - Converts to EUR using `fx_rates_eur`
7. **Data Persistence** - Saves to `trx_enrichment` table

### Framework Components

The reusable framework in `com.fiscaladmin.gam.enrichrows.framework` provides:
- **DataPipeline** - Orchestrates step execution
- **AbstractDataStep** - Base class for all processing steps with helper methods
- **DataContext** - Data container passed between steps
- **StepResult/PipelineResult** - Result objects for tracking processing status

### Key Database Tables

**Source Data:**
- `bank_statement`, `bank_total_trx`, `secu_total_trx` - Transaction sources

**Master Data:**
- `customer` - Customer master records
- `counterparty_master` - Counterparty/bank information
- `currency_master` - Valid currencies
- `cp_txn_mapping` - F14 transaction type mapping rules
- `fx_rates_eur` - Exchange rates to EUR

**Processing:**
- `trx_enrichment` - Enriched transaction output
- `exception_queue` - Exceptions requiring manual review
- `audit_log` - Processing audit trail

### Plugin Entry Points

- **Activator**: `com.fiscaladmin.gam.Activator` - OSGi bundle activator
- **Main Processor**: `com.fiscaladmin.gam.enrichrows.lib.RowsEnricher` - Plugin execution entry
- **Processing Steps**: `com.fiscaladmin.gam.enrichrows.steps.*` - Individual pipeline steps

### Adding New Processing Steps

To add a new step:
1. Create a class extending `AbstractDataStep` in `com.fiscaladmin.gam.enrichrows.steps`
2. Implement `performStep()` and `getStepName()`
3. Add to pipeline in `TransactionProcessor` using `.addStep(new YourStep())`

### Common Development Tasks

**Debugging F14 Rule Issues:**
- Check `cp_txn_mapping` table for rule configuration
- Verify `counterpartyId` values match between `counterparty_master` and `cp_txn_mapping`
- Use debug JAR with extensive logging (see DEBUG_GUIDE.md)

**Database Queries for Troubleshooting:**
```sql
-- Check unprocessed transactions
SELECT COUNT(*) FROM bank_total_trx WHERE c_status = 'new';
SELECT COUNT(*) FROM secu_total_trx WHERE c_status = 'new';

-- Check F14 rule configuration
SELECT * FROM app_fd_cp_txn_mapping WHERE c_status = 'active';

-- Check enrichment results
SELECT * FROM app_fd_trx_enrichment ORDER BY dateCreated DESC LIMIT 10;
```

### Constants and Configuration

- Framework constants: `com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants`
- Domain constants: `com.fiscaladmin.gam.enrichrows.constants.DomainConstants`
- Plugin configuration: Minimal, mainly uses database master data

### Testing Approach

- Unit tests use JUnit 4 with mocked FormDataDao
- Integration tests run against actual Joget database
- Test individual steps by mocking DataContext and FormDataDao
- Use SQL scripts in root directory for database verification