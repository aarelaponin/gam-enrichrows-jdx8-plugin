# Transaction Processing Business Logic Documentation

## Overview
This document describes the business logic implemented in the GL Transaction Processing pipeline. The pipeline processes financial transactions through a series of enrichment steps, each applying specific business rules to validate, identify, and transform transaction data for General Ledger posting.

## Processing Pipeline Architecture

The pipeline processes transactions through the following sequential steps:
1. **Data Loading** - Load transactions and mark statements as "processing"
2. **Currency Validation** - Validate currency codes against master data
3. **FX Conversion** - Convert foreign currencies to EUR base currency
4. **Customer Identification** - Identify customer (bank transactions only)
5. **Counterparty Determination** - Identify the other party in transactions
6. **F14 Rule Mapping** - Classify transactions using business rules
7. **Data Persistence** - Save enriched data and update all statuses

Each step enriches the transaction context with additional data while validating business rules and creating exceptions for manual review when necessary.

## State Management Model

The system implements comprehensive state tracking at three levels:

### Statement Level States
- **new**: Unprocessed statement awaiting enrichment
- **processing**: Currently being processed by the pipeline
- **processed**: All transactions successfully enriched
- **processed_with_errors**: Some transactions failed enrichment

### Transaction Level States
- **new**: Unprocessed transaction
- **enriched**: Successfully processed and stored in enrichment table
- **failed**: Could not be enriched due to errors

### Enrichment Record Status
- **enriched**: Ready for GL posting
- **manual_review**: Requires human intervention
- **failed**: Critical errors prevent processing

## Step-by-Step Business Logic

### Step 1: Data Loading
**Class**: `TransactionDataLoader`  
**Purpose**: Load unprocessed transactions from source systems with state management

**Business Logic**:
- Identifies all statements with status = "new"
- **Marks each statement as "processing"** when loading begins
- Records `processing_started` timestamp
- Loads associated transactions (bank or securities) with status = "new"
- Preserves source system relationships (statement → transactions)
- Groups transactions by statement for batch processing
- Sorts transactions by date for chronological processing

**State Transitions**:
- Statement: `new` → `processing`
- Transactions: remain `new` (will be updated after enrichment)

---

### Step 2: Currency Validation
**File**: `CurrencyValidationStep.java`  
**Purpose**: Ensure all transaction currencies are valid and active in the system

#### Business Rules
1. **Currency Validation Hierarchy**:
   - Currency code must be present (not null/empty)
   - Currency must exist in currency master table
   - Currency must have status = "active"
   - Currency code is normalized to uppercase

2. **Exception Priority Logic**:
   - Critical Priority: Transaction amount ≥ €1,000,000
   - High Priority: Transaction amount ≥ €100,000
   - Medium Priority: Transaction amount ≥ €10,000
   - Low Priority: Transaction amount < €10,000

3. **Data Enrichment**:
   - Adds currency name, symbol, decimal places to context
   - Stores validation timestamp
   - Creates audit trail entry

#### Error Handling
- **MISSING_CURRENCY**: Transaction has no currency specified → Create exception, fail transaction
- **INVALID_CURRENCY**: Currency not found in master data → Create exception, fail transaction
- **INACTIVE_CURRENCY**: Currency exists but not active → Create exception, fail transaction

---

### Step 3: FX Conversion (Moved Earlier in Pipeline)
**File**: `FXConversionStep.java`  
**Purpose**: Convert foreign currency amounts to EUR base currency early in the pipeline

#### Why FX Conversion Comes Early
- Ensures all downstream steps work with consistent EUR amounts
- Allows priority calculations to use actual EUR values
- Simplifies amount-based business rules in later steps

#### Conversion Logic
(See detailed FX conversion logic in Step 6 section below)

---

### Step 4: Customer Identification
**File**: `CustomerIdentificationStep.java`  
**Purpose**: Identify the customer associated with bank transactions

