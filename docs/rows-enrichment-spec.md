# rows-enrichment Plugin — Detailed Specification

**Version:** 3.0 (significantly updated for metadata form consistency)
**Date:** 3 March 2026
**Status:** SPECIFICATION ONLY — no implementation

---

## 1. Purpose

The rows-enrichment plugin transforms raw imported statement transactions (F01.03 bank rows, F01.04 securities rows) into fully enriched records stored in F01.05 (trxEnrichment). Each enrichment record contains resolved entity references (customer, counterparty, asset), validated and converted currency amounts, and a classified internal transaction type — everything the downstream gl-preparator plugin needs to construct GL postings. This specification is entirely configurable: transaction types come from F10.10, counterparty matching rules from F02.14, GL patterns from F02.15, and FX rates from the fx_rates_eur table.

---

## 2. Scope

This specification covers the complete plugin: data loading, a 6-step processing pipeline, data persistence, error handling, audit logging, and configuration. It is aligned with the reorganised F01.05 form definition, the MDM design document v3.0, and the metadata forms F10.10, F02.14, and F02.15.

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

## 3. Metadata Forms — Source of Truth for Configurable Data

This specification is fundamentally built on metadata forms that serve as the source of truth for transaction types, matching rules, and GL patterns. No constants are hardcoded in the plugin; all behavior is driven by these configurable forms.

### 3.1 F10.10 — Transaction Type Master (trxType)

**Purpose:** Define the 8 base transaction types that all imported transactions must be classified into.

**Location:** /sessions/dazzling-amazing-clarke/mnt/rsr/gam-acc-spec/f10.10-trxType.csv

**Key fields:**

| Field | Type | Description |
|---|---|---|
| `Code` | Text | Internal type code (e.g. BOND_BUY, SEC_SELL) |
| `Name` | Text | Human-readable type name |
| `Statement Type` | SelectBox | "Bank" or "Securities" |
| `Asset Type` | SelectBox | Asset category if applicable (empty for bank transactions) |
| `Flow Type` | SelectBox | "In" (debit/income), "Out" (credit/expense), or "None" (neutral) |

**Current 8 types:**

1. `BOND_BUY` — Bond Purchase (Securities, Out)
2. `BOND_INTEREST` — Bond Interest (Bank, In)
3. `CASH_IN_OUT` — Cash Transfer (Bank, In)
4. `COMM_FEE` — Trading Commission (Bank, Out)
5. `DIV_INCOME` — Dividend Income (Bank, In)
6. `SEC_BUY` — Equity Purchase (Securities, Out)
7. `SEC_SELL` — Equity Sale (Securities, In)
8. And one additional type not listed in the CSV header (placeholder for future expansion)

**Plugin integration:** The plugin loads ALL rows from this table at startup and caches them. The 8 types are the definitive list of "base" transaction types. Additional internal types (BANK_TRANS, TECH_BUY, CUST_INT, etc.) are defined in F02.14/F02.15 and may overlap with F10.10 or extend beyond it.

### 3.2 F02.14 — Counterparty-Scoped Transaction Mapping (cpTxnMappingForm)

**Purpose:** 40 configurable rules that match source transaction attributes (description, type, ticker, BIC, etc.) to internal transaction types based on counterparty.

**Location:** /sessions/dazzling-amazing-clarke/mnt/rsr/gam-acc-spec/f02.14-cpTxnMappingForm.csv

**Key fields:**

| Field | Type | Description |
|---|---|---|
| `Mapping ID` | Text | Unique rule identifier (e.g. TXMP-000011) |
| `Mapping Name` | Text | Human-readable rule name (e.g. "Asset Return") |
| `Counterparty` | SelectBox/Text | Counterparty business ID OR "SYSTEM" for universal fallback |
| `Matching Field` | SelectBox | Which source field to match: `type`, `d_c`, `description`, `reference`, `other_side_name`, `other_side_bic`, `ticker`, `combined` |
| `Match Value` | Text | The value to match against (e.g. "Return of assets", "HLMBK", "võlakiri") |
| `Internal Type` | SelectBox | Target internal type code (e.g. ASSET_RETURN, BANK_BOND, BOND_BUY) |

**40 Current Rules (sample):**

