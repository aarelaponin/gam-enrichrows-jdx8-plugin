# Transaction Processing Business Logic Documentation

## Overview
This document describes the business logic implemented in the GL Transaction Processing pipeline. The pipeline processes financial transactions through a series of enrichment steps, each applying specific business rules to validate, identify, and transform transaction data for General Ledger posting.

## Processing Pipeline Architecture

The pipeline processes transactions through the following sequential steps:
1. Data Loading (from source systems)
2. Currency Validation
3. Counterparty Determination
4. F14 Rule Mapping (Transaction Classification)
5. Customer Identification
6. FX Conversion
7. Data Persistence (to enrichment store)

Each step enriches the transaction context with additional data while validating business rules and creating exceptions for manual review when necessary.

## Step-by-Step Business Logic

### Step 1: Data Loading
**Purpose**: Load unprocessed transactions from source systems (bank and securities)

**Business Logic**:
- Identifies all statements with status = "new"
- Loads associated transactions (bank or securities) with status = "new"
- Preserves source system relationships (statement → transactions)
- Sorts transactions by date for chronological processing

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

### Step 3: Counterparty Determination
**File**: `CounterpartyDeterminationStep.java`  
**Purpose**: Identify the other party involved in each transaction

#### Business Rules by Transaction Type

##### Bank Transactions
1. **Primary Logic**: Use the other side's BIC code
   - Look up counterparty by `other_side_bic` field
   - Match against counterparty master table
   - Verify counterparty status = "active"

2. **Fallback Logic**: 
   - If no counterparty found, check if bank exists in bank master
   - Create exception for missing counterparty mapping
   - Set counterparty to "UNKNOWN" to allow continued processing

##### Securities Transactions
1. **Primary Logic**: Use statement bank as counterparty
   - Statement bank acts as custodian or broker
   - Determine type based on transaction:
     - Buy/Sell/Trade → Broker
     - Custody/Dividend/Corporate Action → Custodian

2. **Type Classification**:
   - BANK: Traditional banking counterparty
   - BROKER: Securities trading counterparty
   - CUSTODIAN: Securities custody counterparty

#### Data Enrichment
- Counterparty ID, BIC, name, type
- Counterparty short code for GL construction
- Relationship classification

---

### Step 4: F14 Rule Mapping
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

### Step 5: Customer Identification
**File**: `CustomerIdentificationStep.java`  
**Purpose**: Identify the customer associated with each transaction using multiple methods

#### Identification Methods (in order of preference)

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

### Step 6: FX Conversion
**File**: `FXConversionStep.java`  
**Purpose**: Convert foreign currency amounts to EUR base currency

#### Conversion Logic

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

### Step 7: Data Persistence
**Purpose**: Save enriched transaction data for GL posting

#### Persistence Logic
1. **Enrichment Record Creation**:
   - Generate unique enrichment ID
   - Store all original transaction data
   - Add all enrichment data from steps
   - Calculate processing metadata
   - Initialize `pairing_status` field to "pending"

2. **Status Determination**:
   - **ENRICHED**: All steps successful, ready for GL posting
   - **MANUAL_REVIEW**: Exceptions or low confidence require review
   - **FAILED**: Critical errors prevent processing

3. **Pairing Status**:
   - **pending**: Initial status for all new enriched transactions
   - Indicates transaction is awaiting pairing process
   - Will be updated by downstream pairing workflow

4. **Audit Trail**:
   - Record all processing steps completed
   - Store timestamps for each enrichment
   - Link to original source transactions

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
1. Load Transactions
   ↓
2. Validate Currency → Exception if invalid
   ↓
3. Determine Counterparty → Set UNKNOWN if not found
   ↓
4. Apply F14 Rules → Set UNMATCHED if no rule
   ↓
5. Identify Customer → Set UNKNOWN if not found
   ↓
6. Convert to EUR → Exception if no FX rate
   ↓
7. Persist Enrichment → Save all data
   ↓
8. Update Status → Mark source as processed
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