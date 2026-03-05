# rows-enrichment Plugin — Detailed Specification

**Version:** 2.0 (aligned with F01.05 v2)
**Date:** 2 March 2026
**Status:** SPECIFICATION ONLY — no implementation

---

## 1. Purpose

The rows-enrichment plugin transforms raw imported statement transactions (F01.03 bank rows, F01.04 securities rows) into fully enriched records stored in F01.05 (trxEnrichment). Each enrichment record contains resolved entity references (customer, counterparty, asset), validated and converted currency amounts, and a classified internal transaction type — everything the downstream gl-preparator plugin needs to construct GL postings.

## 2. Scope

This specification covers the complete plugin: data loading, a 6-step processing pipeline (was 5, adding AssetResolutionStep), data persistence, error handling, audit logging, and configuration. It is aligned with the reorganised F01.05 form definition and the MDM design document v3.0.

### 2.1 gam-framework Dependency

The rows-enrichment plugin **must** depend on `gam-framework` (com.fiscaladmin.gam:gam-framework:8.1-SNAPSHOT) for all status lifecycle management. This is a mandatory architectural constraint — no plugin may implement its own status transition logic.

**Key framework classes used:**

| Class | Package | Purpose |
|---|---|---|
| `Status` | `com.fiscaladmin.gam.framework.status` | Enum with 28 status constants. **All status values must use this enum** — no string literals for statuses anywhere in the codebase |
| `StatusManager` | `com.fiscaladmin.gam.framework.status` | Centralised state machine. **All status transitions must go through StatusManager.transition()** — never update status field directly |
| `EntityType` | `com.fiscaladmin.gam.framework.status` | Enum mapping entity types to Joget table names. Relevant types: STATEMENT, BANK_TRX, SECU_TRX, ENRICHMENT, EXCEPTION |
| `InvalidTransitionException` | `com.fiscaladmin.gam.framework.status` | Checked exception thrown on invalid transitions — must be caught and handled |
| `TransitionAuditEntry` | `com.fiscaladmin.gam.framework.status` | Immutable DTO for audit records — written automatically by StatusManager |

**Integration principles:**

1. **Never write status directly.** Replace `row.setProperty("status", "enriched")` with `statusManager.transition(dao, entityType, recordId, Status.ENRICHED, "rows-enrichment", reason)`.
2. **Never use string literals for statuses.** Replace `"new"`, `"enriched"`, `"manual_review"` with `Status.NEW.getCode()`, `Status.ENRICHED.getCode()`, `Status.MANUAL_REVIEW.getCode()`.
3. **Use the custom table name overload for ENRICHMENT.** Because `EntityType.ENRICHMENT.getTableName()` returns `"trx_enrichment"` but the actual form table is `"trxEnrichment"`, always use: `statusManager.transition(dao, "trxEnrichment", EntityType.ENRICHMENT, recordId, targetStatus, triggeredBy, reason)`.
4. **Handle InvalidTransitionException.** If a transition fails (e.g. record is already in a terminal state), catch the exception, log it, and create an exception queue entry.
5. **Audit is automatic.** Every `StatusManager.transition()` call writes a `TransitionAuditEntry` to `audit_log` with entity_type, entity_id, from_status, to_status, triggered_by, reason, timestamp. No additional audit code needed for status changes.

**Status values used by this plugin (subset of 28):**

| Status Enum | Code | Used For |
|---|---|---|
| `Status.NEW` | "new" | Initial state of statements, source transactions, enrichment records |
| `Status.PROCESSING` | "processing" | Source transaction being enriched |
| `Status.ENRICHED` | "enriched" | Successfully enriched (all steps passed, no manual review needed) |
| `Status.MANUAL_REVIEW` | "manual_review" | Enriched but needs human review (UNKNOWN entities, low confidence, etc.) |
| `Status.ERROR` | "error" | Pipeline error (step failure) |
| `Status.CONSOLIDATED` | "consolidated" | Query filter for statements ready for enrichment (read-only — this plugin does NOT set this status) |
| `Status.OPEN` | "open" | Initial state for exception queue entries |

**Valid transitions this plugin will perform:**

| Entity | From | To | When |
|---|---|---|---|
| STATEMENT | `CONSOLIDATED` | `ENRICHED` | All statement transactions enriched successfully |
| STATEMENT | `CONSOLIDATED` | `ERROR` | One or more transactions failed |
| BANK_TRX | `NEW` | `PROCESSING` | Transaction enters pipeline |
| SECU_TRX | `NEW` | `PROCESSING` | Transaction enters pipeline |
| BANK_TRX | `PROCESSING` | `ENRICHED` | All steps passed, no manual review needed |
| BANK_TRX | `PROCESSING` | `MANUAL_REVIEW` | Enriched but needs human review |
| BANK_TRX | `PROCESSING` | `ERROR` | Pipeline step failure |
| SECU_TRX | `PROCESSING` | `ENRICHED` | All steps passed, no manual review needed |
| SECU_TRX | `PROCESSING` | `MANUAL_REVIEW` | Enriched but needs human review |
| SECU_TRX | `PROCESSING` | `ERROR` | Pipeline step failure |
| ENRICHMENT | *(null)* | `NEW` | New F01.05 record created |
| ENRICHMENT | `NEW` | `PROCESSING` | Pipeline processing the record |
| ENRICHMENT | `PROCESSING` | `ENRICHED` | Successfully enriched |
| ENRICHMENT | `PROCESSING` | `MANUAL_REVIEW` | Needs human review |
| ENRICHMENT | `PROCESSING` | `ERROR` | Pipeline error |

*Note: The full STATEMENT lifecycle is: NEW → IMPORTING → IMPORTED → CONSOLIDATING → CONSOLIDATED → ENRICHED → POSTED. The statement-importer plugin handles NEW through CONSOLIDATED. This plugin (rows-enrichment) handles CONSOLIDATED → ENRICHED/ERROR. The gl-preparator handles ENRICHED → POSTED.

**pom.xml dependency to add:**

```xml
<dependency>
    <groupId>com.fiscaladmin.gam</groupId>
    <artifactId>gam-framework</artifactId>
    <version>8.1-SNAPSHOT</version>
</dependency>
```

---

## 3. Data Flow Overview

```
F01.00 (statement, Status.CONSOLIDATED)     -- input: statements already imported & consolidated
  |
  +--> F01.03 (bank_total_trx, Status.NEW)    -- source for bank transactions
  +--> F01.04 (secu_total_trx, Status.NEW)     -- source for secu transactions
  |
  v
TransactionDataLoader
  |  StatusManager: BANK_TRX/SECU_TRX NEW → PROCESSING
  |  loads unprocessed rows, creates DataContext per row
  v
DataPipeline (6 steps, sequential per transaction)
  |
  |  Step 1: CurrencyValidationStep
  |  Step 2: CounterpartyDeterminationStep
  |  Step 3: CustomerIdentificationStep
  |  Step 4: AssetResolutionStep           <-- NEW
  |  Step 5: F14RuleMappingStep
  |  Step 6: FXConversionStep
  |
  v
EnrichmentDataPersister
  |  writes one F01.05 record per enriched transaction
  |  StatusManager: ENRICHMENT (null) → NEW → PROCESSING → ENRICHED|MANUAL_REVIEW|ERROR
  |  StatusManager: BANK_TRX/SECU_TRX PROCESSING → ENRICHED|MANUAL_REVIEW|ERROR
  |  StatusManager: STATEMENT CONSOLIDATED → ENRICHED|ERROR (after all transactions)
  v
F01.05 (trxEnrichment)
```

### 3.1 Pipeline Ordering Rationale