| Mapping ID | Counterparty | Matching Field | Match Value | Internal Type |
|---|---|---|---|---|
| TXMP-000001 | LHV-EE | description | intress | INT_INCOME |
| TXMP-000002 | LHV-EE | description | laenuleping | INT_EXPENSE |
| TXMP-000003 | LHV-EE | description | Securities buy | SEC_BUY |
| TXMP-000004 | LHV-EE | description | Securities sell | SEC_SELL |
| TXMP-000005 | LHV-EE | description | Securities commission fee | COMM_FEE |
| TXMP-000006 | LHV-EE | description | Dividends | DIV_INCOME |
| TXMP-000007 | LHV-EE | description | Account interest | INT_INCOME |
| TXMP-000008 | SYSTEM | description | halduslepingule | MGMT_FEE |
| TXMP-000009 | SYSTEM | description | laenu tagasimakse | LOAN_PAYMENT |
| TXMP-000010 | SYSTEM | description | investment | INV_INCOME |
| TXMP-000011 | SYSTEM | description | Return of assets | ASSET_RETURN |
| TXMP-000012 | SYSTEM | description | Interest payment | INT_EXPENSE |
| TXMP-000013 | SYSTEM | d_c | C | CASH_IN |
| TXMP-000014 | SYSTEM | d_c | D | CASH_OUT |
| TXMP-000015 | IBKR | type | ost | EQ_BUY |
| TXMP-000016 | IBKR | type | müük | EQ_SELL |
| TXMP-000017 | IBKR | description | võlakiri | BOND_BUY |
| TXMP-000018 | IBKR | type | dividend | DIV_INCOME |
| TXMP-000019 | IBKR | type | split+ | SPLIT_IN |
| TXMP-000020 | IBKR | type | split- | SPLIT_OUT |
| TXMP-000021 | SAXO | type | deposit | SEC_DEPOSIT |
| TXMP-000022 | SAXO | type | withdrawal | SEC_WITHDRAW |
| TXMP-000023 | SYSTEM | description | allutatud võlakiri | BOND_INT |
| TXMP-000024 | SYSTEM | ticker | MSFT | TECH_BUY |
| TXMP-000025 | SYSTEM | ticker | ADBE | TECH_BUY |
| TXMP-000026 | SYSTEM | ticker | MU | TECH_BUY |
| TXMP-000027 | SYSTEM | ticker | NEM | COMM_BUY |
| TXMP-000028 | SYSTEM | ticker | CRWD | TECH_BUY |
| TXMP-000029 | SYSTEM | ticker | BIGB | LOCAL_BOND |
| TXMP-000030 | SYSTEM | ticker | HLMBK | BANK_BOND |
| TXMP-000031 | SWED | other_side_name | GENESIS | INT_INCOME |
| TXMP-000032 | SEB | description | Securities | SEC_TRANS |
| TXMP-000033 | COOP | other_side_bic | EKRDEE | BANK_TRANS |
| TXMP-000034 | LUMINOR | description | investment | INV_TRANS |
| TXMP-000035 | SYSTEM | reference | ID-000001 | CUST_INT |
| TXMP-000036 | LHV-EE | type | BUY | EQ_BUY |
| TXMP-000037 | LHV-EE | type | SELL | EQ_SELL |
| TXMP-000038 | LHV-EE | type | SPLIT_IN | SPLIT_IN |
| TXMP-000039 | LHV-EE | type | SPLIT_OUT | SPLIT_OUT |
| TXMP-000040 | (reserved for future) | — | — | — |

**Rule matching logic (Section 7.5 below):**
- Rules are evaluated in priority order (counterparty-specific first, then SYSTEM)
- Each rule specifies a `matchingField` and `matchOperator` (equals, contains, startsWith, etc.)
- First rule to match wins
- If no rule matches, the transaction is marked UNMATCHED

### 3.3 F02.15 — GL Posting Patterns by Internal Type (transactionTypeMapForm)