#### Important Note
- **Only executes for bank transactions** (`SOURCE_TYPE_BANK`)
- **Skips securities transactions** as they represent bank portfolio operations
- Securities transactions don't have individual customers

#### Identification Methods
(See detailed customer identification logic in Step 5 section below)

---

### Step 5: Counterparty Determination
**File**: `CounterpartyDeterminationStep.java`  
**Purpose**: Identify the other party involved in each transaction

#### Unified Logic for Both Transaction Types

**Current Implementation**: Both bank and securities transactions use the statement's bank as the primary counterparty
- Rationale: The statement bank is always involved as a party in the transaction
- This ensures consistent counterparty identification

#### Processing Logic

1. **Primary Counterparty**: Always set to statement's bank
   ```java
   counterpartyId = statementBank (e.g., "BANK001")
   counterpartyBic = bank's BIC code
   counterpartyName = bank's full name
   ```

2. **Additional Data Capture**:
   - For bank transactions with `other_side_bic`:
     - Store in additional data for reference
     - Could be used for future reconciliation
   - Does not create exceptions for missing BIC lookups

3. **Type Classification**:
   - BANK: Traditional banking counterparty (default)
   - BROKER: Securities trading counterparty (based on transaction type)
   - CUSTODIAN: Securities custody counterparty (based on transaction type)

#### Data Enrichment
- Counterparty ID (always the statement bank)
- Counterparty BIC and name
- Counterparty type classification
- Other side BIC stored as additional data (when available)

---

### Step 6: F14 Rule Mapping
**File**: `F14RuleMappingStep.java`  
**Purpose**: Map external transaction codes to standardized internal types for consistent GL posting

#### Rule Evaluation Process

1. **Rule Loading Priority**:
   ```
   1. Counterparty-specific rules (highest priority)
   2. SYSTEM rules (universal rules)
   3. Sort by priority value (lower number = higher priority)
   ```

2. **Rule Structure**:
   - Each rule contains conditions and target internal type
   - Supports single field or multi-field conditions
   - First matching rule wins (stops evaluation)

3. **Supported Operators**:
   - `equals`: Exact string match
   - `contains`: Substring search  
   - `startswith`: Beginning of string match
   - `endswith`: End of string match
   - `regex`: Regular expression pattern
   - `in`: Match against comma-separated list
   - `>`, `<`, `>=`, `<=`: Numeric comparisons

4. **Complex Logic Support**:
   - AND conditions: All must match
   - OR conditions: Any can match
   - Nested expressions with parentheses
   - Arithmetic operations in conditions

#### Example Rules

```
Rule 1 (Priority 10): 
  IF type = "SWIFT" AND description contains "DIVIDEND"
  THEN internal_type = "INCOME_DIVIDEND"

Rule 2 (Priority 20):
  IF amount > 1000000 AND currency = "USD"
  THEN internal_type = "LARGE_TRANSFER"

Rule 3 (Priority 100):
  IF description matches regex ".*REF:\d{6}.*"
  THEN internal_type = "REFERENCE_PAYMENT"
```

#### Error Handling
- **NO_F14_RULES**: No rules configured → Set to "UNMATCHED", create exception
- **NO_RULE_MATCH**: Rules exist but none matched → Set to "UNMATCHED", create exception

---

### Detailed Step Logic

#### Customer Identification (Step 4 Detail)
**File**: `CustomerIdentificationStep.java`  
**Purpose**: Identify the customer associated with bank transactions using multiple methods

##### Identification Methods (in order of preference)

1. **Direct Customer ID** (100% confidence)
   - Check if `customer_id` field contains valid customer ID
   - Format: CUST-XXXXXX or direct lookup by:
     - Registration number
     - Personal ID
     - Tax ID

2. **Account Number Mapping** (95% confidence)
   - Match transaction account to customer account mapping
   - Check dedicated account mapping table
   - Verify account ownership in customer master