| Step | Why This Position |
|------|-------------------|
| 1. CurrencyValidation | Must validate currency first; invalid currency blocks FX conversion |
| 2. CounterpartyDetermination | Must identify counterparty before F14 rule matching (rules are counterparty-scoped) |
| 3. CustomerIdentification | Independent of counterparty; runs for both bank and secu (customer = bank's client); needed for GL account construction |
| 4. AssetResolution | Securities-only; resolves ticker to asset master; needs to happen before F14 for potential asset-class-based rules |
| 5. F14RuleMapping | Needs counterparty_id (Step 2) and optionally asset_class (Step 4) as inputs for rule matching |
| 6. FXConversion | Last because it may optionally use internal_type (Step 5) to select rate type (trade date vs payment date) |

---

## 4. Table & Form Dependencies

### 4.1 Source Tables (READ)

| Table Name (Joget) | Form | Description |
|---|---|---|
| `bank_statement` | F01.00 | Statement header: bank, account_type, from_date, to_date, status |
| `bank_total_trx` | F01.03 | Bank transaction rows: payment_date, payment_amount, currency, d_c, other_side_bic, other_side_name, payment_description, reference_number, customer_id, status |
| `secu_total_trx` | F01.04 | Securities transaction rows: transaction_date, type, ticker, description, quantity, price, currency, amount, fee, total_amount, reference, status |

### 4.2 MDM Tables (READ)

| Table Name (Joget) | Form | Queried By |
|---|---|---|
| `currency` | F10.05 | code (3-letter ISO) |
| `customer` | F10.01 | id, registrationNumber, personalId, tax_id, name |
| `counterparty_master` | F02.03 | bankId (BIC), custodianId, brokerId; returns counterpartyId, shortCode, counterpartyType |
| `bank` | F10.02 | swift_code_bic; returns name |
| `broker` | F10.03 | id, swift_code_bic; returns bic_code |
| `customer_account` | F02.01 | account_number; returns customer_id |
| `cp_txn_mapping` | F14 rules | counterpartyId, sourceType; returns matchingField, matchOperator, matchValue, internalType, priority |
| `fx_rates_eur` | FX rates | targetCurrency, effectiveDate; returns exchangeRate, midRate, importSource, rateType |
| `asset_master` | F02.02 (assetMasterForm) | ticker; returns assetId, isin, categoryCode, asset_class, tradingCurrency. Key fields: assetId (IdGenerator), ticker, isin, cusip, sedol, assetName, shortName, categoryCode (SelectBox→asset category MDM), asset_class (SelectBox→asset class MDM), tradingCurrency (SelectBox→currency MDM), tradingStatus, riskCategory, liquidityProfile |

### 4.3 Target Tables (WRITE)

| Table Name (Joget) | Form | Description |
|---|---|---|
| `trxEnrichment` | F01.05 | Enriched transaction output — **52 fields** |
| `exception_queue` | Exception | Exceptions requiring manual attention |
| `audit_log` | Audit | Step-level audit trail |

**CRITICAL:** The plugin constant `TABLE_TRX_ENRICHMENT` must be `"trxEnrichment"` (not `"trx_enrichment"`) to match the F01.05 form's tableName.

---

## 5. DataContext Specification

DataContext is the data carrier passed through all pipeline steps. Each source transaction becomes one DataContext instance.

### 5.1 Core Fields (set by TransactionDataLoader)

| Field | Type | Set From | Description |
|---|---|---|---|
| `sourceType` | String | F01.00 account_type | "bank" or "secu" |
| `transactionId` | String | F01.03/F01.04 row ID | Unique source transaction ID |
| `statementId` | String | F01.00 row ID | Parent statement ID |
| `transactionRow` | FormRow | F01.03/F01.04 row | Raw transaction record |
| `statementRow` | FormRow | F01.00 row | Parent statement record |
| `transactionDate` | String | F01.03 payment_date / F01.04 transaction_date | Transaction date (yyyy-MM-dd) |
| `currency` | String | F01.03/F01.04 currency | 3-letter ISO currency code |
| `amount` | String | F01.03 payment_amount / F01.04 total_amount | Transaction amount |
| `baseAmount` | String | — | EUR equivalent (set by FXConversionStep) |
| `customerId` | String | F01.03/F01.04 customer_id | Raw customer ID field from source |
| `statementBank` | String | F01.00 bank | BIC/SWIFT of statement bank |
| `accountType` | String | F01.00 account_type | "bank" or "secu" |
| `processingStatus` | String | — | Last successful step status |
| `errorMessage` | String | — | Error details if any step fails |
| `processedSteps` | List | — | Ordered list of completed step names |

### 5.2 Bank-Specific Fields (set by TransactionDataLoader, source_type=bank)

| Field | Type | Set From (F01.03 field) |
|---|---|---|
| `paymentDate` | String | `payment_date` |
| `paymentAmount` | String | `payment_amount` |
| `debitCredit` | String | `d_c` |
| `otherSideBic` | String | `other_side_bic` |
| `otherSideAccount` | String | `other_side_account` |
| `otherSideName` | String | `other_side_name` |
| `paymentDescription` | String | `payment_description` |
| `referenceNumber` | String | `reference_number` |

### 5.3 Securities-Specific Fields (set by TransactionDataLoader, source_type=secu)

| Field | Type | Set From (F01.04 field) |
|---|---|---|
| `type` | String | `type` (e.g. "Ost", "Muu") |
| `ticker` | String | `ticker` |
| `description` | String | `description` |
| `quantity` | String | `quantity` |
| `price` | String | `price` |
| `fee` | String | `fee` |
| `totalAmount` | String | `total_amount` |
| `reference` | String | `reference` |

### 5.4 AdditionalData Map (populated by pipeline steps)

The `additionalData` map accumulates step results. Each key is documented with the step that sets it.

| Key | Type | Set By Step | Description |
|---|---|---|---|
| **Currency Validation (Step 1)** |||
| `currency_name` | String | CurrencyValidation | Full name (e.g. "Euro") |
| `decimal_places` | String | CurrencyValidation | Decimal precision |
| `currency_symbol` | String | CurrencyValidation | Symbol (e.g. "EUR") |
| **Counterparty Determination (Step 2)** |||
| `counterparty_id` | String | CounterpartyDetermination | Business counterparty ID (e.g. "CPT0143") |
| `counterparty_type` | String | CounterpartyDetermination | "Bank", "Custodian", or "Broker" |
| `counterparty_bic` | String | CounterpartyDetermination | BIC/SWIFT code used for lookup |
| `counterparty_name` | String | CounterpartyDetermination | Display name |
| `counterparty_short_code` | String | CounterpartyDetermination | ShortCode for GL (e.g. "LHV", "JPMC") |
| `other_side_bic` | String | CounterpartyDetermination | Other party BIC (bank txn only, for reference) |
| `other_side_name` | String | CounterpartyDetermination | Other party name (bank txn only, for reference) |
| **Customer Identification (Step 3)** |||
| `customer_id` | String | CustomerIdentification | Resolved customer master ID |
| `customer_name` | String | CustomerIdentification | Customer display name |
| `customer_code` | String | CustomerIdentification | Customer business code |
| `customer_confidence` | Integer | CustomerIdentification | 0-100 confidence score |
| `customer_identification_method` | String | CustomerIdentification | "DIRECT_ID", "ACCOUNT_NUMBER", "REGISTRATION_NUMBER_EXTRACTED", "NAME_PATTERN", "NONE" |
| `customer_type` | String | CustomerIdentification | "Individual" or "Corporate" |
| `customer_currency` | String | CustomerIdentification | Customer base currency |
| `customer_risk_level` | String | CustomerIdentification | Risk classification |
| **Asset Resolution (Step 4) — NEW** |||
| `asset_id` | String | AssetResolution | Resolved asset master ID (F02.02 assetId) |
| `asset_isin` | String | AssetResolution | ISIN code (F02.02 isin) |
| `asset_category` | String | AssetResolution | Asset category code (F02.02 categoryCode) |
| `asset_class` | String | AssetResolution | Asset class code (F02.02 asset_class) |
| `asset_base_currency` | String | AssetResolution | Asset trading/denomination currency (F02.02 tradingCurrency) |
| `currency_mismatch_flag` | String | AssetResolution | "yes" if transaction currency != tradingCurrency |
| **F14 Rule Mapping (Step 5)** |||
| `internal_type` | String | F14RuleMapping | Matched internal transaction type code |
| `f14_rule_id` | String | F14RuleMapping | ID of the matched rule |
| `f14_rule_name` | String | F14RuleMapping | Name of the matched rule |
| `f14_rules_evaluated` | Integer | F14RuleMapping | Number of rules evaluated |
| **FX Conversion (Step 6)** |||
| `original_amount` | String | FXConversion | Original transaction amount |
| `original_currency` | String | FXConversion | Original currency code |
| `base_amount` | String | FXConversion | EUR equivalent amount |
| `base_currency` | String | FXConversion | Always "EUR" |
| `fx_rate` | Double | FXConversion | Applied exchange rate |
| `fx_rate_date` | String | FXConversion | Rate effective date |
| `fx_rate_source` | String | FXConversion | "ecb", "manual", "BASE_CURRENCY", "MISSING" |
| `base_fee` | String | FXConversion | EUR fee (secu only) |
| `base_total_amount` | String | FXConversion | EUR total (secu only) |

---

## 6. TransactionDataLoader Specification

### 6.1 Responsibility

Load all unprocessed transactions from F01.03 and F01.04, grouped by their parent statement (F01.00).

### 6.2 Processing Logic

1. Query `bank_statement` table for all rows where `status = Status.CONSOLIDATED.getCode()` (statements ready for enrichment)
2. For each unprocessed statement:
   a. Transition each source transaction via StatusManager: `statusManager.transition(dao, entityType, txId, Status.PROCESSING, "rows-enrichment", "Pipeline processing started")`
   b. Based on `account_type`:
      - `"bank"` → load from `bank_total_trx` where `statement_id = <statementId>` and `status = Status.NEW.getCode()`
      - `"secu"` → load from `secu_total_trx` where `statement_id = <statementId>` and `status = Status.NEW.getCode()`
3. Create one DataContext per transaction row (see Section 5)
4. Sort all contexts by transaction date ascending
5. Return the complete list

### 6.3 Bank DataContext Construction

```
sourceType        = "bank"
transactionId     = F01.03 row ID
statementId       = F01.00 row ID
statementBank     = F01.00.bank (BIC/SWIFT)
transactionDate   = F01.03.payment_date
currency          = F01.03.currency
amount            = F01.03.payment_amount
customerId        = F01.03.customer_id
debitCredit       = F01.03.d_c
otherSideBic      = F01.03.other_side_bic
otherSideName     = F01.03.other_side_name
otherSideAccount  = F01.03.other_side_account
paymentDescription= F01.03.payment_description
referenceNumber   = F01.03.reference_number
```

### 6.4 Securities DataContext Construction

```
sourceType        = "secu"
transactionId     = F01.04 row ID
statementId       = F01.00 row ID
statementBank     = F01.00.bank (BIC/SWIFT)
transactionDate   = F01.04.transaction_date
currency          = F01.04.currency
amount            = F01.04.total_amount
customerId        = F01.04.customer_id      (may be null for secu)
type              = F01.04.type
ticker            = F01.04.ticker
description       = F01.04.description
quantity          = F01.04.quantity
price             = F01.04.price
fee               = F01.04.fee
totalAmount       = F01.04.total_amount
reference         = F01.04.reference
```

---

## 7. Pipeline Steps Specification

### 7.1 Step 1: CurrencyValidationStep

**Purpose:** Validate that the transaction currency exists and is active in the currency MDM.

**Execution condition:** Always (unless previous error in context).

**Input:** `context.getCurrency()`

**Processing:**
1. Normalize currency code: trim, uppercase
2. Query `currency` table for matching `code` with `status = "active"`
3. If found: store currency_name, decimal_places, symbol in additionalData
4. If not found: create exception (type=INVALID_CURRENCY, priority based on amount), return failure

**Output (additionalData):** `currency_name`, `decimal_places`, `currency_symbol`

**Status on success:** `context.processingStatus = "currency_validated"`

**Exceptions created:**
- `MISSING_CURRENCY` — currency field is null/empty
- `INVALID_CURRENCY` — currency code not found in MDM
- `CURRENCY_VALIDATION_ERROR` — unexpected error

---

### 7.2 Step 2: CounterpartyDeterminationStep

**Purpose:** Identify the counterparty for each transaction.

**Execution condition:** Always (unless previous error).

**Processing logic:**

For **bank** transactions:
1. Use `statementBank` (BIC) as the counterparty lookup key
2. Search `counterparty_master` for active records where:
   - `counterpartyType = "Bank"` and `bankId = <statementBank>`, OR
   - `counterpartyType = "Custodian"` and `custodianId = <statementBank>`, OR
   - `counterpartyType = "Broker"` and broker's BIC matches
3. Return the `counterpartyId` (business ID, e.g. "CPT0143") and `shortCode`
4. Store `other_side_bic` and `other_side_name` as reference info

For **securities** transactions:
1. Use `statementBank` (BIC) as the counterparty lookup key (the custodian/broker holding the securities)
2. Same lookup logic as bank transactions
3. Determine `counterpartyType` from the securities transaction type:
   - BUY/SELL/TRADE → "Broker"
   - CUSTODY/SAFEKEEPING/DIVIDEND/CORPORATE → "Custodian"
   - Default → "Custodian"

**Output (additionalData):** `counterparty_id`, `counterparty_type`, `counterparty_bic`, `counterparty_name`, `counterparty_short_code`

**Status on success:** `context.processingStatus = "counterparty_determined"`

**Exceptions created:**
- `COUNTERPARTY_NOT_FOUND` — no matching counterparty for BIC
- `COUNTERPARTY_DETERMINATION_ERROR` — unexpected error

**On failure:** Set counterparty_id = "UNKNOWN", continue processing.

---

### 7.3 Step 3: CustomerIdentificationStep

**Purpose:** Identify the customer for both bank and securities transactions. Each transaction belongs to a bank customer whose portfolio or account is being operated on.

**Execution condition:** Always (both bank and secu). For the majority of transactions the customer must be resolved. Only rare technical/internal bank operations may legitimately have no customer.

**Processing (4 methods, tried in order — bank and secu):**

| Priority | Method | Confidence | Input | Bank | Secu |
|---|---|---|---|---|---|
| 1 | Direct ID match | 100% | `context.customerId` → match as CUST-XXXXXX format, or as registrationNumber/personalId | Yes | Yes |
| 2 | Account number | 95% | `transactionRow.account_number` → lookup in `customer_account` table | Yes | Yes (if account_number present) |
| 3 | Registration number extraction | 90% | Extract REG:, REGNUM: patterns from reference/description → search customer master | Yes | Yes (from description/reference) |
| 4 | Name pattern matching | 70% | `context.otherSideName` → exact/partial name match in customer master | Yes | N/A (no otherSideName for secu) |

**Note on securities:** The customer is the bank's client whose portfolio is being managed. The `customer_id` field on F01.04 is the primary source. If F01.04 provides customer_id, Method 1 (Direct ID) will resolve it at 100% confidence. If absent, Methods 2-3 are attempted. Method 4 is not applicable for securities as there is no "other side name".

**Post-identification:**
1. Verify customer is active (`status = "active"`)
2. Load customer details: customer_type, base_currency, risk_level
3. If confidence < 80%: create LOW_CONFIDENCE_IDENTIFICATION exception (priority=low)

**Output (additionalData):** `customer_id`, `customer_name`, `customer_code`, `customer_confidence`, `customer_identification_method`, `customer_type`, `customer_currency`, `customer_risk_level`

**Also sets:** `context.customerId = <resolved customer ID>`

**Status on success:** `context.processingStatus = "customer_identified"`

**On failure:** Set customer_id = "UNKNOWN", confidence = 0, method = "NONE". Create MISSING_CUSTOMER exception (priority=high). Continue processing.

**Exceptions created:**
- `MISSING_CUSTOMER` — no customer found by any method (high priority)
- `INACTIVE_CUSTOMER` — customer found but not active (high priority)
- `LOW_CONFIDENCE_IDENTIFICATION` — identified but confidence < 80% (low priority)
- `CUSTOMER_IDENTIFICATION_ERROR` — unexpected error

---

### 7.4 Step 4: AssetResolutionStep — NEW

**Purpose:** For securities transactions, resolve the ticker symbol to an asset master record and retrieve asset metadata.

**Execution condition:** `sourceType = "secu"` only. Skip for bank transactions.

**Source table:** `asset_master` (form F02.02, formDefId `assetMasterForm`)

**F02.02 key fields used by this step:**

| F02.02 Field | Type | Used For |
|---|---|---|
| `assetId` | IdGeneratorField | Resolved asset master ID |
| `ticker` | TextField | Primary lookup key |
| `isin` | TextField | ISIN code to store in F01.05 |
| `categoryCode` | SelectBox | Asset category (bound to F10.14 asset category MDM) |
| `asset_class` | SelectBox | Asset class (bound to F10.15 asset class MDM) |
| `tradingCurrency` | SelectBox | Asset denomination/trading currency |
| `tradingStatus` | SelectBox | Must be active for valid resolution |

**Processing:**
1. Get `ticker` from context
2. If ticker is null/empty: create exception MISSING_TICKER, set asset_id = "UNKNOWN", continue
3. Query `asset_master` table:
   a. Load all asset records (or use AJAX query)
   b. First try exact match on `ticker` field
   c. If not found, try match on `isin` (in case the source provides ISIN instead of ticker)
   d. If not found, try partial/fuzzy match on ticker as substring of `shortName` or `assetName`
   e. If found, verify `tradingStatus` is active
4. If found and active:
   a. Store: `asset_id` = row.assetId, `asset_isin` = row.isin, `asset_category` = row.categoryCode, `asset_class` = row.asset_class, `asset_base_currency` = row.tradingCurrency
   b. Compare context.getCurrency() vs row.tradingCurrency:
      - If different: set `currency_mismatch_flag = "yes"`
      - If same: set `currency_mismatch_flag = "no"`
5. If found but inactive: create exception INACTIVE_ASSET, set asset_id to the assetId anyway (for reference), continue
6. If not found: create exception UNKNOWN_ASSET, set asset_id = "UNKNOWN", continue

**Output (additionalData):** `asset_id`, `asset_isin`, `asset_category`, `asset_class`, `asset_base_currency`, `currency_mismatch_flag`

**Status on success:** `context.processingStatus = "asset_resolved"`

**Exceptions created:**
- `MISSING_TICKER` — no ticker in source transaction (medium priority)
- `UNKNOWN_ASSET` — ticker/ISIN not found in asset master (medium priority)
- `INACTIVE_ASSET` — asset found but tradingStatus is not active (low priority)
- `ASSET_RESOLUTION_ERROR` — unexpected error (high priority)

---

### 7.5 Step 5: F14RuleMappingStep

**Purpose:** Classify the transaction by matching it against configurable rules to determine the internal transaction type.

**Execution condition:** Always (unless previous error).

**Processing:**
1. Get `counterparty_id` from additionalData (set by Step 2)
2. Load applicable rules from `cp_txn_mapping` table where:
   - `sourceType` matches context source type ("bank" or "secu")
   - `counterpartyId` matches context counterparty OR equals "SYSTEM" (universal fallback)
   - `status` is "Active" or "active"
   - `effectiveDate` is null or <= today
3. Sort rules: counterparty-specific first (by priority ASC), then SYSTEM rules (by priority ASC)
4. Evaluate each rule against the transaction:
   a. Get the `matchingField` value from context (payment_description, d_c, type, ticker, etc.)
   b. Apply `matchOperator`: equals, contains, startsWith, endsWith, regex, in
   c. Apply `caseSensitive` flag
   d. If `matchingField = "combined"`: evaluate `complexRuleExpression` (AND/OR conditions)
   e. If field match passes, check `arithmeticCondition` (e.g. amount > 100)
5. First matching rule wins → extract `internalType`
6. If no rules exist for counterparty: set UNMATCHED, create NO_F14_RULES exception
7. If rules exist but none match: set UNMATCHED, create NO_RULE_MATCH exception

**Output (additionalData):** `internal_type`, `f14_rule_id`, `f14_rule_name`, `f14_rules_evaluated`

**Status on success:** `context.processingStatus = "f14_mapped"`

**Matching confidence derivation:**
- If matched: confidence = "high"
- If UNMATCHED: confidence = "low"

**Exceptions created:**
- `NO_F14_RULES` — no rules configured for counterparty/source type (high priority)
- `NO_RULE_MATCH` — rules exist but none match (medium priority; includes transaction details for manual rule creation)
- `F14_MAPPING_ERROR` — unexpected error

---

### 7.6 Step 6: FXConversionStep

**Purpose:** Convert transaction amount to base currency (EUR).

**Execution condition:** Always (unless previous error).

**Processing:**
1. Get validated currency from context
2. If currency = EUR: set base_amount = original_amount, fx_rate = 1.0, fx_rate_source = "BASE_CURRENCY", done
3. Determine FX rate date:
   - Bank transactions: use transaction_date (payment_date)
   - Securities transactions: use transaction_date (trade date)
   - Dividends/income: use transaction_date
4. Look up FX rate in `fx_rates_eur` table:
   a. Match `targetCurrency` and `effectiveDate` exactly
   b. Rate must have `status = "active"`
   c. The table stores rates as "1 EUR = X target currency"
   d. To convert target → EUR: rate = 1 / exchangeRate
5. If exact date not found: search for most recent rate within 5 days (MAX_RATE_AGE_DAYS)
6. If rate found:
   a. Calculate: `baseAmount = originalAmount * rate`
   b. For securities: also convert fee and total_amount
   c. If rate is older than 0 days: create OLD_FX_RATE exception (low priority)
7. If no rate found: set baseAmount = "0.00", fx_rate_source = "MISSING", create FX_RATE_MISSING exception

**Output (additionalData):** `original_amount`, `original_currency`, `base_amount`, `base_currency`, `fx_rate`, `fx_rate_date`, `fx_rate_source`, `base_fee`, `base_total_amount`

**Also sets:** `context.baseAmount = <EUR amount>`

**Status on success:** `context.processingStatus = "fx_converted"`

**Exceptions created:**
- `MISSING_CURRENCY` — currency not set
- `INVALID_FX_DATE` — cannot determine rate lookup date
- `FX_RATE_MISSING` — no rate found within 5 days (high priority)
- `OLD_FX_RATE` — using a rate older than exact date (low priority)
- `FX_CONVERSION_ERROR` — unexpected error

---

## 8. EnrichmentDataPersister Specification

### 8.1 Responsibility

Transform the enriched DataContext into an F01.05 record, save it, update source transaction status, and create an audit trail.

### 8.2 Target Table

`trxEnrichment` (matching F01.05 form tableName)

### 8.3 Record ID Format

`TRX-<6-char-UUID-uppercase>` (e.g. TRX-A3BF12)

### 8.4 Complete Field Mapping

Each row below maps one F01.05 field to its source in DataContext.

#### Provenance Section

| F01.05 Field | Source | Logic |
|---|---|---|
| `source_tp` | `context.getSourceType()` | Direct: "bank" or "secu" |
| `statement_id` | `context.getStatementId()` | Direct |
| `statement_date` | `context.getStatementRow().getProperty("from_date")` | Copy statement's from_date |
| `source_trx_id` | `context.getTransactionId()` | Direct |
| `origin` | constant | Set "auto" for pipeline-created records |
| `lineage_note` | computed | Format: "Pipeline v{version}: {N}/{total} steps OK. Steps: {step1},{step2},..." from processedSteps |
| `acc_post_id` | — | Leave null. Populated by gl-preparator downstream |
| `parent_enrichment_id` | — | Leave null. For manual split/correction only |
| `group_id` | — | Leave null. For manual grouping only |
| `split_sequence` | — | Leave null. For manual split only |

#### Transaction Core Section

| F01.05 Field | Source | Logic |
|---|---|---|
| `transaction_date` | `context.getTransactionDate()` | Direct |
| `settlement_date` | computed | Bank: same as transaction_date. Secu: transaction_date + N business days where N = plugin property `settlementConvention` (default "2", meaning T+2). Weekends and holidays are skipped. **Extensibility note:** designed as a single global value for now; if needed, can be extended later to a per-asset-class lookup table without changing the pipeline interface — only the settlement date computation method in the persister would need updating |
| `debit_credit` | `context.getDebitCredit()` | Direct for bank (from F01.03 d_c). For secu: derived from transaction type via configurable mapping (see debit/credit derivation table below) |
| `description` | Description Builder output | Assembled from configurable source field list (see Section 12.2). Bank default: payment_description, reference_number, other_side_name, other_side_bic, other_side_account. Secu default: description, reference, ticker, quantity, price |
| `original_amount` | `context.getAmount()` | Direct |
| `fee_amount` | `context.getFee()` | Secu only. Bank: null |
| `total_amount` | `context.getTotalAmount()` or `context.getAmount()` | Secu: use totalAmount. Bank: use amount |
| `original_currency` | `context.getCurrency()` | Direct (after validation/normalization) |

**Debit/Credit Derivation for Securities (configurable mapping, default values):**

The mapping from F01.04 `type` to F01.05 `debit_credit` reflects the cash impact on the client's account. This mapping is stored as a configurable property (`secuDebitCreditMapping`) so new transaction types can be added without code changes.

| F01.04 Transaction Type | D/C/N | Accounting Rationale |
|---|---|---|
| BUY, PURCHASE | D | Cash outflow — securities acquired |
| SELL, DISPOSE | C | Cash inflow — securities sold |
| DIVIDEND | C | Cash inflow — income received |
| COUPON, INTEREST | C | Cash inflow — interest/coupon payment |
| CUSTODY_FEE, SAFEKEEPING, FEE | D | Cash outflow — fee/expense charged |
| TRANSFER_IN | N | Neutral — custody transfer, no cash movement |
| TRANSFER_OUT | N | Neutral — custody transfer, no cash movement |
| CORPORATE_ACTION | N | Neutral by default — varies by action, manual review recommended |
| *(unmapped type)* | N | Default to neutral, create exception for manual review |

#### Classification Section

| F01.05 Field | Source | Logic |
|---|---|---|
| `internal_type` | `additionalData.internal_type` | Direct. May be "UNMATCHED" |
| `type_confidence` | computed | Map from matching + customer confidence: if internal_type != UNMATCHED AND customer_confidence >= 80 → "high"; if >= 50 → "medium"; else → "low" |
| `matched_rule_id` | `additionalData.f14_rule_id` | Direct. May be null if UNMATCHED |

#### Customer Resolution Section

| F01.05 Field | Source | Logic |
|---|---|---|
| `resolved_customer_id` | `context.getCustomerId()` | After Step 3 resolution. May be "UNKNOWN" |
| `customer_match_method` | `additionalData.customer_identification_method` | Map: DIRECT_ID → "direct_id", ACCOUNT_NUMBER → "account_mapping", REGISTRATION_NUMBER_EXTRACTED → "registration_number", NAME_PATTERN → "name_pattern", NONE → "unresolved" |
| `customer_code` | `additionalData.customer_code` | Direct |
| `customer_display_name` | `additionalData.customer_name` | Direct |

**Note:** For securities transactions, customer resolution runs the same as for bank transactions — the customer is the bank's client whose portfolio is being managed. If no customer can be resolved (rare — typically only internal/technical transactions), set resolved_customer_id = "UNKNOWN" and customer_match_method = "unresolved", and create a MISSING_CUSTOMER exception as for bank transactions.

#### Asset Resolution Section

| F01.05 Field | Source | Logic |
|---|---|---|
| `resolved_asset_id` | `additionalData.asset_id` | Direct. Secu only |
| `asset_isin` | `additionalData.asset_isin` | Direct. Secu only |
| `asset_category` | `additionalData.asset_category` | Direct. Secu only |
| `asset_class` | `additionalData.asset_class` | Direct. Secu only |
| `asset_base_currency` | `additionalData.asset_base_currency` | Direct. Secu only |
| `currency_mismatch_flag` | `additionalData.currency_mismatch_flag` | "yes" or "no". Secu only |

**Note:** For bank transactions, all asset fields are left null. F01.05's Asset Resolution section has `visibilityControl: source_tp`, `visibilityValue: secu` so these fields are hidden for bank records.

#### Counterparty Resolution Section

| F01.05 Field | Source | Logic |
|---|---|---|
| `counterparty_id` | `additionalData.counterparty_id` | Populate when counterparty_type = "Bank" |
| `counterparty_short_code` | `additionalData.counterparty_short_code` | Populate when counterparty_type = "Bank" |
| `counterparty_source` | computed | "statement_bank" for both bank and secu (since we use statement bank BIC in both cases) |
| `custodian_id` | `additionalData.counterparty_id` | Populate when counterparty_type = "Custodian" |
| `custodian_short_code` | `additionalData.counterparty_short_code` | Populate when counterparty_type = "Custodian" |
| `broker_id` | `additionalData.counterparty_id` | Populate when counterparty_type = "Broker" |
| `broker_short_code` | `additionalData.counterparty_short_code` | Populate when counterparty_type = "Broker" |

**Routing logic:**
```
counterpartyType = additionalData.get("counterparty_type")
counterpartyId   = additionalData.get("counterparty_id")
shortCode        = additionalData.get("counterparty_short_code")

if "Bank":
    row.counterparty_id         = counterpartyId
    row.counterparty_short_code = shortCode
elif "Custodian":
    row.custodian_id            = counterpartyId
    row.custodian_short_code    = shortCode
elif "Broker":
    row.broker_id               = counterpartyId
    row.broker_short_code       = shortCode
```

#### Currency & FX Section

| F01.05 Field | Source | Logic |
|---|---|---|
| `validated_currency` | `context.getCurrency()` | After CurrencyValidationStep normalization |
| `fx_rate_source` | `additionalData.fx_rate_source` | "ecb", "manual", "BASE_CURRENCY", "MISSING" |
| `requires_eur_parallel` | computed | "yes" if validated_currency != "EUR", else "no" |
| `fx_rate_to_eur` | `additionalData.fx_rate` | Double → String. The applied conversion rate |
| `fx_rate_date` | `additionalData.fx_rate_date` | Rate effective date |
| `base_amount_eur` | `context.getBaseAmount()` | EUR equivalent |

#### Fee & Pairing Section

| F01.05 Field | Source | Logic |
|---|---|---|
| `has_fee` | computed | "yes" if fee_amount != null AND fee_amount > 0, else "no" |
| `fee_trx_id` | — | Leave null. Populated by pairing logic downstream |
| `pair_id` | — | Leave null. Populated by pairing logic downstream |

#### Status & Notes Section

| F01.05 Field | Source | Logic |
|---|---|---|
| `status` | computed | See Section 8.5 below |
| `enrichment_timestamp` | `new Date()` | ISO timestamp: yyyy-MM-dd HH:mm:ss |
| `error_message` | `context.getErrorMessage()` | Direct. May be null |
| `processing_notes` | computed | Concatenated summary: "Steps completed: {processedSteps}. Customer: {customer_id} ({confidence}%). Counterparty: {counterparty_id} ({type}). Internal type: {internal_type}." |
| `version` | "1" | Initial version |

### 8.5 Status Determination & Transition Logic

The `status` field uses `Status` enum values from gam-framework. All transitions go through `StatusManager`.

**Step 1 — Determine target status:**

```
IF context.hasError():
    targetStatus = Status.ERROR
ELSE IF needsManualReview(context):
    targetStatus = Status.MANUAL_REVIEW
ELSE:
    targetStatus = Status.ENRICHED

needsManualReview returns TRUE if ANY of:
  - customer_id = "UNKNOWN"
  - counterparty_id = "UNKNOWN"
  - internal_type = "UNMATCHED"
  - customer_confidence < confidenceThresholdHigh (default 80)
  - asset_id = "UNKNOWN" (secu only)
  - fx_rate_source = "MISSING"
```

**Step 2 — Transition the enrichment record:**

```java
// NOTE: statusManager is the shared instance from RowsEnricher (Section 15.3)
// — do NOT instantiate per-record

// Create F01.05 record with initial status
statusManager.transition(dao, "trxEnrichment", EntityType.ENRICHMENT,
    enrichedRecordId, Status.NEW, "rows-enrichment", "New enrichment record created");

// Move to PROCESSING
statusManager.transition(dao, "trxEnrichment", EntityType.ENRICHMENT,
    enrichedRecordId, Status.PROCESSING, "rows-enrichment", "Pipeline processing started");

// Move to final status (ENRICHED, MANUAL_REVIEW, or ERROR)
String reason = buildTransitionReason(context);  // e.g. "6/6 steps OK" or "UNKNOWN customer, UNMATCHED type"
try {
    statusManager.transition(dao, "trxEnrichment", EntityType.ENRICHMENT,
        enrichedRecordId, targetStatus, "rows-enrichment", reason);
} catch (InvalidTransitionException e) {
    // Log error, create exception queue entry
    LogUtil.error(getClassName(), "Invalid enrichment transition: " + e.getMessage());
}
```

**Note:** Each `StatusManager.transition()` call automatically writes a `TransitionAuditEntry` to `audit_log`. No additional audit code is needed for status changes.

### 8.6 Post-Persistence Actions

After each enrichment record is saved:

1. **Transition source transaction status via StatusManager:**
   ```java
   EntityType sourceEntity = (sourceType == "bank") ? EntityType.BANK_TRX : EntityType.SECU_TRX;
   Status sourceTarget = (targetStatus == Status.ERROR) ? Status.ERROR
                        : (targetStatus == Status.MANUAL_REVIEW) ? Status.MANUAL_REVIEW
                        : Status.ENRICHED;
   statusManager.transition(dao, sourceEntity, sourceTransactionId, sourceTarget,
       "rows-enrichment", "Enrichment record " + enrichedRecordId + " created");
   ```
   This automatically writes an audit entry for the source transaction transition.

2. **Update source row metadata** (non-status fields, direct write OK):
   Set `enrichment_date = now()`, `enriched_record_id = <new F01.05 row ID>`.

3. **Store enriched_record_id in context**: Set `additionalData.enriched_record_id = <new F01.05 row ID>` for downstream use.

**Note:** The audit entry for "ENRICHMENT_SAVED" is automatically created by the StatusManager transition in Step 8.5. No manual audit write needed.

### 8.7 Batch Completion (per statement)

After all transactions for a statement are persisted:

1. **Transition statement status via StatusManager:**
   ```java
   // The statement lifecycle in gam-framework is:
   //   NEW → IMPORTING → IMPORTED → CONSOLIDATING → CONSOLIDATED → ENRICHED → POSTED
   // The rows-enrichment plugin receives statements in CONSOLIDATED status
   // and transitions them to ENRICHED (success) or ERROR (failures).
   Status statementTarget = (failureCount == 0) ? Status.ENRICHED : Status.ERROR;
   String reason = String.format("%d success, %d failures out of %d total",
       successCount, failureCount, totalCount);
   statusManager.transition(dao, EntityType.STATEMENT, statementId,
       statementTarget, "rows-enrichment", reason);
   ```
   This automatically writes an audit entry for the statement transition.

2. **Update statement metadata** (non-status fields, direct write OK):
   Set `processing_completed = now()`, `transactions_processed = total`, `transactions_success = successCount`, `transactions_failed = failureCount`.

---

## 9. Exception Queue Specification

All exceptions are written to the `exception_queue` table.

### 9.1 Exception Record Structure

| Field | Description |
|---|---|
| `id` | UUID |
| `transaction_id` | Source transaction ID |
| `statement_id` | Parent statement ID |
| `source_type` | "bank" or "secu" |
| `exception_type` | Type code (see below) |
| `exception_details` | Human-readable description |
| `exception_date` | Timestamp |
| `amount` | Transaction amount (for priority context) |
| `currency` | Transaction currency |
| `transaction_date` | Transaction date |
| `priority` | "critical" / "high" / "medium" / "low" |
| `status` | `Status.OPEN.getCode()` ("open") — initial state per gam-framework EXCEPTION lifecycle. Transition via `StatusManager.transition(dao, EntityType.EXCEPTION, exceptionId, Status.OPEN, "rows-enrichment", reason)` |
| `assigned_to` | "supervisor" (high/critical) / "fx_specialist" (FX) / "operations" (other) |
| `due_date` | Calculated: critical/high = +1 day, medium = +3 days, low = +7 days |
| Additional context fields | Varies by exception type (payment_description, ticker, counterparty_id, etc.) |

### 9.2 Exception Types

| Code | Step | Priority | Description |
|---|---|---|---|
| `MISSING_CURRENCY` | CurrencyValidation | based on amount | Currency field empty |
| `INVALID_CURRENCY` | CurrencyValidation | based on amount | Currency not in MDM |
| `COUNTERPARTY_NOT_FOUND` | CounterpartyDetermination | based on amount | No counterparty for BIC |
| `MISSING_CUSTOMER` | CustomerIdentification | high | Customer not identifiable |
| `INACTIVE_CUSTOMER` | CustomerIdentification | high | Customer found but inactive |
| `LOW_CONFIDENCE_IDENTIFICATION` | CustomerIdentification | low | Confidence < 80% |
| `MISSING_TICKER` | AssetResolution | medium | No ticker in secu transaction |
| `UNKNOWN_ASSET` | AssetResolution | medium | Ticker not in asset master |
| `NO_F14_RULES` | F14RuleMapping | high | No rules for counterparty/source |
| `NO_RULE_MATCH` | F14RuleMapping | medium | Rules exist, none matched |
| `FX_RATE_MISSING` | FXConversion | high | No rate within 5 days |
| `OLD_FX_RATE` | FXConversion | low | Rate is older than exact date |
| `INACTIVE_ASSET` | AssetResolution | low | Asset found but tradingStatus not active |
| `INVALID_FX_DATE` | FXConversion | medium | Cannot determine rate lookup date |
| `CURRENCY_VALIDATION_ERROR` | CurrencyValidation | high | Unexpected error during validation |
| `COUNTERPARTY_DETERMINATION_ERROR` | CounterpartyDetermination | high | Unexpected error during lookup |
| `CUSTOMER_IDENTIFICATION_ERROR` | CustomerIdentification | high | Unexpected error during identification |
| `ASSET_RESOLUTION_ERROR` | AssetResolution | high | Unexpected error during resolution |
| `F14_MAPPING_ERROR` | F14RuleMapping | high | Unexpected error during rule evaluation |
| `FX_CONVERSION_ERROR` | FXConversion | high | Unexpected error during conversion |

### 9.3 Priority Calculation (amount-based)

For exceptions where priority = "based on amount":

| Amount (absolute) | Priority |
|---|---|
| >= 1,000,000 | critical |
| >= 100,000 | high |
| >= 10,000 | medium |
| < 10,000 | low |

---

## 10. Audit Log Specification

The `audit_log` table receives entries from two sources:

### 10.1 Framework-Managed Audit (automatic — via StatusManager)

Every `StatusManager.transition()` call automatically writes a `TransitionAuditEntry` with:

| Field | Description |
|---|---|
| `id` | UUID (auto-generated) |
| `entity_type` | Enum name: "STATEMENT", "BANK_TRX", "SECU_TRX", "ENRICHMENT", "EXCEPTION" |
| `entity_id` | Record primary key |
| `from_status` | Previous status code (may be "null" for initial) |
| `to_status` | New status code |
| `triggered_by` | Always `"rows-enrichment"` for this plugin |
| `reason` | Human-readable explanation (e.g. "6/6 steps OK", "UNKNOWN customer, UNMATCHED type") |
| `timestamp` | ISO 8601 (auto-generated) |

**No code needed for these entries** — they are a side-effect of every status transition.

Examples of auto-generated entries:
- BANK_TRX "BT001": NEW → PROCESSING (triggered_by: "rows-enrichment", reason: "Pipeline processing started")
- ENRICHMENT "TRX-A3BF12": PROCESSING → ENRICHED (triggered_by: "rows-enrichment", reason: "6/6 steps OK. Customer: CUST-001 (100%). Counterparty: CPT0143 (Bank)")
- STATEMENT "ST001": CONSOLIDATED → ENRICHED (triggered_by: "rows-enrichment", reason: "15 success, 0 failures out of 15 total")
- EXCEPTION "EX001": (null) → OPEN (triggered_by: "rows-enrichment", reason: "MISSING_CUSTOMER: No customer found for bank transaction BT007")

### 10.2 Step-Level Audit (custom — written by pipeline steps)

In addition to status transitions, each pipeline step writes a step-level audit entry for detailed operational tracing. These use the same `audit_log` table but with step-specific fields:

| Field | Description |
|---|---|
| `transaction_id` | Source transaction ID |
| `step_name` | Pipeline step name |
| `action` | Step-specific action code (see below) |
| `details` | Human-readable step result details |
| `timestamp` | ISO timestamp |

| Action Code | Step | When |
|---|---|---|
| `CURRENCY_VALIDATED` | CurrencyValidation | Currency validated successfully |
| `COUNTERPARTY_DETERMINED` | CounterpartyDetermination | Counterparty identified |
| `CUSTOMER_IDENTIFIED` | CustomerIdentification | Customer identified |
| `ASSET_RESOLVED` | AssetResolution | Asset resolved from ticker |
| `F14_MAPPED` | F14RuleMapping | Internal type determined |
| `BASE_CURRENCY_CALCULATED` | FXConversion | EUR amount calculated |

**Note:** The old `ENRICHMENT_SAVED` and `STATEMENT_PROCESSED` action codes are **removed** — these are now covered by framework-managed audit entries from StatusManager transitions (Section 10.1).

---

## 11. Configuration & Framework Integration Specification

The plugin must externalise all configurable values. No hardcoding. Status management must use gam-framework.

### 11.1 Plugin Properties (Joget plugin configuration)

| Property | Default | Description |
|---|---|---|
| `baseCurrency` | "EUR" | Base/reporting currency |
| `maxFxRateAgeDays` | 5 | Max days old for acceptable FX rate |
| `settlementConvention` | "2" | Settlement lag in business days (integer as string). "2" = T+2. Selectbox options: 1, 2, 3, 5. Extensible to per-asset-class later |
| `stopOnError` | false | Whether pipeline stops on first step failure |
| `batchSize` | 100 | Max statements to process per run |
| `pipelineVersion` | "2.0" | Version string for lineage tracking |
| `confidenceThresholdHigh` | 80 | Minimum confidence for "high" classification |
| `confidenceThresholdMedium` | 50 | Minimum confidence for "medium" classification |
| `descriptionFieldsBank` | "payment_description,reference_number,other_side_name,other_side_bic,other_side_account" | Comma-separated source fields to include in F01.05 description for bank transactions (see Section 12.2) |
| `descriptionFieldsSecu` | "description,reference,ticker,quantity,price" | Comma-separated source fields to include in F01.05 description for secu transactions (see Section 12.2) |
| `secuDebitCreditMapping` | "BUY:D,PURCHASE:D,SELL:C,DISPOSE:C,DIVIDEND:C,COUPON:C,INTEREST:C,CUSTODY_FEE:D,SAFEKEEPING:D,FEE:D,TRANSFER_IN:N,TRANSFER_OUT:N,CORPORATE_ACTION:N" | Configurable mapping from F01.04 transaction type to debit_credit (D/C/N). Unmapped types default to N with exception |
| `descriptionMaxLength` | 2000 | Maximum character length for the built description field |

### 11.2 Table Name Constants (DomainConstants)

All table names must be constants, centralised in `DomainConstants.java`:

| Constant | Current Value | Required Value |
|---|---|---|
| `TABLE_TRX_ENRICHMENT` | `"trx_enrichment"` | **`"trxEnrichment"`** |
| `TABLE_BANK_STATEMENT` | `"bank_statement"` | `"bank_statement"` (OK) |
| `TABLE_BANK_TOTAL_TRX` | `"bank_total_trx"` | `"bank_total_trx"` (OK) |
| `TABLE_SECU_TOTAL_TRX` | `"secu_total_trx"` | `"secu_total_trx"` (OK) |
| `TABLE_CUSTOMER_MASTER` | `"customer"` | `"customer"` (OK) |
| `TABLE_COUNTERPARTY_MASTER` | `"counterparty_master"` | `"counterparty_master"` (OK) |
| `TABLE_CURRENCY_MASTER` | `"currency"` | `"currency"` (OK) |
| `TABLE_ASSET_MASTER` | — | **ADD: `"asset_master"`** (F02.02, formDefId=assetMasterForm) |
| `TABLE_EXCEPTION_QUEUE` | `"exception_queue"` | `"exception_queue"` (OK) |
| `TABLE_AUDIT_LOG` | `"audit_log"` | `"audit_log"` (OK) |

### 11.3 FrameworkConstants.java — Cleanup Required

The current `FrameworkConstants.java` contains status constants and processing status strings that **must be replaced** with gam-framework's `Status` enum. This file should be reduced to only non-status constants.

| Current Constant | Action | Replacement |
|---|---|---|
| `STATUS_NEW = "new"` | **Remove** | `Status.NEW.getCode()` |
| `STATUS_ENRICHED = "enriched"` | **Remove** | `Status.ENRICHED.getCode()` |
| `STATUS_POSTED = "posted"` | **Remove** | `Status.POSTED.getCode()` |
| `STATUS_FAILED = "failed"` | **Remove** | `Status.ERROR.getCode()` |
| `STATUS_PENDING = "pending"` | **Remove** | `Status.PENDING.getCode()` |
| `STATUS_ACTIVE = "active"` / `STATUS_ACTIVE_CAPITAL = "Active"` | **Keep** | These are MDM status values, not entity lifecycle statuses |
| `PROCESSING_STATUS_ENRICHED = "enriched"` | **Remove** | `Status.ENRICHED.getCode()` |
| `PROCESSING_STATUS_MANUAL_REVIEW = "manual_review"` | **Remove** | `Status.MANUAL_REVIEW.getCode()` |
| `ENTITY_UNKNOWN = "UNKNOWN"` | **Keep** | Domain constant, not a status |
| `ENTITY_SYSTEM = "SYSTEM"` | **Keep** | F14 fallback rule scope identifier |
| `INTERNAL_TYPE_UNMATCHED = "UNMATCHED"` | **Keep** | Domain constant, not a status |
| `SYSTEM_USER = "SYSTEM"` | **Remove** | Joget handles createdBy natively |
| `PIPELINE_VERSION = "1.0"` | **Remove** | Plugin property `pipelineVersion` |

**After cleanup, FrameworkConstants retains only:** `STATUS_ACTIVE`, `STATUS_ACTIVE_CAPITAL`, `ENTITY_UNKNOWN`, `ENTITY_SYSTEM`, `INTERNAL_TYPE_UNMATCHED`.

### 11.4 Framework Contracts — DataStep Interface & Property Passing

**Problem:** Currently pipeline steps are instantiated with no-arg constructors and have no access to plugin configuration. Steps like FXConversionStep need `maxFxRateAgeDays`, CustomerIdentificationStep needs `confidenceThresholdHigh`, etc.

**Solution:** Pass the plugin properties `Map<String, Object>` to steps via one of:

- **Option A (recommended):** Add `setProperties(Map<String, Object>)` to the `DataStep` interface, called by RowsEnricher after step construction. Steps read their config from this map.
- **Option B:** Store properties in DataContext (accessible to all steps), but this pollutes the per-transaction context with global config.
- **Option C:** Pass properties via the `DataPipeline` constructor, which forwards to each step.

Whichever option is chosen, each step must gracefully fall back to defaults if a property is missing.

**DataStep interface contract (current):**

```java
public interface DataStep {
    StepResult execute(DataContext context, FormDataDao formDataDao);
    String getStepName();
    boolean shouldExecute(DataContext context);
}
```

**Proposed addition:**

```java
    void setProperties(Map<String, Object> properties);  // Option A
```

### 11.5 Values That Must NOT Be Hardcoded

| Currently Hardcoded | Location | Must Become |
|---|---|---|
| `"SPOT"` (fx_rate_type) | EnrichmentDataPersister.populateFXFields | Removed — not in F01.05 |
| `"pending"` (pairing_status) | EnrichmentDataPersister.populateMetadataFields | Removed — not in F01.05; has_fee is computed |
| `"1.0"` (pipeline_version) | FrameworkConstants | Plugin property `pipelineVersion` |
| `"SYSTEM"` (created_by) | FrameworkConstants | Removed — Joget handles createdBy natively |
| `80` (confidence threshold) | CustomerIdentificationStep, determineManualReviewStatus | Plugin property `confidenceThresholdHigh` |
| `5` (MAX_RATE_AGE_DAYS) | FXConversionStep | Plugin property `maxFxRateAgeDays` |
| BUY→D, SELL→C (secu debit/credit) | EnrichmentDataPersister | Plugin property `secuDebitCreditMapping` |
| Hardcoded description source | EnrichmentDataPersister | Plugin properties `descriptionFieldsBank` / `descriptionFieldsSecu` |
| `"T+2"` (settlement lag) | EnrichmentDataPersister | Plugin property `settlementConvention` (global, extensible to per-asset-class) |
| All status string literals | Throughout plugin | `Status` enum from gam-framework (see Section 11.3) |
| Direct status field writes | EnrichmentDataPersister, TransactionDataLoader | `StatusManager.transition()` (see Sections 8.5-8.7) |

---

## 12. Fields Dropped from Persister & Description Builder

### 12.1 Fields Dropped from Persister

The following fields are currently written by the plugin but have no corresponding F01.05 field. They are **removed** from the persister. However, some of them serve as inputs to the configurable **Description Builder** (Section 12.2) so that useful context is preserved in the `description` field for manual resolution.

| Dropped Field | Reason | Description Builder Candidate |
|---|---|---|
| `amount` | Redundant with `original_amount` | No |
| `currency` | Redundant with `validated_currency` and `original_currency` | No |
| `reference_number` | Source field from F01.03 | **Yes** — default for bank |
| `reference` | Source field from F01.04 | **Yes** — default for secu |
| `source_table` | Derivable from source_tp | No |
| `counterparty_bic` | Resolvable from counterparty_id | No |
| `counterparty_name` | Resolvable from counterparty_id via MDM | No |
| `customer_name` | Mapped to `customer_display_name` (renamed) | No |
| `customer_type` | Resolvable from customer MDM | No |
| `f14_rule_name` | Resolvable from matched_rule_id | No |
| `f14_priority` | Stored in rule definition | No |
| `base_currency` | Always EUR, implicit | No |
| `fx_rate_type` | Was hardcoded "SPOT", not in F01.05 | No |
| `pipeline_version` | Merged into lineage_note | No |
| `created_by` | Joget native field | No |
| `created_date` | Renamed to enrichment_timestamp | No |
| `processing_date` | Redundant with enrichment_timestamp | No |
| `processing_status` | Renamed to status | No |
| `pairing_status` | Replaced by has_fee computation | No |
| `asset_name` | Resolvable from asset master | No |
| `asset_ticker` | Input preserved as context but not a separate F01.05 field | No |
| `payment_date` | Source field from F01.03 | No (already in transaction_date) |
| `payment_amount` | Source field from F01.03 | No (already in original_amount) |
| `payment_description` | Source field from F01.03 | **Yes** — default for bank |
| `other_side_bic` | Source field from F01.03 | **Yes** — default for bank |
| `other_side_account` | Source field from F01.03 | **Yes** — default for bank |
| `other_side_name` | Source field from F01.03 | **Yes** — default for bank |
| `quantity` | Source field from F01.04 | **Yes** — default for secu |
| `price` | Source field from F01.04 | **Yes** — default for secu |
| `version_number` | Renamed to version | No |
| `matching_confidence` | Replaced by type_confidence SelectBox mapping | No |

### 12.2 Description Builder

The `description` field in F01.05 is populated by a configurable **Description Builder** that concatenates selected source fields into a single human-readable string. This preserves useful context from the source transaction (F01.03/F01.04) for operators performing manual resolution, without requiring separate F01.05 fields for each source attribute.

**Configuration:** Two plugin properties control the description builder — one for bank transactions, one for securities. Each property is a comma-separated list of source field names.

| Property | Default Value |
|---|---|
| `descriptionFieldsBank` | `payment_description,reference_number,other_side_name,other_side_bic,other_side_account` |
| `descriptionFieldsSecu` | `description,reference,ticker,quantity,price` |

**Building logic:**
1. Read the appropriate field list (bank or secu) from plugin configuration
2. For each field in the list, get the value from the source transaction row (F01.03 or F01.04)
3. Skip null/empty values
4. Format as: `field_label: value` pairs separated by ` | `
5. Truncate to 2000 characters if needed (preserving complete last field)

**Example output (bank):**

```
payment_description: INVOICE 2024-1234 ACME CORP | reference_number: REF20240315001 | other_side_name: ACME CORPORATION | other_side_bic: DEUTDEFF | other_side_account: DE89370400440532013000
```

**Example output (secu):**

```
description: BUY 500 AAPL NASDAQ | reference: TRD-2024-0315-001 | ticker: AAPL | quantity: 500 | price: 172.50
```

**Rationale:** This approach lets operators see all relevant source context in a single glance during manual review, without bloating F01.05 with redundant columns. The field list is configurable, so if a bank's statements include additional useful fields, they can be added without code changes.

---

## 13. Resolved Questions (formerly Open)

All questions have been resolved. Decisions documented here for traceability.

| # | Question | Resolution | Spec Impact |
|---|---|---|---|
| 1 | For securities transactions, should resolved_customer_id be the bank's own customer record, or left empty? | **Yes — customer must be resolved for both bank and secu.** The customer is the bank's client whose portfolio is being managed. Only rare internal/technical transactions may have no customer. | Section 7.3: execution condition changed to "Always (both bank and secu)". Section 8.4 Customer Resolution note updated. Plugin property `defaultSecuCustomerId` removed. |
| 2 | Should settlement_date use a configurable T+N per asset class, or a single global default? | **Start with global default (T+2), keep extensible.** Can be extended to per-asset-class lookup later without pipeline changes. | Section 8.4: settlement_date includes extensibility note. Section 11.1: `settlementConvention` property documented with extensibility note. |
| 3 | Should the `description` field combine multiple source fields? | **Yes — configurable Description Builder.** A comma-separated list of source field names per source type. Reasonable defaults provided. References are included in the default lists. | New Section 12.2: Description Builder specification. Section 8.4: description source updated. Section 11.1: `descriptionFieldsBank` and `descriptionFieldsSecu` properties added. |
| 4 | How should BUY/SELL map to debit_credit D/C/N for securities? | **Configurable mapping with accounting defaults.** BUY/PURCHASE→D, SELL/DISPOSE→C, DIVIDEND/COUPON/INTEREST→C, CUSTODY_FEE/SAFEKEEPING/FEE→D, TRANSFER_IN/OUT→N, CORPORATE_ACTION→N, unmapped→N+exception. | Section 8.4: debit/credit derivation table added. Section 11.1: `secuDebitCreditMapping` property added. |
| 5 | Should reference_number (bank) and reference (secu) be preserved in F01.05? | **Yes — via Description Builder.** Both `reference_number` and `reference` are included in the default description field lists, so they are preserved in the `description` field without needing separate F01.05 columns. | Covered by Section 12.2 Description Builder. Default field lists include these. |

---

## 14. Unit Test Specification

The rows-enrichment plugin currently ships with **zero test files**. This is unacceptable — the plugin manipulates financial data across 6 pipeline steps and writes 52 fields per enrichment record. Every class must have unit tests before the implementation is considered complete.

### 14.1 Test Framework & Dependencies

**Framework:** JUnit 4.13.2 + Mockito 4.11.0 (consistent with gam-framework).

**pom.xml additions required:**

```xml
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <version>4.11.0</version>
    <scope>test</scope>
</dependency>
```

**Test directory structure:**

```
src/test/java/com/fiscaladmin/gam/enrichrows/
├── loader/
│   └── TransactionDataLoaderTest.java
├── steps/
│   ├── CurrencyValidationStepTest.java
│   ├── CounterpartyDeterminationStepTest.java
│   ├── CustomerIdentificationStepTest.java
│   ├── AssetResolutionStepTest.java
│   ├── F14RuleMappingStepTest.java
│   └── FXConversionStepTest.java
├── persister/
│   └── EnrichmentDataPersisterTest.java
├── framework/
│   ├── DataPipelineTest.java
│   └── DataContextTest.java
├── lib/
│   └── RowsEnricherIntegrationTest.java
└── helpers/
    └── TestDataFactory.java
```

**Pattern:** Follow gam-framework test conventions — `@Mock FormDataDao mockDao`, `MockitoAnnotations.initMocks(this)`, `ArgumentCaptor` for verifying saved data, helper methods for building test fixtures.

### 14.2 Shared Test Utilities — TestDataFactory

A dedicated helper class providing reusable builders for test data. Every test class uses these instead of constructing FormRow/FormRowSet manually.

| Factory Method | Returns | Purpose |
|---|---|---|
| `bankContext(overrides...)` | `DataContext` | Pre-populated bank DataContext with sensible defaults (EUR, valid customer, etc.) |
| `secuContext(overrides...)` | `DataContext` | Pre-populated secu DataContext with sensible defaults (USD, valid ISIN, etc.) |
| `bankSourceRow(overrides...)` | `FormRow` | Mimics F01.03 row with all fields loader would read |
| `secuSourceRow(overrides...)` | `FormRow` | Mimics F01.04 row with all fields loader would read |
| `currencyRow(code, name)` | `FormRow` | Mimics MDM currency lookup result |
| `counterpartyRow(bic, name)` | `FormRow` | Mimics MDM counterparty lookup result |
| `customerRow(id, name)` | `FormRow` | Mimics MDM customer lookup result |
| `assetRow(isin, name, class)` | `FormRow` | Mimics MDM asset master lookup result |
| `f14RuleRow(...)` | `FormRow` | Mimics F14 rule mapping row |
| `fxRateRow(from, to, rate, date)` | `FormRow` | Mimics FX rate lookup result |
| `pluginProperties(overrides...)` | `Map<String, Object>` | Default plugin properties with override capability |

### 14.3 TransactionDataLoaderTest

Tests the loading of bank and securities transactions from CONSOLIDATED statements and construction of DataContext objects.

**Mock setup:** `@Mock FormDataDao`, `@Mock StatusManager`.

| # | Test | Asserts |
|---|---|---|
| 1 | `load_bankStatement_buildsContextsForAllTransactions` | Given a statement with 3 bank rows in `Status.NEW`, returns 3 DataContext objects with `source_type=bank` |
| 2 | `load_secuStatement_buildsContextsForAllTransactions` | Given a statement with 2 secu rows in `Status.NEW`, returns 2 DataContext objects with `source_type=secu` |
| 3 | `load_transitionsSourceTrx_newToProcessing` | Verify `StatusManager.transition()` called with `EntityType.BANK_TRX`, `Status.PROCESSING` for each loaded row |
| 4 | `load_skipsNonNewTransactions` | Given rows with status ENRICHED and ERROR, they are excluded from the returned list |
| 5 | `load_bankContext_allFieldsPopulated` | All core fields from Section 5.1 + bank fields from Section 5.2 are non-null on returned context |
| 6 | `load_secuContext_allFieldsPopulated` | All core fields from Section 5.1 + secu fields from Section 5.3 are non-null on returned context |
| 7 | `load_onlyConsolidatedStatements` | Given statements with status NEW and IMPORTED, they are ignored (only CONSOLIDATED loaded) |
| 8 | `load_emptyStatement_returnsEmptyList` | Statement with zero rows → empty result, no exceptions |
| 9 | `load_statusTransitionFailure_throwsAndAbortsStatement` | If `StatusManager.transition()` throws `InvalidTransitionException`, the statement is skipped with error logged |

### 14.4 CurrencyValidationStepTest

Tests currency validation against MDM currency list (Section 7.1).

**Mock setup:** `@Mock FormDataDao` (for currency lookup queries).

| # | Test | Asserts |
|---|---|---|
| 1 | `execute_validCurrency_stepsSucceeds` | EUR in MDM → `StepResult.success()`, context unchanged |
| 2 | `execute_invalidCurrency_setsError` | ZZZ not in MDM → `StepResult.error()`, context `hasError()=true`, `error_detail` populated |
| 3 | `execute_nullCurrency_setsError` | Null currency on context → error, not NPE |
| 4 | `execute_emptyCurrency_setsError` | Empty string currency → error |
| 5 | `execute_caseSensitivity` | "eur" vs "EUR" — verify behaviour matches implementation (likely case-insensitive) |
| 6 | `shouldExecute_alwaysTrue` | Step executes for both bank and secu |

### 14.5 CounterpartyDeterminationStepTest

Tests BIC-based counterparty lookup (Section 7.2).

**Mock setup:** `@Mock FormDataDao` (for counterparty MDM lookup).

| # | Test | Asserts |
|---|---|---|
| 1 | `execute_knownBic_resolvesCounterparty` | BIC found in MDM → sets `counterparty_id`, `counterparty_name` in additionalData |
| 2 | `execute_unknownBic_setsUnknown` | BIC not in MDM → sets `counterparty_id="UNKNOWN"`, `counterparty_name="UNKNOWN"` |
| 3 | `execute_nullBic_setsUnknown` | Null BIC → UNKNOWN, not NPE |
| 4 | `execute_emptyBic_setsUnknown` | Empty string BIC → UNKNOWN |
| 5 | `execute_multipleBicMatches_usesFirst` | If lookup returns multiple rows, first match is used |
| 6 | `shouldExecute_alwaysTrue` | Executes for both bank and secu source types |

### 14.6 CustomerIdentificationStepTest

Tests customer resolution for both bank and secu (Section 7.3 — changed from bank-only).

**Mock setup:** `@Mock FormDataDao` (for customer MDM lookup).

| # | Test | Asserts |
|---|---|---|
| 1 | `execute_bank_knownAccount_resolvesCustomer` | Account found → sets `customer_id`, `customer_name`, `customer_confidence ≥ 80` |
| 2 | `execute_bank_unknownAccount_setsUnknown` | Account not in MDM → `customer_id="UNKNOWN"` |
| 3 | `execute_secu_resolvesCustomer` | **Critical: secu now also resolves customer** (Section 7.3 change) |
| 4 | `execute_secu_unknownPortfolio_setsUnknown` | Portfolio not matched → `customer_id="UNKNOWN"` |
| 5 | `shouldExecute_bank_returnsTrue` | Execution condition: always true for bank |
| 6 | `shouldExecute_secu_returnsTrue` | **Critical: must now return true for secu** (verifies Section 7.3 change) |
| 7 | `execute_lowConfidence_belowThreshold` | Match exists but confidence < threshold → still resolves but confidence recorded for manual review trigger |
| 8 | `execute_properties_confidenceThreshold` | Verify `setProperties()` passes custom confidence threshold and it is respected |

### 14.7 AssetResolutionStepTest

Tests the **new** AssetResolutionStep (Section 7.4).

**Mock setup:** `@Mock FormDataDao` (for asset_master lookup).

| # | Test | Asserts |
|---|---|---|
| 1 | `execute_secu_knownIsin_resolvesAsset` | ISIN found in asset_master → sets `asset_id`, `asset_name`, `asset_class` |
| 2 | `execute_secu_unknownIsin_setsUnknown` | ISIN not in asset_master → `asset_id="UNKNOWN"` |
| 3 | `execute_secu_nullIsin_setsUnknown` | No ISIN on context → UNKNOWN, not NPE |
| 4 | `execute_bank_isSkipped` | Bank source type → step does not execute (assets are securities-specific) |
| 5 | `shouldExecute_secu_returnsTrue` | Execution condition: true for secu |
| 6 | `shouldExecute_bank_returnsFalse` | Execution condition: false for bank |
| 7 | `execute_enrichesAssetClassFromMaster` | Verify `asset_class` is populated from F02.02 field |
| 8 | `execute_lookupByIsinField` | Verify the MDM query uses the correct ISIN field name from source context |

### 14.8 F14RuleMappingStepTest

Tests rule-based classification of transactions to internal types (Section 7.5).

**Mock setup:** `@Mock FormDataDao` (for F14 rule table lookup).

| # | Test | Asserts |
|---|---|---|
| 1 | `execute_matchingRule_setsInternalType` | Rule match found → sets `internal_type` from rule |
| 2 | `execute_noMatchingRule_setsUnmatched` | No rule matches → `internal_type="UNMATCHED"` |
| 3 | `execute_multipleMatchingRules_usesHighestPriority` | Multiple rules match → highest priority (or first match) wins |
| 4 | `execute_ruleWithAccountingType_setsAccountingType` | Rule provides accounting classification → sets `accounting_type` |
| 5 | `execute_bank_usesCorrectLookupFields` | Bank context → rule matching uses bank-specific fields (e.g. payment_code) |
| 6 | `execute_secu_usesCorrectLookupFields` | Secu context → rule matching uses secu-specific fields (e.g. transaction_type_code) |
| 7 | `shouldExecute_alwaysTrue` | Executes for both source types |

### 14.9 FXConversionStepTest

Tests FX rate lookup and amount conversion (Section 7.6).

**Mock setup:** `@Mock FormDataDao` (for FX rate table lookup).

| # | Test | Asserts |
|---|---|---|
| 1 | `execute_sameCurrency_noConversion` | Transaction currency = reporting currency → `amount_reporting = amount_original`, `fx_rate = 1.0`, `fx_rate_source = "BASE_CURRENCY"` (per Section 7.6) |
| 2 | `execute_differentCurrency_convertsAmount` | EUR→CHF, rate 0.95 → `amount_reporting = amount_original × 0.95`, `fx_rate = 0.95` |
| 3 | `execute_noRateFound_setsError` | No FX rate available → `fx_rate_source = "MISSING"`, step records error |
| 4 | `execute_staleRate_respectsMaxAge` | Rate older than `maxRateAgeDays` property → treated as missing |
| 5 | `execute_maxRateAgeDays_fromProperties` | Verify `setProperties()` passes custom MAX_RATE_AGE_DAYS and it is respected |
| 6 | `execute_nullAmount_setsError` | Null original amount → error, not NPE |
| 7 | `execute_zeroAmount_convertsCorrectly` | Zero amount × rate → zero reporting amount (not error) |
| 8 | `execute_numericPrecision_fourDecimals` | Verify converted amount preserves 4 decimal places |
| 9 | `shouldExecute_alwaysTrue` | Executes for both source types |

### 14.10 EnrichmentDataPersisterTest

Tests the core persistence logic — 52-field mapping, status determination, Description Builder, debit/credit mapping. This is the most critical test class.

**Mock setup:** `@Mock FormDataDao`, `@Mock StatusManager`.

**14.10.1 — Field Mapping (52 fields)**

| # | Test | Asserts |
|---|---|---|
| 1 | `persist_bank_allFieldsMapped` | Bank context → saved FormRow contains all 52 fields from Section 8.4 with correct values |
| 2 | `persist_secu_allFieldsMapped` | Secu context → saved FormRow contains all 52 fields with correct secu-specific values |
| 3 | `persist_recordId_format` | Verify generated record ID follows the format from Section 8.3 |
| 4 | `persist_enrichmentDate_isNow` | `enrichment_date` is set to current timestamp |
| 5 | `persist_sourceType_bank` | Bank context → `source_type = "bank"` |
| 6 | `persist_sourceType_secu` | Secu context → `source_type = "secu"` |

**14.10.2 — Status Determination (Section 8.5)**

| # | Test | Asserts |
|---|---|---|
| 7 | `persist_allStepsOk_statusEnriched` | No errors, no UNKNOWN, no UNMATCHED → `StatusManager.transition()` called with `Status.ENRICHED` |
| 8 | `persist_unknownCustomer_statusManualReview` | `customer_id="UNKNOWN"` → transition to `Status.MANUAL_REVIEW` |
| 9 | `persist_unknownCounterparty_statusManualReview` | `counterparty_id="UNKNOWN"` → `Status.MANUAL_REVIEW` |
| 10 | `persist_unmatchedInternalType_statusManualReview` | `internal_type="UNMATCHED"` → `Status.MANUAL_REVIEW` |
| 11 | `persist_lowConfidence_statusManualReview` | `customer_confidence < 80` → `Status.MANUAL_REVIEW` |
| 12 | `persist_unknownAsset_secu_statusManualReview` | Secu with `asset_id="UNKNOWN"` → `Status.MANUAL_REVIEW` |
| 13 | `persist_missingFxRate_statusManualReview` | `fx_rate_source="MISSING"` → `Status.MANUAL_REVIEW` |
| 14 | `persist_contextError_statusError` | `context.hasError()=true` → transition to `Status.ERROR` |
| 15 | `persist_multipleManualReviewTriggers_singleTransition` | Multiple UNKNOWN fields → still one transition to MANUAL_REVIEW with combined reason |

**14.10.3 — StatusManager Integration (Sections 8.5, 8.6, 8.7)**

| # | Test | Asserts |
|---|---|---|
| 16 | `persist_enrichmentLifecycle_3transitions` | Verify exactly 3 transitions: null→NEW, NEW→PROCESSING, PROCESSING→{final} using `ArgumentCaptor` |
| 17 | `persist_usesCustomTableName_trxEnrichment` | Verify transitions use `"trxEnrichment"` (not `"trx_enrichment"`) for ENRICHMENT entity |
| 18 | `persist_sourceTransactionStatus_enriched` | After enriched record saved, source BANK_TRX transitions to ENRICHED |
| 19 | `persist_sourceTransactionStatus_error` | After error record, source BANK_TRX transitions to ERROR |
| 20 | `persist_sourceTransactionStatus_manualReview` | After manual review record, source transitions to MANUAL_REVIEW |
| 21 | `persist_invalidTransition_createsException` | If `StatusManager.transition()` throws `InvalidTransitionException`, exception queue entry is created |

**14.10.4 — Description Builder (Section 12.2)**

| # | Test | Asserts |
|---|---|---|
| 22 | `persist_bank_description_defaultFields` | Bank → description contains `payment_description`, `reference_number`, `other_side_name`, `other_side_bic`, `other_side_account` separated by ` \| ` |
| 23 | `persist_secu_description_defaultFields` | Secu → description contains `description`, `reference`, `ticker`, `quantity`, `price` |
| 24 | `persist_description_skipsNullFields` | Source field is null → omitted from description (no "null" text) |
| 25 | `persist_description_skipsEmptyFields` | Source field is empty string → omitted |
| 26 | `persist_description_truncatesAt2000` | Very long values → truncated to 2000 chars preserving complete last field |
| 27 | `persist_description_customFieldList` | Plugin property overrides default list → only configured fields appear |
| 28 | `persist_description_fieldLabelFormat` | Each field formatted as `field_name: value` |

**14.10.5 — Debit/Credit Mapping (Section 8.4, Q13.4)**

| # | Test | Asserts |
|---|---|---|
| 29 | `persist_bank_debitCredit_fromSource` | Bank → `debit_credit` copied directly from F01.03 source field |
| 30 | `persist_secu_buy_debit` | Secu BUY → `debit_credit = "D"` |
| 31 | `persist_secu_sell_credit` | Secu SELL → `debit_credit = "C"` |
| 32 | `persist_secu_dividend_credit` | Secu DIVIDEND → `debit_credit = "C"` |
| 33 | `persist_secu_fee_debit` | Secu CUSTODY_FEE → `debit_credit = "D"` |
| 34 | `persist_secu_transfer_neutral` | Secu TRANSFER_IN → `debit_credit = "N"` |
| 35 | `persist_secu_unmappedType_neutral_plusException` | Unmapped type → `debit_credit = "N"` and exception queue entry created |
| 36 | `persist_secu_customMapping` | Plugin property `secuDebitCreditMapping` overrides defaults → verified |

**14.10.6 — Settlement Date (Section 8.4)**

| # | Test | Asserts |
|---|---|---|
| 37 | `persist_settlementDate_defaultTPlus2` | Transaction date + 2 business days = settlement date |
| 38 | `persist_settlementDate_customConvention` | Plugin property `settlementConvention=3` → T+3 |
| 39 | `persist_settlementDate_weekendSkip` | Friday transaction → settlement date is next Tuesday (skips weekend) |

**14.10.7 — Batch Completion (Section 8.7)**

| # | Test | Asserts |
|---|---|---|
| 40 | `batchComplete_allSuccess_statementEnriched` | 0 failures → `StatusManager.transition()` called with `Status.ENRICHED` on statement |
| 41 | `batchComplete_anyFailure_statementError` | ≥1 failures → statement transitions to `Status.ERROR` |
| 42 | `batchComplete_updatesStatementMetadata` | `processing_completed`, `transactions_processed`, `transactions_success`, `transactions_failed` all set |

### 14.11 DataPipelineTest

Tests the pipeline orchestration (step ordering, error propagation, result aggregation).

**Mock setup:** `@Mock DataStep` (multiple), `@Mock FormDataDao`.

| # | Test | Asserts |
|---|---|---|
| 1 | `execute_runsStepsInOrder` | Steps execute in registration order (1→6), verified via `InOrder` |
| 2 | `execute_stepError_stopsExecution` | Step 2 returns error → steps 3–6 are NOT executed |
| 3 | `execute_stepError_aggregatesInResult` | Error from step → `PipelineResult.hasError()=true`, error message captured |
| 4 | `execute_allStepsSuccess_resultOk` | All 6 steps succeed → `PipelineResult.hasError()=false` |
| 5 | `execute_stepShouldExecuteFalse_isSkipped` | Step's `shouldExecute()` returns false → step skipped, next step runs |
| 6 | `execute_propertiesPassed_toAllSteps` | After `setProperties()`, all steps receive the properties map |
| 7 | `execute_emptyPipeline_succeeds` | No steps added → result OK (no NPE) |

### 14.12 DataContextTest

Tests DataContext data integrity (Section 5).

| # | Test | Asserts |
|---|---|---|
| 1 | `setAndGet_coreFields` | All core fields (Section 5.1) round-trip correctly |
| 2 | `setAndGet_bankFields` | All bank-specific fields (Section 5.2) round-trip |
| 3 | `setAndGet_secuFields` | All secu-specific fields (Section 5.3) round-trip |
| 4 | `additionalData_putAndGet` | additionalData map stores and retrieves arbitrary keys |
| 5 | `hasError_defaultFalse` | New context → `hasError()=false` |
| 6 | `setError_hasErrorTrue` | After setting error → `hasError()=true` |

### 14.13 RowsEnricherIntegrationTest

End-to-end test of the full plugin pipeline with all components wired together but database mocked.

**Mock setup:** `@Mock FormDataDao` (all lookup queries mocked with representative data), real `StatusManager`, real pipeline steps, real persister.

| # | Test | Asserts |
|---|---|---|
| 1 | `enrich_bankTransaction_happyPath` | Complete bank flow: load → 6 steps → persist → verify all 52 fields on saved FormRow |
| 2 | `enrich_secuTransaction_happyPath` | Complete secu flow: load → 6 steps → persist → verify all 52 fields |
| 3 | `enrich_bankTransaction_unknownCounterparty` | Unknown BIC → enrichment record has status MANUAL_REVIEW, exception queue entry created |
| 4 | `enrich_secuTransaction_unknownAsset` | Unknown ISIN → MANUAL_REVIEW, exception created |
| 5 | `enrich_invalidCurrency_errorStatus` | Invalid currency → ERROR status, pipeline stops after step 1 |
| 6 | `enrich_batchOf3_mixedResults` | 3 transactions (1 success, 1 manual review, 1 error) → correct counts, statement transitions to ERROR |
| 7 | `enrich_allSuccess_statementEnriched` | All transactions succeed → statement transitions to ENRICHED |
| 8 | `enrich_statusTransitions_verifyOrder` | Verify complete transition sequence: source NEW→PROCESSING, enrichment null→NEW→PROCESSING→{final}, source →{final} |

### 14.14 Coverage Target & Constraints

**Minimum coverage target:** 85% line coverage, 80% branch coverage across all classes except `Activator.java` (OSGi boilerplate, exempt from testing).

**What must NOT be tested:**
- `Activator.java` — OSGi lifecycle, tested by the container
- Joget platform internals (`FormDataDao` implementation, `FormRow` internals) — mocked in all tests

**What MUST reach 100% branch coverage:**
- `needsManualReview()` logic in persister (Section 8.5) — every condition tested individually and in combination
- Debit/credit mapping (every mapped type + unmapped fallback)
- Description Builder field-skipping logic (null, empty, truncation)

**Build integration:** Tests run automatically via `mvn test` (already configured in pom.xml with `maven-surefire-plugin`). CI must fail on test failures — `<skipTests>false</skipTests>` is already set.

**Test naming convention:** `methodUnderTest_scenario_expectedBehaviour` (consistent with gam-framework tests).

---

## 15. Joget Plugin Integration Specification

The rows-enrichment plugin extends `DefaultApplicationPlugin` (a Joget **Process Tool Plugin**). It is designed to run as a tool within a Joget workflow process. This section specifies how the plugin integrates with the Joget platform.

### 15.1 Plugin Registration & Lifecycle

**Plugin class:** `com.fiscaladmin.gam.enrichrows.lib.RowsEnricher`
**Extends:** `org.joget.plugin.base.DefaultApplicationPlugin`
**OSGi Activator:** `com.fiscaladmin.gam.Activator` — registers the plugin with Joget's plugin manager on bundle start.

**Lifecycle:**
1. OSGi container loads the bundle and calls `Activator.start()`
2. Workflow reaches the tool activity mapped to this plugin
3. Joget calls `RowsEnricher.execute(Map properties)` with the properties configured in the workflow tool mapping
4. Plugin returns a result string (success summary or error message)
5. Workflow continues to the next activity

**Required overrides:**

| Method | Current Value | Change |
|---|---|---|
| `getName()` | `"Rows Enrichment"` | No change |
| `getDescription()` | `"This plugin will enrich statement rows"` | No change |
| `getVersion()` | `"8.1-SNAPSHOT"` | No change |
| `getLabel()` | `"Rows Enrichment"` | No change |
| `getClassName()` | `getClass().getName()` | No change |
| `getPropertyOptions()` | `AppUtil.readPluginResource(getClass().getName(), "", null, true, null)` | **Change:** Load from `/properties/app/RowsEnricher.json` (see Section 15.2) |

### 15.2 Property Options JSON — NEW file

**File:** `src/main/resources/properties/app/RowsEnricher.json`

This file defines the configuration UI that Joget renders when an administrator maps this plugin to a workflow tool activity. Every property from Section 11.1 must appear here. Format follows the Joget property options JSON convention (array of page objects, each with title and properties array).

**Joget property types used:**

| Type | Joget JSON type | Used For |
|---|---|---|
| Text input | `textfield` | baseCurrency, descriptionFieldsBank, descriptionFieldsSecu, secuDebitCreditMapping, descriptionMaxLength |
| Number input | `textfield` + `regex_validation` | maxFxRateAgeDays, batchSize, confidenceThresholdHigh, confidenceThresholdMedium |
| Select box | `selectbox` | settlementConvention, stopOnError |
| Read-only | `textfield` + `readonly: true` | pipelineVersion (for lineage, not user-editable) |

**Required structure:**

```json
[{
    "title": "General",
    "properties": [
        { "name": "baseCurrency", "label": "Base / Reporting Currency", "type": "textfield", "value": "EUR",
          "description": "ISO 4217 code for the reporting currency" },
        { "name": "pipelineVersion", "label": "Pipeline Version", "type": "textfield", "value": "2.0",
          "description": "Version identifier written to every enrichment record for lineage tracking",
          "readonly": "true" },
        { "name": "batchSize", "label": "Batch Size", "type": "textfield", "value": "100",
          "description": "Maximum number of statements to process per plugin execution",
          "regex_validation": "^[0-9]+$", "validation_message": "Must be a positive integer" },
        { "name": "stopOnError", "label": "Stop Pipeline on Error", "type": "selectbox", "value": "false",
          "options": [{ "value": "false", "label": "Continue (process all transactions)" },
                      { "value": "true", "label": "Stop (abort on first step failure)" }],
          "description": "Whether the pipeline stops on first step failure or continues" }
    ]
}, {
    "title": "Enrichment Pipeline",
    "properties": [
        { "name": "confidenceThresholdHigh", "label": "High Confidence Threshold (%)", "type": "textfield",
          "value": "80", "description": "Minimum confidence for automatic enrichment (below → MANUAL_REVIEW)",
          "regex_validation": "^[0-9]+$", "validation_message": "Must be 0-100" },
        { "name": "confidenceThresholdMedium", "label": "Medium Confidence Threshold (%)", "type": "textfield",
          "value": "50", "description": "Minimum confidence for medium classification",
          "regex_validation": "^[0-9]+$", "validation_message": "Must be 0-100" },
        { "name": "maxFxRateAgeDays", "label": "Max FX Rate Age (days)", "type": "textfield",
          "value": "5", "description": "FX rates older than this are treated as missing",
          "regex_validation": "^[0-9]+$", "validation_message": "Must be a positive integer" },
        { "name": "settlementConvention", "label": "Settlement Convention", "type": "selectbox",
          "value": "2",
          "options": [{ "value": "1", "label": "T+1" }, { "value": "2", "label": "T+2" },
                      { "value": "3", "label": "T+3" }, { "value": "5", "label": "T+5" }],
          "description": "Global settlement lag for securities. Extensible to per-asset-class later" }
    ]
}, {
    "title": "Description Builder",
    "properties": [
        { "name": "descriptionFieldsBank", "label": "Bank Description Fields",
          "type": "textfield",
          "value": "payment_description,reference_number,other_side_name,other_side_bic,other_side_account",
          "description": "Comma-separated list of F01.03 fields to include in F01.05 description" },
        { "name": "descriptionFieldsSecu", "label": "Securities Description Fields",
          "type": "textfield",
          "value": "description,reference,ticker,quantity,price",
          "description": "Comma-separated list of F01.04 fields to include in F01.05 description" },
        { "name": "descriptionMaxLength", "label": "Max Description Length",
          "type": "textfield", "value": "2000",
          "description": "Maximum character length for built description (truncates preserving last complete field)",
          "regex_validation": "^[0-9]+$", "validation_message": "Must be a positive integer" }
    ]
}, {
    "title": "Securities Debit/Credit Mapping",
    "properties": [
        { "name": "secuDebitCreditMapping", "label": "Transaction Type → D/C/N Mapping",
          "type": "textfield",
          "value": "BUY:D,PURCHASE:D,SELL:C,DISPOSE:C,DIVIDEND:C,COUPON:C,INTEREST:C,CUSTODY_FEE:D,SAFEKEEPING:D,FEE:D,TRANSFER_IN:N,TRANSFER_OUT:N,CORPORATE_ACTION:N",
          "description": "Comma-separated TYPE:D/C/N pairs. Unmapped types default to N with exception queue entry" }
    ]
}]
```

**Property access in code:** `RowsEnricher.execute(Map properties)` receives all configured values. Access via `properties.get("baseCurrency")`. The `properties` map is passed to `TransactionDataLoader.loadData(dao, properties)` and `EnrichmentDataPersister.persistBatch(...)`, and to each pipeline step via `step.setProperties(properties)`.

### 15.3 RowsEnricher.execute() — Updated Flow

The `execute(Map properties)` method must be restructured per the spec. The current implementation has wrong step order, missing steps, and no StatusManager. The target flow:

```
1. Obtain FormDataDao via AppUtil.getApplicationContext().getBean("formDataDao")
2. Instantiate StatusManager
3. Create TransactionDataLoader, pass StatusManager
4. Load transactions: dataLoader.loadData(dao, properties)
5. Create DataPipeline with 6 steps in correct order (Appendix A)
6. Pass properties to all steps via setProperties()
7. Execute pipeline: pipeline.executeBatch(transactions)
8. Create EnrichmentDataPersister, pass StatusManager
9. Persist: persister.persistBatch(transactions, batchResult, dao, properties)
10. Return summary string
```

**Key change from current code:** StatusManager must be instantiated once and shared with the loader, persister, and any steps that need it (via the properties map or constructor injection). It must NOT be instantiated per-record.

### 15.4 Workflow Activity Mapping

When mapping this plugin to a workflow tool activity in Joget App Designer:

1. Open the workflow process in Joget App Designer
2. Add or edit a **Tool** activity
3. In the tool mapping, select **"Rows Enrichment"** from the plugin list
4. Configure properties via the UI rendered from Section 15.2
5. The plugin receives the configured properties in the `execute(Map properties)` call

**No workflow variables are read or written** by this plugin. All input comes from database queries (CONSOLIDATED statements) and all output goes to database writes (F01.05 records, status transitions, exception queue, audit log). The return string is for logging/monitoring only.

---

## Appendix A: Current vs Target Pipeline Order

| Current Order | Target Order | Change |
|---|---|---|
| 1. CurrencyValidation | 1. CurrencyValidation | No change |
| 2. FXConversion | 2. CounterpartyDetermination | **Moved up** |
| 3. CustomerIdentification | 3. CustomerIdentification | No change |
| 4. CounterpartyDetermination | 4. AssetResolution | **NEW step** |
| 5. F14RuleMapping | 5. F14RuleMapping | No change |
| — | 6. FXConversion | **Moved to last** |

**Rationale for moving FXConversion to last:**
- FX conversion may optionally use `internal_type` to choose between different rate date strategies (trade date vs ex-date vs payment date for dividends)
- Counterparty must be known before F14 rule matching
- Asset resolution may inform F14 rules (asset-class-based rules)
- Currency validation must happen first (FX needs valid currency)

## Appendix B: Files To Modify

### B.1 Build Configuration

| File | Changes |
|---|---|
| `pom.xml` | **Add gam-framework dependency** (com.fiscaladmin.gam:gam-framework:8.1-SNAPSHOT). **Add mockito-core 4.11.0 test dependency** (see Section 14.1) |
| `src/main/resources/properties/app/RowsEnricher.json` | **NEW file.** Joget property options JSON defining the configuration UI (see Section 15.2) |

### B.2 Constants & Framework

| File | Changes |
|---|---|
| `DomainConstants.java` | Fix TABLE_TRX_ENRICHMENT → "trxEnrichment", add TABLE_ASSET_MASTER = "asset_master" |
| `FrameworkConstants.java` | **Major cleanup** — remove all status constants (replaced by `Status` enum from gam-framework), remove PIPELINE_VERSION, SYSTEM_USER. Keep only: STATUS_ACTIVE, ENTITY_UNKNOWN, ENTITY_SYSTEM, INTERNAL_TYPE_UNMATCHED (see Section 11.3) |
| `DataStep.java` (interface) | Add `setProperties(Map<String, Object>)` method for config passing (see Section 11.4) |
| `AbstractDataStep.java` | **Remove** hardcoded `STATUS_NEW`, `STATUS_ENRICHED`, `STATUS_FAILED`, `STATUS_POSTED` constants (lines 11-14). **Remove or rework** `updateTransactionStatus()` method — must not write status directly, use `StatusManager.transition()` instead. Implement default `setProperties()`, add `StatusManager` field and helper method for status transitions |

### B.3 Pipeline Core

| File | Changes |
|---|---|
| `RowsEnricher.java` | Reorder steps (see Appendix A), add AssetResolutionStep, pass config properties to steps via `setProperties()`, instantiate `StatusManager`, **change `getPropertyOptions()`** to load from `/properties/app/RowsEnricher.json` (see Section 15.1). Restructure `execute()` per Section 15.3 |
| `TransactionDataLoader.java` | Use `StatusManager` for BANK_TRX/SECU_TRX NEW→PROCESSING transitions. Ensure all F01.04 fields loaded. Replace status string literals with `Status` enum |
| `DataContext.java` | No structural changes needed; additionalData map handles new fields |
| `DataPipeline.java` | Pass properties map to steps after addStep() |

### B.4 Pipeline Steps

| File | Changes |
|---|---|
| `CurrencyValidationStep.java` | Implement `setProperties()`, use configurable properties, replace status string literals with `Status` enum |
| `CounterpartyDeterminationStep.java` | Replace status string literals with `Status` enum. No logic changes |
| `CustomerIdentificationStep.java` | **Change execution condition** to run for both bank and secu (remove sourceType=="bank" guard). Implement `setProperties()` for configurable confidence threshold. Replace status string literals |
| `AssetResolutionStep.java` | **NEW file** — full implementation per Section 7.4 |
| `F14RuleMappingStep.java` | Replace status string literals with `Status` enum. No logic changes |
| `FXConversionStep.java` | Implement `setProperties()` for configurable MAX_RATE_AGE_DAYS. Replace status string literals |

### B.5 Persistence

| File | Changes |
|---|---|
| `EnrichmentDataPersister.java` | **Major rewrite.** Use `StatusManager` for all status transitions (ENRICHMENT, BANK_TRX, SECU_TRX, EXCEPTION entities). Implement complete 52-field mapping per Section 8.4. Implement Description Builder (Section 12.2). Implement configurable debit/credit mapping. Implement settlement_date T+N. Remove all dropped fields (Section 12.1). Remove manual audit writes for status changes (now auto via StatusManager) |