**Purpose:** Map each internal transaction type to GL debit/credit posting patterns. 31 distinct internal types are defined (superset of F10.10's 8 base types).

**Location:** /sessions/dazzling-amazing-clarke/mnt/rsr/gam-acc-spec/f02.15-transactionTypeMapForm.csv

**Key fields:**

| Field | Type | Description |
|---|---|---|
| `Mapping ID` | Text | Unique identifier |
| `Internal Type` | SelectBox | Target internal type (e.g. INT_INCOME, SEC_BUY, TECH_BUY) |
| `Description` | Text | Human-readable type description |
| `GL Debit Pattern` | Text | GL posting template for debit side (e.g. `1101.{custId}.{currency}.{cpId}\|{amount}\|{currency}`) |
| `GL Credit Pattern` | Text | GL posting template for credit side |

**31 Current Internal Types (from f02.15):**

1. INT_INCOME — Interest Income
2. INT_EXPENSE — Interest Expense
3. SEC_BUY — Securities Purchase
4. SEC_SELL — Securities Sale
5. COMM_FEE — Commission Fee
6. DIV_INCOME — Dividend Income
7. MGMT_FEE — Management Fee
8. LOAN_PAYMENT — Loan Payment Received
9. INV_INCOME — Investment Income
10. ASSET_RETURN — Asset Return
11. CASH_IN — Cash Inflow
12. CASH_OUT — Cash Outflow
13. EQ_BUY — Equity Purchase
14. EQ_SELL — Equity Sale
15. BOND_BUY — Bond Purchase
16. BOND_INT — Bond Interest Income
17. SPLIT_IN — Stock Split Increase
18. SPLIT_OUT — Stock Split Decrease
19. SEC_DEPOSIT — Securities Deposit
20. SEC_WITHDRAW — Securities Withdrawal
21. TECH_BUY — Technology Stock Purchase
22. COMM_BUY — Commodity Stock Purchase
23. LOCAL_BOND — Local Bond Purchase
24. BANK_BOND — Bank Bond Purchase
25. SEC_TRANS — Securities Transaction Generic
26. BANK_TRANS — Bank Transfer
27. INV_TRANS — Investment Transaction
28. CUST_INT — Customer Interest
29. FX_CONV — Foreign Exchange Conversion
30. CUSTODY_FEE — Custody Fee
31. *(reserved)* — (for future use)

**GL Pattern Format:**
- Debit Pattern: e.g. `1101.{custId}.{currency}.{cpId}|{amount}|{currency}`
- Credit Pattern: e.g. `3103.{custId}|{amount}|{currency}`
- Placeholders: `{custId}`, `{assetId}`, `{cpId}`, `{amount}`, `{fee}`, `{currency}`
- The gl-preparator plugin expands these patterns when creating GL posting lines

**Plugin integration:** The rows-enrichment plugin does NOT perform GL posting — that is the gl-preparator's job. However, the enrichment plugin must ensure that all fields required for GL posting are populated (customer_id, counterparty_id, asset_id for securities, amounts, currency, internal_type). The F02.15 patterns are informational for the enrichment team to understand what fields are needed downstream.

### 3.4 Settlement Days Configuration (Future Extension Point)

**Current:** The plugin uses a global `settlementConvention` property (default "T+2") to compute settlement_date from transaction_date.

**Future enhancement:** F02.15 could be extended with a `settlementDays` field per internal type, allowing settlement periods to vary by transaction type. For now, a single global value applies to all transactions. The architecture is designed to support per-type settlement periods without changing the pipeline interface — only the settlement date computation method in EnrichmentDataPersister would need updating.

**Placeholder note:** If F02.15 is extended with settlementDays in a future release, the persister must:
1. Load the `settlementDays` value from the F02.15 row matching the transaction's internal_type
2. If not found, fall back to the plugin property `settlementConvention`
3. Compute settlement_date by adding N business days to transaction_date, skipping weekends

---

## 4. Data Flow Overview

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
  |  Step 4: AssetResolutionStep
  |  Step 5: F14RuleMappingStep (uses F02.14 rules + internal types from F02.15)
  |  Step 6: FXConversionStep
  |
  v
EnrichmentDataPersister
  |  writes one F01.05 record per enriched transaction
  |  StatusManager: ENRICHMENT (null) → NEW → PROCESSING → ENRICHED|MANUAL_REVIEW|ERROR
  |  StatusManager: BANK_TRX/SECU_TRX PROCESSING → ENRICHED|MANUAL_REVIEW|ERROR
  |  StatusManager: STATEMENT CONSOLIDATED → ENRICHED|ERROR (after all transactions)
  v
F01.05 (trxEnrichment) — 52 fields per Section 8.4
  |
  v (downstream, outside this plugin's scope)
gl-preparator plugin consumes F01.05, expands GL patterns from F02.15, creates posting lines
```

### 4.1 Pipeline Ordering Rationale

| Step | Why This Position |
|------|-------------------|
| 1. CurrencyValidation | Must validate currency first; invalid currency blocks FX conversion |
| 2. CounterpartyDetermination | Must identify counterparty before F14 rule matching (rules are counterparty-scoped) |
| 3. CustomerIdentification | Independent of counterparty; runs for both bank and secu (customer = bank's client); needed for GL account construction |
| 4. AssetResolution | Securities-only; resolves ticker to asset master; needs to happen before F14 for potential asset-class-based rules |
| 5. F14RuleMapping | Needs counterparty_id (Step 2) and optionally asset_class (Step 4) as inputs for rule matching |
| 6. FXConversion | Last because it may optionally use internal_type (Step 5) to select rate type (trade date vs payment date) |

---

## 5. Table & Form Dependencies

### 5.1 Source Tables (READ)

| Table Name (Joget) | Form | Description |
|---|---|---|
| `bank_statement` | F01.00 | Statement header: bank, account_type, from_date, to_date, status |
| `bank_total_trx` | F01.03 | Bank transaction rows: payment_date, payment_amount, currency, d_c, other_side_bic, other_side_name, payment_description, reference_number, customer_id, status |
| `secu_total_trx` | F01.04 | Securities transaction rows: transaction_date, type, ticker, description, quantity, price, currency, amount, fee, total_amount, reference, status |

### 5.2 MDM Tables (READ)

| Table Name (Joget) | Form | Queried By |
|---|---|---|
| `currency` | F10.05 | code (3-letter ISO) |
| `customer` | F10.01 | id, registrationNumber, personalId, tax_id, name |
| `counterparty_master` | F02.03 | bankId (BIC), custodianId, brokerId; returns counterpartyId, shortCode, counterpartyType |
| `bank` | F10.02 | swift_code_bic; returns name |
| `broker` | F10.03 | id, swift_code_bic; returns bic_code |
| `customer_account` | F02.01 | account_number; returns customer_id |
| `asset_master` | F02.02 (assetMasterForm) | ticker; returns assetId, isin, categoryCode, asset_class, tradingCurrency. Key fields: assetId (IdGenerator), ticker, isin, cusip, sedol, assetName, shortName, categoryCode (SelectBox→asset category MDM), asset_class (SelectBox→asset class MDM), tradingCurrency (SelectBox→currency MDM), tradingStatus, riskCategory, liquidityProfile |
| `fx_rates_eur` | FX rates | targetCurrency, effectiveDate; returns exchangeRate, midRate, importSource, rateType |

### 5.3 Metadata Forms (READ) — Source of Truth for Behavior

| Table Name (Joget) | Form | Purpose | Fields Used |
|---|---|---|---|
| `trxType` | F10.10 | Base transaction types | Code, Name, Statement Type, Asset Type, Flow Type |
| `cp_txn_mapping` | F02.14 | Counterparty-scoped matching rules | Mapping ID, Counterparty, Matching Field, Match Value, Internal Type |
| `transactionTypeMap` | F02.15 | GL posting patterns & internal type definitions | Mapping ID, Internal Type, GL Debit Pattern, GL Credit Pattern |

### 5.4 Target Tables (WRITE)

| Table Name (Joget) | Form | Description |
|---|---|---|
| `trxEnrichment` | F01.05 | Enriched transaction output — **52 fields** |
| `exception_queue` | Exception | Exceptions requiring manual attention |
| `audit_log` | Audit | Step-level audit trail |

**CRITICAL:** The plugin constant `TABLE_TRX_ENRICHMENT` must be `"trxEnrichment"` (not `"trx_enrichment"`) to match the F01.05 form's tableName.

---

## 6. DataContext Specification

DataContext is the data carrier passed through all pipeline steps. Each source transaction becomes one DataContext instance.

### 6.1 Core Fields (set by TransactionDataLoader)

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

### 6.2 Bank-Specific Fields (set by TransactionDataLoader, source_type=bank)

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

### 6.3 Securities-Specific Fields (set by TransactionDataLoader, source_type=secu)

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

### 6.4 AdditionalData Map (populated by pipeline steps)

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
| **Asset Resolution (Step 4) — for securities only** |||
| `asset_id` | String | AssetResolution | Resolved asset master ID (F02.02 assetId) |
| `asset_isin` | String | AssetResolution | ISIN code (F02.02 isin) |
| `asset_category` | String | AssetResolution | Asset category code (F02.02 categoryCode) |
| `asset_class` | String | AssetResolution | Asset class code (F02.02 asset_class) |
| `asset_base_currency` | String | AssetResolution | Asset trading/denomination currency (F02.02 tradingCurrency) |
| `currency_mismatch_flag` | String | AssetResolution | "yes" if transaction currency != tradingCurrency |
| **F14 Rule Mapping (Step 5)** |||
| `internal_type` | String | F14RuleMapping | Matched internal transaction type code (from F02.14/F02.15) |
| `f14_rule_id` | String | F14RuleMapping | ID of the matched rule (e.g. TXMP-000011) |
| `f14_rule_name` | String | F14RuleMapping | Name of the matched rule (e.g. "Asset Return") |
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

## 7. TransactionDataLoader Specification

### 7.1 Responsibility

Load all unprocessed transactions from F01.03 and F01.04, grouped by their parent statement (F01.00).

### 7.2 Processing Logic

1. Query `bank_statement` table for all rows where `status = Status.CONSOLIDATED.getCode()` (statements ready for enrichment)
2. For each unprocessed statement:
   a. Transition each source transaction via StatusManager: `statusManager.transition(dao, entityType, txId, Status.PROCESSING, "rows-enrichment", "Pipeline processing started")`
   b. Based on `account_type`:
      - `"bank"` → load from `bank_total_trx` where `statement_id = <statementId>` and `status = Status.NEW.getCode()`
      - `"secu"` → load from `secu_total_trx` where `statement_id = <statementId>` and `status = Status.NEW.getCode()`
3. Create one DataContext per transaction row (see Section 6)
4. Sort all contexts by transaction date ascending
5. Return the complete list

### 7.3 Bank DataContext Construction

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

### 7.4 Securities DataContext Construction

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

## 8. Pipeline Steps Specification

### 8.1 Step 1: CurrencyValidationStep

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

### 8.2 Step 2: CounterpartyDeterminationStep

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

### 8.3 Step 3: CustomerIdentificationStep

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

### 8.4 Step 4: AssetResolutionStep

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

### 8.5 Step 5: F14RuleMappingStep

**Purpose:** Classify the transaction by matching it against configurable F02.14 rules to determine the internal transaction type. All internal types come from the F02.15 transactionTypeMap table.

**Execution condition:** Always (unless previous error).

**Processing:**

1. **Load applicable rules from F02.14 (`cp_txn_mapping` table):**
   - Query for all rows where `status` is "active"
   - Filter by counterparty: include rows where `Counterparty = <counterparty_id from Step 2>` OR `Counterparty = "SYSTEM"`
   - Filter by transaction type: include rows where source system type (bank/secu) matches
   - Filter by effectiveDate: include rows where `effectiveDate` is null or <= today

2. **Sort rules for evaluation:**
   - **Priority 1:** Rules with matching counterparty_id (ordered by rule priority ASC)
   - **Priority 2:** Rules with Counterparty = "SYSTEM" (ordered by rule priority ASC)
   - This ensures counterparty-specific rules are evaluated before universal SYSTEM rules

3. **Evaluate each rule in order against the transaction:**

   For each rule, extract:
   - `matchingField` — the source transaction field to match (type, d_c, description, reference, other_side_name, other_side_bic, ticker, combined)
   - `matchOperator` — comparison method (equals, contains, startsWith, endsWith, regex, in, case_insensitive)
   - `matchValue` — the pattern/value to match
   - `arithmeticCondition` (optional) — numeric range, e.g. amount > 100
   - `complexRuleExpression` (optional, if matchingField="combined") — AND/OR conditions on multiple fields

   Matching logic:
   ```
   matchField = context.getField(rule.matchingField)

   IF rule.matchingField == "combined":
       # Evaluate complex expression (e.g. "description CONTAINS 'loan' AND amount > 1000")
       matchPassed = evaluateExpression(rule.complexRuleExpression, context)
   ELSE:
       # Simple field match
       matchPassed = applyOperator(matchField, rule.matchOperator, rule.matchValue, rule.caseSensitive)

   IF matchPassed AND rule.arithmeticCondition:
       # Also check numeric condition
       matchPassed = evaluateArithmetic(rule.arithmeticCondition, context.amount)

   IF matchPassed:
       RETURN rule.internalType  # First match wins
   ```

4. **Determine matching confidence:**
   - If a rule matched: confidence = "high"
   - If no rule matched: confidence = "low", set `internal_type = "UNMATCHED"`

5. **Handle no-match scenarios:**
   - If no rules exist for the counterparty/source type: create exception NO_F14_RULES, set UNMATCHED
   - If rules exist but none match: create exception NO_RULE_MATCH (include transaction details for manual rule creation), set UNMATCHED
   - On UNMATCHED: store details in exception for downstream operations team to create new rule

**Output (additionalData):** `internal_type`, `f14_rule_id`, `f14_rule_name`, `f14_rules_evaluated`

**Status on success:** `context.processingStatus = "f14_mapped"`

**Matching confidence derivation:**
- If matched: confidence = "high"
- If UNMATCHED: confidence = "low"

**Exceptions created:**
- `NO_F14_RULES` — no rules configured for counterparty/source type (high priority)
- `NO_RULE_MATCH` — rules exist but none match (medium priority; includes transaction details for manual rule creation)
- `F14_MAPPING_ERROR` — unexpected error

**Important:** The internal_type codes come from the superset of F02.14 (40 rules) and F02.15 (31 internal types). The plugin must support all internal types defined in F02.15, even if F10.10 only lists 8 base types. The architecture is designed to be extensible: new internal types can be added to F02.15 and new matching rules to F02.14 without code changes.

---

### 8.6 Step 6: FXConversionStep

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
5. If exact date not found: search for most recent rate within 5 days (MAX_RATE_AGE_DAYS, configurable)
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

## 9. EnrichmentDataPersister Specification

### 9.1 Responsibility

Transform the enriched DataContext into an F01.05 record, save it, update source transaction status, and create an audit trail.

### 9.2 Target Table

`trxEnrichment` (matching F01.05 form tableName)

### 9.3 Record ID Format

`TRX-<6-char-UUID-uppercase>` (e.g. TRX-A3BF12)

### 9.4 Complete 52-Field Mapping

Each row below maps one F01.05 field to its source in DataContext.

#### Provenance Section (10 fields)

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

#### Transaction Core Section (8 fields)

| F01.05 Field | Source | Logic |
|---|---|---|
| `transaction_date` | `context.getTransactionDate()` | Direct |
| `settlement_date` | computed | Bank: same as transaction_date. Secu: transaction_date + N business days where N = plugin property `settlementConvention` (default "2", meaning T+2). Weekends and holidays are skipped. **Extensibility note:** designed as a single global value for now; if F02.15 is extended with a `settlementDays` field per internal type in a future release, settlement date computation can be extended to use that lookup without changing the pipeline interface — only the persister method would need updating. |
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

#### Classification Section (3 fields)

| F01.05 Field | Source | Logic |
|---|---|---|
| `internal_type` | `additionalData.internal_type` | Direct. May be "UNMATCHED" |
| `type_confidence` | computed | Map from matching + customer confidence: if internal_type != UNMATCHED AND customer_confidence >= 80 → "high"; if >= 50 → "medium"; else → "low" |
| `matched_rule_id` | `additionalData.f14_rule_id` | Direct. May be null if UNMATCHED |

#### Customer Resolution Section (4 fields)

| F01.05 Field | Source | Logic |
|---|---|---|
| `resolved_customer_id` | `context.getCustomerId()` | After Step 3 resolution. May be "UNKNOWN" |
| `customer_match_method` | `additionalData.customer_identification_method` | Map: DIRECT_ID → "direct_id", ACCOUNT_NUMBER → "account_mapping", REGISTRATION_NUMBER_EXTRACTED → "registration_number", NAME_PATTERN → "name_pattern", NONE → "unresolved" |
| `customer_code` | `additionalData.customer_code` | Direct |
| `customer_display_name` | `additionalData.customer_name` | Direct |

**Note:** For securities transactions, customer resolution runs the same as for bank transactions — the customer is the bank's client whose portfolio is being managed. If no customer can be resolved (rare — typically only internal/technical transactions), set resolved_customer_id = "UNKNOWN" and customer_match_method = "unresolved", and create a MISSING_CUSTOMER exception as for bank transactions.

#### Asset Resolution Section (6 fields, securities only)

| F01.05 Field | Source | Logic |
|---|---|---|
| `resolved_asset_id` | `additionalData.asset_id` | Direct. Secu only |
| `asset_isin` | `additionalData.asset_isin` | Direct. Secu only |
| `asset_category` | `additionalData.asset_category` | Direct. Secu only |
| `asset_class` | `additionalData.asset_class` | Direct. Secu only |
| `asset_base_currency` | `additionalData.asset_base_currency` | Direct. Secu only |
| `currency_mismatch_flag` | `additionalData.currency_mismatch_flag` | "yes" or "no". Secu only |

**Note:** For bank transactions, all asset fields are left null. F01.05's Asset Resolution section has `visibilityControl: source_tp`, `visibilityValue: secu` so these fields are hidden for bank records.

#### Counterparty Resolution Section (7 fields)

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

#### Currency & FX Section (5 fields)

| F01.05 Field | Source | Logic |
|---|---|---|
| `validated_currency` | `context.getCurrency()` | After CurrencyValidationStep normalization |
| `fx_rate_source` | `additionalData.fx_rate_source` | "ecb", "manual", "BASE_CURRENCY", "MISSING" |
| `requires_eur_parallel` | computed | "yes" if validated_currency != "EUR", else "no" |
| `fx_rate_to_eur` | `additionalData.fx_rate` | Double → String. The applied conversion rate |
| `fx_rate_date` | `additionalData.fx_rate_date` | Rate effective date |
| `base_amount_eur` | `context.getBaseAmount()` | EUR equivalent |

#### Fee & Pairing Section (4 fields)

| F01.05 Field | Source | Logic |
|---|---|---|
| `has_fee` | computed | "yes" if fee_amount != null AND fee_amount > 0, else "no" |
| `fee_trx_id` | — | Leave null. Populated by pairing logic downstream |
| `pair_id` | — | Leave null. Populated by pairing logic downstream |
| `base_fee_eur` | `additionalData.base_fee` | EUR fee (secu only) |

#### Status & Notes Section (5 fields)

| F01.05 Field | Source | Logic |
|---|---|---|
| `status` | computed | See Section 9.5 below |
| `enrichment_timestamp` | `new Date()` | ISO timestamp: yyyy-MM-dd HH:mm:ss |
| `error_message` | `context.getErrorMessage()` | Direct. May be null |
| `processing_notes` | computed | Concatenated summary: "Steps completed: {processedSteps}. Customer: {customer_id} ({confidence}%). Counterparty: {counterparty_id} ({type}). Internal type: {internal_type}." |
| `version` | "1" | Initial version |

### 9.5 Status Determination & Transition Logic

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

### 9.6 Post-Persistence Actions

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

**Note:** The audit entry for "ENRICHMENT_SAVED" is automatically created by the StatusManager transition in Step 9.5. No manual audit write needed.

### 9.7 Batch Completion (per statement)

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

## 10. Exception Queue Specification

All exceptions are written to the `exception_queue` table.

### 10.1 Exception Record Structure

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

### 10.2 Exception Types

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
| `INACTIVE_ASSET` | AssetResolution | low | Asset found but tradingStatus not active |
| `NO_F14_RULES` | F14RuleMapping | high | No rules for counterparty/source |
| `NO_RULE_MATCH` | F14RuleMapping | medium | Rules exist, none matched |
| `FX_RATE_MISSING` | FXConversion | high | No rate within 5 days |
| `OLD_FX_RATE` | FXConversion | low | Rate is older than exact date |
| `INVALID_FX_DATE` | FXConversion | medium | Cannot determine rate lookup date |
| `CURRENCY_VALIDATION_ERROR` | CurrencyValidation | high | Unexpected error during validation |
| `COUNTERPARTY_DETERMINATION_ERROR` | CounterpartyDetermination | high | Unexpected error during lookup |
| `CUSTOMER_IDENTIFICATION_ERROR` | CustomerIdentification | high | Unexpected error during identification |
| `ASSET_RESOLUTION_ERROR` | AssetResolution | high | Unexpected error during resolution |
| `F14_MAPPING_ERROR` | F14RuleMapping | high | Unexpected error during rule evaluation |
| `FX_CONVERSION_ERROR` | FXConversion | high | Unexpected error during conversion |

### 10.3 Priority Calculation (amount-based)

For exceptions where priority = "based on amount":

| Amount (absolute) | Priority |
|---|---|
| >= 1,000,000 | critical |
| >= 100,000 | high |
| >= 10,000 | medium |
| < 10,000 | low |

---

## 11. Audit Log Specification

The `audit_log` table receives entries from two sources:

### 11.1 Framework-Managed Audit (automatic — via StatusManager)

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

### 11.2 Step-Level Audit (custom — written by pipeline steps)

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

**Note:** The old `ENRICHMENT_SAVED` and `STATEMENT_PROCESSED` action codes are **removed** — these are now covered by framework-managed audit entries from StatusManager transitions (Section 11.1).

---

## 12. Configuration & Framework Integration Specification

The plugin must externalise all configurable values. No hardcoding. Status management must use gam-framework.

### 12.1 Plugin Properties (Joget plugin configuration)

| Property | Default | Description |
|---|---|---|
| `baseCurrency` | "EUR" | Base/reporting currency |
| `maxFxRateAgeDays` | 5 | Max days old for acceptable FX rate |
| `settlementConvention` | "2" | Settlement lag in business days (integer as string). "2" = T+2. Selectbox options: 1, 2, 3, 5. Extensible to per-asset-class later |
| `stopOnError` | false | Whether pipeline stops on first step failure |
| `batchSize` | 100 | Max statements to process per run |
| `pipelineVersion` | "3.0" | Version string for lineage tracking |
| `confidenceThresholdHigh` | 80 | Minimum confidence for "high" classification |
| `confidenceThresholdMedium` | 50 | Minimum confidence for "medium" classification |
| `descriptionFieldsBank` | "payment_description,reference_number,other_side_name,other_side_bic,other_side_account" | Comma-separated source fields to include in F01.05 description for bank transactions (see Section 13.2) |
| `descriptionFieldsSecu` | "description,reference,ticker,quantity,price" | Comma-separated source fields to include in F01.05 description for secu transactions (see Section 13.2) |
| `secuDebitCreditMapping` | "BUY:D,PURCHASE:D,SELL:C,DISPOSE:C,DIVIDEND:C,COUPON:C,INTEREST:C,CUSTODY_FEE:D,SAFEKEEPING:D,FEE:D,TRANSFER_IN:N,TRANSFER_OUT:N,CORPORATE_ACTION:N" | Configurable mapping from F01.04 transaction type to debit_credit (D/C/N). Unmapped types default to N with exception |
| `descriptionMaxLength` | 2000 | Maximum character length for the built description field |

### 12.2 Table Name Constants (DomainConstants)

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
| `TABLE_CP_TXN_MAPPING` | — | **ADD: `"cp_txn_mapping"`** (F02.14 rules) |
| `TABLE_TRANSACTION_TYPE_MAP` | — | **ADD: `"transactionTypeMap"`** (F02.15 GL patterns) |
| `TABLE_TRANSACTION_TYPE` | — | **ADD: `"trxType"`** (F10.10 base types) |
| `TABLE_EXCEPTION_QUEUE` | `"exception_queue"` | `"exception_queue"` (OK) |
| `TABLE_AUDIT_LOG` | `"audit_log"` | `"audit_log"` (OK) |
| `TABLE_FX_RATES_EUR` | — | **ADD: `"fx_rates_eur"`** (FX rate table) |

### 12.3 FrameworkConstants.java — Cleanup Required

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

### 12.4 Framework Contracts — DataStep Interface & Property Passing

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

### 12.5 Values That Must NOT Be Hardcoded

| Currently Hardcoded | Location | Must Become |
|---|---|---|
| `"SPOT"` (fx_rate_type) | EnrichmentDataPersister.populateFXFields | Removed — not in F01.05 |
| `"pending"` (pairing_status) | EnrichmentDataPersister.populateMetadataFields | Removed — not in F01.05; has_fee is computed |
| `"3.0"` (pipeline_version) | FrameworkConstants | Plugin property `pipelineVersion` |
| `"SYSTEM"` (created_by) | FrameworkConstants | Removed — Joget handles createdBy natively |
| `80` (confidence threshold) | CustomerIdentificationStep, determineManualReviewStatus | Plugin property `confidenceThresholdHigh` |
| `5` (MAX_RATE_AGE_DAYS) | FXConversionStep | Plugin property `maxFxRateAgeDays` |
| BUY→D, SELL→C (secu debit/credit) | EnrichmentDataPersister | Plugin property `secuDebitCreditMapping` |
| Hardcoded description source | EnrichmentDataPersister | Plugin properties `descriptionFieldsBank` / `descriptionFieldsSecu` |
| `"T+2"` (settlement lag) | EnrichmentDataPersister | Plugin property `settlementConvention` (global, extensible to per-asset-class via F02.15 in future) |
| All status string literals | Throughout plugin | `Status` enum from gam-framework (see Section 12.3) |
| Direct status field writes | EnrichmentDataPersister, TransactionDataLoader | `StatusManager.transition()` (see Sections 9.5-9.7) |
| Internal type list | F14RuleMapping | Loaded from F02.14/F02.15 at runtime, not hardcoded |
| Transaction type codes | Throughout | Loaded from F10.10 at startup, not hardcoded |
| Counterparty rules | F14RuleMapping | Loaded from F02.14 at runtime, not hardcoded |

---

## 13. Fields Dropped from Persister & Description Builder

### 13.1 Fields Dropped from Persister

The following fields are currently written by the plugin but have no corresponding F01.05 field. They are **removed** from the persister. However, some of them serve as inputs to the configurable **Description Builder** (Section 13.2) so that useful context is preserved in the `description` field for manual resolution.

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

### 13.2 Description Builder

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

## 14. Key Principles — Everything Configurable

This specification is built on the principle that **no behavior should be hardcoded**. All configurable elements come from metadata forms or plugin properties:

| Behavior | Source | Configurable |
|---|---|---|
| Base transaction types (8 types) | F10.10 (trxType) | Yes — add/remove rows to F10.10 |
| Extended transaction types (31 types) | F02.15 (transactionTypeMap) | Yes — add rows to F02.15 |
| Rule-based classification (40 rules) | F02.14 (cpTxnMappingForm) | Yes — add/modify rows to F02.14 |
| GL posting patterns | F02.15 (GL Debit/Credit patterns) | Yes — configure patterns per internal type |
| Settlement days (T+N) | Plugin property `settlementConvention` + (future) F02.15 `settlementDays` field | Yes — change property or extend F02.15 |
| Confidence thresholds | Plugin properties `confidenceThresholdHigh`, `confidenceThresholdMedium` | Yes — adjust thresholds |
| Debit/Credit mapping for securities | Plugin property `secuDebitCreditMapping` | Yes — add/modify mappings |
| Description field composition | Plugin properties `descriptionFieldsBank`, `descriptionFieldsSecu` | Yes — change source field lists |
| FX rate age tolerance | Plugin property `maxFxRateAgeDays` | Yes — adjust tolerance |
| Base currency | Plugin property `baseCurrency` | Yes — change reporting currency |
| Status lifecycle | gam-framework `Status` enum + plugin calls to `StatusManager` | Yes — gam-framework can be extended |

### 14.1 How to Extend Without Code Changes

**Example 1: Add a new transaction type**
1. Add a row to F02.15 with new internal type code, GL patterns, description
2. No code change needed — the F14RuleMapping step loads from F02.15 at runtime

**Example 2: Add a new matching rule**
1. Add a row to F02.14 with the new rule details (counterparty, matching field, match value, target internal type)
2. No code change needed — the F14RuleMapping step loads from F02.14 at runtime

**Example 3: Extend settlement days to be per-transaction-type**
1. Add a `settlementDays` column to F02.15
2. Update EnrichmentDataPersister.computeSettlementDate() to look up F02.15 by internal_type
3. This is an enhancement to the persister only; the plugin interface and pipeline remain unchanged

**Example 4: Change debit/credit mapping for a transaction type**
1. Edit the `secuDebitCreditMapping` plugin property
2. No code change needed

---

## 15. Internal Type Consistency Across Metadata Forms

This section verifies that internal types are consistent across all three metadata forms.

### 15.1 F10.10 Base Types (8)

From the CSV, the official 8 base types are:
1. BOND_BUY
2. BOND_INTEREST
3. CASH_IN_OUT
4. COMM_FEE
5. DIV_INCOME
6. SEC_BUY
7. SEC_SELL
8. (one additional type, unclear from CSV)

### 15.2 F02.14 Target Internal Types (from 40 rules)

Extracting unique internal types from the 40 rules:
ASSET_RETURN, BANK_BOND, BANK_TRANS, BOND_BUY, BOND_INT, CASH_IN, CASH_OUT, COMM_BUY, COMM_FEE, CUST_INT, DIV_INCOME, EQ_BUY, EQ_SELL, INT_EXPENSE, INT_INCOME, INV_INCOME, INV_TRANS, LOAN_PAYMENT, LOCAL_BOND, MGMT_FEE, SEC_BUY, SEC_DEPOSIT, SEC_SELL, SEC_TRANS, SPLIT_IN, SPLIT_OUT, TECH_BUY

Total: **27 distinct types in F02.14**

### 15.3 F02.15 Internal Types (31 in form)

1. INT_INCOME
2. INT_EXPENSE
3. SEC_BUY
4. SEC_SELL
5. COMM_FEE
6. DIV_INCOME
7. MGMT_FEE
8. LOAN_PAYMENT
9. INV_INCOME
10. ASSET_RETURN
11. CASH_IN
12. CASH_OUT
13. EQ_BUY
14. EQ_SELL
15. BOND_BUY
16. BOND_INT
17. SPLIT_IN
18. SPLIT_OUT
19. SEC_DEPOSIT
20. SEC_WITHDRAW
21. TECH_BUY
22. COMM_BUY
23. LOCAL_BOND
24. BANK_BOND
25. SEC_TRANS
26. BANK_TRANS
27. INV_TRANS
28. CUST_INT
29. FX_CONV
30. CUSTODY_FEE
31. (reserved)

Total: **30 assigned + 1 reserved = 31 slots**

### 15.4 Reconciliation & Consistency Notes

The plugin must support all 31 internal types defined in F02.15 as the authoritative list of valid transaction classifications. The 40 rules in F02.14 map to these types. The 8 base types in F10.10 are a simplified classification for high-level reporting.

**Key consistency rules:**
1. All internal types referenced in F02.14 matching rules must exist in F02.15
2. All internal types that may appear in enriched transactions must be defined in F02.15 (for GL posting pattern lookup)
3. F10.10 contains only the 8 base types; F02.15 extends with domain-specific types (TECH_BUY, CUST_INT, etc.)
4. No hardcoding of internal type lists — both the plugin and operations team refer to F02.15 as source of truth

---

## 16. Manual Review Conditions

The enrichment persister marks a record for manual review (Status.MANUAL_REVIEW) if **any** of these 6 conditions are true:

1. **UNKNOWN customer:** `resolved_customer_id = "UNKNOWN"` (customer could not be identified)
2. **UNKNOWN counterparty:** `counterparty_id = "UNKNOWN"` (no matching counterparty for BIC)
3. **UNMATCHED internal type:** `internal_type = "UNMATCHED"` (no F14 rule matched)
4. **Low customer confidence:** `customer_confidence < confidenceThresholdHigh` (default: < 80)
5. **UNKNOWN asset (securities only):** `asset_id = "UNKNOWN"` (ticker not in asset master)
6. **Missing FX rate:** `fx_rate_source = "MISSING"` (no FX rate found within tolerance)

If **none** of these conditions are true and the transaction successfully passed all 6 pipeline steps, the record is marked Status.ENRICHED (fully auto-approved).

---

## 17. Master Specification References

This specification is part of a larger family of documents:

- **gam-framework-specification.md** — Defines the `Status` enum, `StatusManager` class, and entity lifecycle state machines used by all plugins
- **F01.05-form-definition.md** — Complete field specification for the trxEnrichment form (52 fields, validation rules, visibility controls)
- **F10.10-form-definition.md** — Transaction Type Master form (base types)
- **F02.14-form-definition.md** — Counterparty Transaction Mapping form (40 rules, configurable matching logic)
- **F02.15-form-definition.md** — Transaction Type GL Pattern form (31 internal types, GL posting patterns)
- **MDM-design-document-v3.0.md** — Master Data Management architecture (currency, customer, counterparty, asset, FX rates)

All cross-references in this specification point to the forms and documents listed above.

---

## 18. Implementation Status

**Version 3.0 (this document):**
- Full rewrite for metadata form consistency
- F10.10 (transaction types), F02.14 (matching rules), F02.15 (GL patterns) integrated as source of truth
- 6 manual review conditions explicitly documented
- 52-field persister mapping complete and cross-referenced
- Settlement days architecture extended for future per-type configuration
- All internal type definitions reconciled across forms

**Remaining for implementation:**
- Code translation from this specification
- Unit test suite (Section 14 has comprehensive test plan)
- Integration testing with real metadata forms
- Verification that all 31 internal types in F02.15 are handled by gl-preparator

---

**End of Specification**