3. **Registration Number Extraction** (90% confidence)
   - Extract registration numbers from text fields
   - Pattern recognition in reference/description
   - Formats supported:
     - "REG:12345", "REGNUM:12345"
     - "REG-12345", "REG.12345"
     - Natural language: "registration number 12345"

4. **Name Pattern Matching** (70% confidence)
   - Fuzzy matching against customer names
   - Minimum 70% similarity required
   - Name must be at least 5 characters
   - Checks multiple name fields:
     - Legal name
     - Trading name
     - Short name

#### Business Rules
- **Confidence Threshold**: Confidence < 80% triggers manual review
- **Active Status**: Only match active customers
- **Unknown Customer**: Set to "UNKNOWN" if no match found
- **Manual Review**: Create exception for low confidence or unknown

#### Data Enrichment
- Customer ID, name, code, type
- Customer attributes and classifications
- Identification method used
- Confidence score (0-100)

---

#### FX Conversion (Step 3 Detail)
**File**: `FXConversionStep.java`  
**Purpose**: Convert foreign currency amounts to EUR base currency

##### Conversion Logic

1. **EUR Transactions**:
   - No conversion needed
   - Base amount = original amount
   - FX rate = 1.0

2. **Foreign Currency Transactions**:

   a. **Rate Date Determination**:
   ```
   Securities transactions:
     - Use trade date for all securities
   
   Bank transactions:
     - Dividends/Income: Use payment date
     - All others: Use payment date
   ```

   b. **Rate Lookup Process**:
   ```
   1. Try exact date match
   2. If not found, get most recent rate within 5 days
   3. If no recent rate, create exception
   ```

   c. **Rate Application**:
   ```
   Table stores: 1 EUR = X foreign currency
   
   Conversion formula:
   - Foreign to EUR: amount_EUR = amount_foreign / fx_rate
   - EUR to Foreign: amount_foreign = amount_EUR * fx_rate
   ```

3. **Rate Age Validation**:
   - Ideal: Same day rate
   - Acceptable: Within 3 days
   - Warning: 3-5 days old
   - Error: More than 5 days old

#### Business Rules
- **Rate Types**: Support for BID, ASK, MID rates
- **Rate Source**: Central bank rates preferred
- **Fallback Logic**: Use most recent valid rate
- **Audit Trail**: Record rate used, date, and source

#### Error Handling
- **MISSING_CURRENCY**: No currency specified → Fail conversion
- **FX_RATE_MISSING**: No valid rate found → Create exception
- **OLD_FX_RATE**: Rate older than preferred → Create warning

---

### Step 7: Data Persistence with State Management
**Class**: `EnrichmentDataPersister`  
**Purpose**: Save enriched transaction data and manage all state transitions

#### Batch Persistence Logic

1. **Transaction Grouping**:
   - Groups all transactions by statement ID
   - Processes each statement's transactions together
   - Tracks success/failure counts per statement

2. **Enrichment Record Creation**:
   - Generate unique enrichment ID (format: `TRX-XXXXXX`)
   - Store all original transaction data
   - Add all enrichment data from all steps
   - Calculate processing metadata
   - Initialize `pairing_status` field to "pending"

3. **State Management Flow**:
   ```
   For each successfully enriched transaction:
   1. Save to trx_enrichment table
   2. Update source transaction status: new → enriched
   3. Record enrichment_date timestamp
   
   After all transactions in statement:
   1. Calculate success/failure counts
   2. Update statement status:
      - All successful: processing → processed
      - Some failures: processing → processed_with_errors
   3. Record processing_completed timestamp
   4. Store transaction counts in statement record
   ```

4. **Status Determination**:
   - **ENRICHED**: All steps successful, ready for GL posting
   - **MANUAL_REVIEW**: Triggered by:
     - Unknown customer or counterparty
     - Unmatched F14 classification
     - Customer confidence < 80%
   - **FAILED**: Critical errors prevent processing

