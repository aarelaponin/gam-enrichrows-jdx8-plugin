# GAM Rows Enrichment Plugin

A Joget DX8 plugin that implements a data processing pipeline for financial transaction enrichment. The system processes bank and securities transactions through validation and enrichment steps to prepare them for General Ledger posting.

## Overview

This plugin processes transactions from bank statements (F01.00) and their associated rows (F01.03 for bank transactions, F01.04 for securities transactions) through a series of enrichment steps:

1. **Data Loading** - Loads unprocessed transactions from `bank_total_trx` and `secu_total_trx`
2. **Currency Validation** - Validates against `currency_master`
3. **FX Conversion** - Converts to EUR using `fx_rates_eur`
4. **Customer Identification** - Identifies customers from `customer` table (bank transactions only)
5. **Counterparty Determination** - Identifies counterparties using BIC codes from `counterparty_master`
6. **F14 Rule Mapping** - Maps transaction types using rules from `cp_txn_mapping`
7. **Data Persistence** - Saves to `trx_enrichment` table with state management

## Architecture

### Core Components

#### Framework (`com.fiscaladmin.gam.enrichrows.framework`)
- **DataPipeline** - Orchestrates step execution
- **AbstractDataStep** - Base class for all processing steps with helper methods
- **DataContext** - Data container passed between steps
- **StepResult/PipelineResult** - Result objects for tracking processing status
- **BatchPersistenceResult** - Tracks batch persistence and statement status

#### Processing Steps (`com.fiscaladmin.gam.enrichrows.steps`)
- **CurrencyValidationStep** - Validates transaction currencies
- **FXConversionStep** - Converts amounts to EUR
- **CustomerIdentificationStep** - Identifies customers for bank transactions
- **CounterpartyDeterminationStep** - Determines counterparties from BIC codes
- **F14RuleMappingStep** - Maps transactions to F14 internal types

#### Data Management
- **TransactionDataLoader** - Loads transactions and marks statements as "processing"
- **EnrichmentDataPersister** - Persists enriched data and manages state

### State Management

The plugin implements comprehensive state management:

**Statement States:**
- `new` → `processing` → `processed` or `processed_with_errors`

**Transaction States:**
- `new` → `enriched`

**Enrichment Records:**
- Saved with `processing_status`: `enriched` or `manual_review`

## Database Schema

### Source Tables
- `bank_statement` - Bank account statements (F01.00)
- `bank_total_trx` - Bank transaction rows (F01.03)
- `secu_total_trx` - Securities transaction rows (F01.04)

### Master Data
- `customer` - Customer master records
- `counterparty_master` - Counterparty/bank information
- `currency_master` - Valid currencies
- `cp_txn_mapping` - F14 transaction type mapping rules
- `fx_rates_eur` - Exchange rates to EUR

### Output Tables
- `trx_enrichment` - Enriched transaction output
- `exception_queue` - Exceptions requiring manual review
- `audit_log` - Processing audit trail

## Building

```bash
# Build the plugin JAR
mvn clean package

# Build without running tests
mvn clean package -DskipTests

# Run tests only
mvn test
```

## Deployment

```bash
# Copy to your Joget installation
cp target/rows-enrichment-8.1-SNAPSHOT.jar /path/to/joget/wflow/app_plugins/

# The plugin will be automatically loaded by Joget's OSGi container
```

## Development

### Adding New Processing Steps

1. Create a class extending `AbstractDataStep` in `com.fiscaladmin.gam.enrichrows.steps`
2. Implement `performStep()` and `getStepName()`
3. Add to pipeline in `RowsEnricher`:

```java
DataPipeline pipeline = new DataPipeline(formDataDao)
    .addStep(new YourNewStep())
    // ... other steps
```

### Architectural Principles

- **Steps**: Pure transformation functions, no side effects
- **Persisters**: Handle both data persistence AND state management
- **Clear separation**: Enrichment logic vs persistence logic

## Troubleshooting

### Common SQL Queries

```sql
-- Check unprocessed transactions
SELECT COUNT(*) FROM bank_total_trx WHERE c_status = 'new';
SELECT COUNT(*) FROM secu_total_trx WHERE c_status = 'new';

-- Check F14 rule configuration
SELECT * FROM app_fd_cp_txn_mapping WHERE c_status = 'active';

-- Check enrichment results
SELECT * FROM app_fd_trx_enrichment ORDER BY dateCreated DESC LIMIT 10;

-- Check statement processing status
SELECT id, c_status, processing_started, processing_completed 
FROM app_fd_bank_statement 
ORDER BY dateCreated DESC;
```

### Debug Mode

For detailed logging, enable debug JAR with extensive logging (see DEBUG_GUIDE.md)

## Configuration

The plugin uses minimal configuration, mainly relying on database master data. Key constants are defined in:

- `FrameworkConstants` - Framework-level constants
- `DomainConstants` - Domain-specific constants

## Testing

The plugin includes:
- Unit tests using JUnit 4 with mocked FormDataDao
- Integration tests against actual Joget database
- Test individual steps by mocking DataContext and FormDataDao

## License

MIT

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## Support

For issues and questions, please [open an issue](https://github.com/yourusername/gam-plugins/issues) on GitHub.