5. **Comprehensive Audit Trail**:
   - Transaction-level audit entries
   - Statement-level completion audit
   - All timestamps and status changes recorded
   - Links preserved between all related records

#### State Transition Summary
```
Statement:  new → processing → processed/processed_with_errors
Transaction: new → enriched
Enrichment: (created with status based on validation results)
```

---

## Exception Management Framework

### Exception Priority Levels
1. **CRITICAL**: Immediate attention required
   - Amount > €1,000,000
   - System integrity issues
   - Due: Same day

2. **HIGH**: Urgent processing needed
   - Amount > €100,000
   - Missing critical data
   - Due: Next business day

3. **MEDIUM**: Standard processing
   - Amount > €10,000
   - Data quality issues
   - Due: 2 business days

4. **LOW**: Routine review
   - Amount < €10,000
   - Minor discrepancies
   - Due: 5 business days

### Exception Assignment Rules
- Critical/High → Supervisor review
- Medium → Senior processor
- Low → Standard processor
- System errors → IT support

---

## Master Data Dependencies

### Required Master Tables
1. **Currency Master** (`currency_master`)
   - Currency codes, names, active status
   - Decimal places, symbols

2. **Counterparty Master** (`counterparty_master`)
   - Counterparty IDs, BICs, names
   - Types, relationships, active status

3. **Customer Master** (`customer`)
   - Customer IDs, names, registration numbers
   - Account mappings, active status

4. **F14 Rule Mappings** (`cp_txn_mapping`)
   - Transaction classification rules
   - Priority ordering, conditions

5. **FX Rates** (`fx_rates_eur`)
   - Daily exchange rates to EUR
   - Rate types, sources

### Data Quality Requirements
- All master data must be current
- Active/inactive status properly maintained
- Unique identifiers (BIC, customer ID) must be unique
- FX rates updated daily

---

## Business Process Flow

```
1. Load Transactions & Mark Statements as "processing"
   ↓
2. Validate Currency → Exception if invalid
   ↓
3. Convert to EUR → Exception if no FX rate
   ↓
4. Identify Customer (Bank only) → Set UNKNOWN if not found
   ↓
5. Determine Counterparty → Use statement bank
   ↓
6. Apply F14 Rules → Set UNMATCHED if no rule
   ↓
7. Persist Enrichment → Save all data with state management
   ↓
8. Update All Statuses:
   - Transactions: new → enriched
   - Statements: processing → processed/processed_with_errors
```

---

## Configuration Considerations

### Performance Tuning
- Batch size: 100-500 transactions recommended
- Parallel processing: Not currently supported
- Caching: Master data cached per batch

### Error Handling Modes
- **Stop on Error**: Halt batch on first error (development)
- **Continue on Error**: Process all, report errors (production)

### Monitoring Points
- Transactions processed per batch
- Exception rate by type
- Processing time per step
- Master data hit rates

---

## Compliance and Audit

### Regulatory Requirements
- Full audit trail for all transactions
- Immutable exception records
- Timestamp all processing steps
- Preserve original transaction data

### Data Retention
- Enrichment records: 7 years
- Exception records: 7 years  
- Audit logs: 10 years
- Master data history: 10 years

---

## Appendix: Internal Type Codes

Common internal types mapped by F14 rules:
- `PAYMENT_CUSTOMER`: Customer payment
- `PAYMENT_VENDOR`: Vendor payment
- `INCOME_DIVIDEND`: Dividend income
- `INCOME_INTEREST`: Interest income
- `FEE_BANK`: Banking fees
- `FEE_CUSTODY`: Custody fees
- `TRADE_BUY`: Securities purchase
- `TRADE_SELL`: Securities sale
- `TRANSFER_INTERNAL`: Internal transfer
- `TRANSFER_EXTERNAL`: External transfer
- `UNMATCHED`: No rule matched

---

*This document represents the complete business logic as implemented in the GL Transaction Processing pipeline version 8.1-SNAPSHOT*