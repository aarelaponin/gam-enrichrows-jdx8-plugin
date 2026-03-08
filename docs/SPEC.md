# Rows Enrichment Plugin — Specification

**Plugin:** `RowsEnricher` (extends `DefaultApplicationPlugin`)
**Platform:** Joget DX 8.1
**Version:** 8.1-SNAPSHOT
**Base currency:** EUR

---

## 1. Purpose

Automates transaction classification for a custodian fund (Genesis Asset Management OÜ) operating on LHV bank account EE117700771003603322. Reads raw bank and securities statement rows, resolves all accounting dimensions (counterparty, customer, asset, internal type, loan, FX), persists enriched records, and pairs cross-statement transactions.

---

## 2. Execution Flow

```
RowsEnricher.execute()
  │
  ├─ 1. LOAD ─── TransactionDataLoader
  │     Reads bank_statement (CONSOLIDATED/ENRICHED status)
  │     Fetches bank_total_trx + secu_total_trx rows
  │     Marks re-enrichment flag on previously enriched records
  │     Filters out workspace-protected records (PAIRED/IN_REVIEW/ADJUSTED/READY/CONFIRMED/SUPERSEDED)
  │
  ├─ 2. PIPELINE ─── DataPipeline (7 steps, sequential per transaction)
  │     Step 1: CurrencyValidationStep
  │     Step 2: CounterpartyDeterminationStep
  │     Step 3: CustomerIdentificationStep      ← bank only, skips secu-related bank trx
  │     Step 4: AssetResolutionStep              ← secu only
  │     Step 5: F14RuleMappingStep
  │     Step 6: LoanResolutionStep               ← bank only, non-blocking
  │     Step 7: FXConversionStep                 ← non-EUR only
  │
  ├─ 3. PERSIST ─── EnrichmentDataPersister
  │     Writes to trxEnrichment table
  │     REQUIRES_NEW transaction per statement group (avoids JTA timeout)
  │     Updates statement status (CONSOLIDATED → ENRICHED)
  │     Sets record status: ENRICHED or MANUAL_REVIEW
  │
  ├─ 4. POST-ENRICHMENT
  │     Creates PORTFOLIO_ALLOCATION_REQUIRED exceptions for secu ENRICHED records
  │
  └─ 5. PAIRING ─── TransactionPairingStep
        Matches secu ↔ bank by amount + date (±1 day)
        secu.original_amount = bank.principal + bank.fee
        Creates trx_pair records, updates status → PAIRED
```

---

## 3. Pipeline Steps

### 3.1 CurrencyValidationStep

Validates transaction currency exists in `currency` master table. Normalizes to uppercase. Creates INVALID_CURRENCY / MISSING_CURRENCY exception on failure. **Blocking** — failed transactions do not continue.

### 3.2 CounterpartyDeterminationStep

Resolves counterparty for F14 rule matching:
- **Bank transactions:** Uses statement bank (e.g. `LHV-EE`) as counterparty. Other-side BIC/name stored as additional data.
- **Securities transactions:** Uses statement bank as broker/custodian.

Looks up counterparty in `counterparty_master` by BIC. Creates COUNTERPARTY_NOT_FOUND exception if not found.

**Enrichment fields:** `counterparty_id`, `counterparty_short_code`, `other_side_bic`, `other_side_name`.

### 3.3 CustomerIdentificationStep

**Applies to:** Bank transactions only (excluding securities-related: SEC_BUY, SEC_SELL, COMM_FEE, Dividends, Income tax withheld).

Six methods, tried in order until one succeeds:

| # | Method | Confidence | Resolution |
|---|--------|-----------|------------|
| 1 | DIRECT_ID | 100 | `customer_id` field → match `registrationNumber` or `personalId` on customer |
| 2 | ACCOUNT_NUMBER | 95 | `account_number` → `customer_account.accountNumber` → customer via business key |
| 3 | CUSTOMER_ACCOUNT | 93 | `other_side_account` → `customer_account.accountNumber` (SQL WHERE) → `customerId` |
| 4 | REGISTRATION_NUMBER | 90 | Extract reg number from reference/description → match customer |
| 5 | NAME_PATTERN | 70 | `other_side_name` → exact/partial match on customer `name`/`short_name` |
| 6 | FUND_FALLBACK | 80 | No counterparty (empty `other_side_account`) → customer with `is_fund=yes` |

**Method 6 detail:** Fund fallback assigns the fund entity (Genesis Asset Management OÜ, customer `12345678`) as customer when no external counterparty exists. Covers: account interest, portfolio safekeeping fees, VAT, FX conversions, bond coupon income. Result cached per enrichment run. Constrained by DuplicateValueValidator (exactly one `is_fund=yes` customer).

**Fallback:** If all methods fail → customer = `UNKNOWN`, high-priority MISSING_CUSTOMER exception created.

**Low confidence warning:** If confidence < 80 (configurable via `confidenceThresholdHigh`), creates LOW_CONFIDENCE_IDENTIFICATION exception.

**KYC check:** After identification, verifies `kycStatus = completed`. Warns if not.

### 3.4 AssetResolutionStep

**Applies to:** Securities transactions only.

Resolution strategy:
1. Exact ticker match in `asset_master`
2. ISIN match (if ticker looks like an ISIN)
3. Partial name match against description

Verifies trading status is active. Stores: `asset_id`, `asset_isin`, `asset_category`, `asset_class`, `asset_base_currency`, `currency_mismatch_flag`.

Creates MISSING_TICKER / UNKNOWN_ASSET / INACTIVE_ASSET exceptions on failure.

### 3.5 F14RuleMappingStep

Maps transactions to internal types using `cp_txn_mapping` rules:
1. Load rules for identified counterparty, ordered by priority
2. Evaluate each rule's conditions against transaction fields
3. First match determines internal type (e.g. LOAN_PAYMENT, INT_INCOME, EQ_BUY)
4. Falls back to SYSTEM rules (universal) if no counterparty-specific match

**Rule evaluation:**
- Primary condition: field + operator + value
- Optional secondary condition: AND logic with `secondaryField`, `secondaryOperator`, `secondaryValue`
- First matching rule wins (stops evaluation)
- Filtered by status = Active and effectiveDate <= today

**Supported operators:**
| Operator | Behavior |
|----------|----------|
| `equals` | Exact string match |
| `contains` | Substring search |
| `startsWith` | Beginning of string match |
| `endsWith` | End of string match |
| `regex` | Regular expression pattern |
| `in` | Match against comma-separated list |

All operators respect the rule's `caseSensitive` flag.

Creates NO_F14_RULES / NO_RULE_MATCH exceptions if unmatched → internal type = `UNCLASSIFIED`.

### 3.6 LoanResolutionStep

**Applies to:** Bank transactions only. **Non-blocking** — transactions continue without loan linkage.

Three-tier resolution:
1. Extract contract number from description (Estonian patterns: `leping 1315-2020`, `laenulepingu 110401`, `avaldusele #211`)
2. Match by `other_side_account` → `loanContract`
3. Auto-register DRAFT loan if contract number extracted but not found

### 3.7 FXConversionStep

**Applies to:** Non-EUR transactions only (EUR = base currency, skipped).

1. **Rate lookup:** Retrieves FX rate from `fx_rates_eur` by currency and transaction date
2. **Rate application:** Table stores rates as `1 EUR = X foreign currency`. Conversion: `amount_EUR = amount_foreign / fx_rate`. For securities: also converts fee and total_amount to EUR.
3. **Rate age validation:** Rate older than 5 days (configurable) → OLD_FX_RATE exception. No rate found → FX_RATE_MISSING exception (high priority).

---

## 4. Re-enrichment

When a statement is in ENRICHED status, its transactions are loaded with `reEnrichment=true`. Each step's `shouldExecute()` checks whether its dimension is already resolved (via `isFieldResolved`) — resolved fields are skipped, UNKNOWN values are re-evaluated.

**Workspace protection:** Records in statuses PAIRED, IN_REVIEW, ADJUSTED, READY, CONFIRMED, SUPERSEDED are filtered out before pipeline execution.

**JTA timeout mitigation:** EnrichmentDataPersister uses `PROPAGATION_REQUIRES_NEW` per statement group, breaking out of Joget's outer JTA transaction.

---

## 5. Persistence

**Target table:** `trxEnrichment`

Each enriched record stores: source type, statement ID, transaction ID, counterparty, customer (ID + name + confidence + method), internal type, asset details, loan linkage, FX rate + base amount, processing metadata.

**Record creation:**
- First-time: Generate unique enrichment ID (format: `TRX-XXXXXX`)
- Re-enrichment: Reuse existing enrichment ID via `existing_enrichment_id` (upsert)

**Status assignment:**
- `ENRICHED` — all required dimensions resolved
- `MANUAL_REVIEW` — customer = UNKNOWN or other critical dimension missing

**Self-transition skip:** On re-enrichment, if target status equals current status (e.g. ENRICHED → ENRICHED), skip the StatusManager transition silently.

**Statement status:** Updated to ENRICHED after all rows persisted.

**Post-enrichment:** Creates PORTFOLIO_ALLOCATION_REQUIRED exceptions for securities ENRICHED records.

---

## 6. Cross-Statement Pairing

Post-persistence step. Matches securities and bank transactions:

- **Candidates:** ENRICHED records with no `pair_id`
- **Match logic:** `secu.original_amount = bank.principal_amount + bank.fee_amount`, date within ±1 day
- **Bank classification:** Uses `internal_type` (SEC_BUY, SEC_SELL, COMM_FEE)
- **Result:** Creates `trx_pair` record, updates both sides to PAIRED status

---

## 7. Data Model

### Source Tables (read)
| Table | Description |
|-------|-------------|
| `bank_statement` | Bank + secu statement headers |
| `bank_total_trx` | Bank transaction rows |
| `secu_total_trx` | Securities transaction rows |

### Master Data (read)
| Table | Description |
|-------|-------------|
| `customer` | Customer master (`registrationNumber`, `personalId`, `is_fund`, `kycStatus`) |
| `customer_account` | Customer ↔ IBAN mapping (`accountNumber`, `corporateCustomerId`, `individualCustomerId`) |
| `counterparty_master` | Counterparty registry (BIC-based) |
| `currency` | Valid currencies |
| `asset_master` | Securities master (ticker, ISIN, status) |
| `cp_txn_mapping` | F14 classification rules (with secondary conditions) |
| `fx_rates_eur` | FX rates (EUR-based) |
| `loanContract` | Loan contract master |
| `bank` | Bank registry |
| `broker` | Broker registry |
| `trxType` | Transaction type definitions |

### Processing Tables (write)
| Table | Description |
|-------|-------------|
| `trxEnrichment` | Enriched transaction records |
| `trx_pair` | Cross-statement pair records |
| `exception_queue` | Operational exceptions |
| `audit_log` | Processing audit trail |

---

## 8. Exception Types

| Exception | Priority | Trigger |
|-----------|----------|---------|
| MISSING_CURRENCY | high | No currency on transaction |
| INVALID_CURRENCY | high | Currency not in master |
| COUNTERPARTY_NOT_FOUND | medium | BIC not in counterparty_master |
| MISSING_CUSTOMER | high | All 6 identification methods failed |
| INACTIVE_CUSTOMER | high | KYC not completed |
| LOW_CONFIDENCE_IDENTIFICATION | low | Confidence < threshold (default 80) |
| MISSING_TICKER | high | No ticker on secu transaction |
| UNKNOWN_ASSET | high | Ticker not in asset_master |
| INACTIVE_ASSET | medium | Asset not in trading status |
| NO_F14_RULES | medium | No rules for counterparty |
| NO_RULE_MATCH | medium | Rules exist but none matched |
| FX_RATE_MISSING | high | No rate for currency + date |
| OLD_FX_RATE | medium | Rate older than 5 days |
| PORTFOLIO_ALLOCATION_REQUIRED | medium | Secu record enriched, needs customer allocation |

---

## 9. State Management

The system uses the gam-framework `StatusManager` state machine for all status transitions. Direct status writes are never used.

### Statement States
```
CONSOLIDATED → ENRICHED
```

### Transaction States (Bank/Secu)
```
NEW → PROCESSING → ENRICHED or MANUAL_REVIEW
```

### Enrichment Record States
```
ENRICHED or MANUAL_REVIEW → PAIRED (via pairing)
                          → IN_REVIEW → ADJUSTED → READY → CONFIRMED (operator workflow)
```

### Workspace-Protected Statuses
Records in PAIRED, IN_REVIEW, ADJUSTED, READY, CONFIRMED, SUPERSEDED are excluded from re-enrichment.

---

## 10. Source Structure & Architecture

```
src/main/java/com/fiscaladmin/gam/
├── Activator.java                       # OSGi bundle activator
└── enrichrows/
    ├── constants/
    │   ├── DomainConstants.java          # Business domain constants
    │   └── FrameworkConstants.java       # Framework-level constants
    ├── framework/                        # Reusable pipeline framework
    │   ├── AbstractDataLoader.java       # Base loader
    │   ├── AbstractDataPersister.java    # Base persister
    │   ├── AbstractDataStep.java         # Base step (helpers, lifecycle)
    │   ├── DataContext.java              # Transaction context carrier
    │   ├── DataPipeline.java             # Step orchestration
    │   ├── StepResult.java               # Single step result
    │   ├── PipelineResult.java           # Single pipeline result
    │   ├── BatchPipelineResult.java      # Batch processing results
    │   └── BatchPersistenceResult.java   # Batch persistence results
    ├── lib/
    │   └── RowsEnricher.java             # Plugin entry point
    ├── loader/
    │   └── TransactionDataLoader.java    # Data loading + state management
    ├── persister/
    │   └── EnrichmentDataPersister.java  # Persistence + JTA fix
    └── steps/                            # Pipeline step implementations
        ├── CurrencyValidationStep.java
        ├── CounterpartyDeterminationStep.java
        ├── CustomerIdentificationStep.java
        ├── AssetResolutionStep.java
        ├── F14RuleMappingStep.java
        ├── LoanResolutionStep.java
        ├── FXConversionStep.java
        └── TransactionPairingStep.java
```

### Design Patterns
- **Pipeline Pattern**: Sequential processing through steps
- **Template Method**: `AbstractDataStep` provides lifecycle (`shouldExecute` → `execute` → `performStep`)
- **Strategy Pattern**: Each step implements `DataStep` interface
- **Builder Pattern**: `DataPipeline` uses fluent `.addStep()` interface

---

## 11. Development Guide

### 11.1 Adding New Steps

1. Create class extending `AbstractDataStep`:
```java
public class MyNewStep extends AbstractDataStep {
    @Override
    public String getStepName() { return "My New Step"; }

    @Override
    protected StepResult performStep(DataContext context, FormDataDao dao) {
        // Your logic here
        return new StepResult(true, "Done");
    }
}
```

2. Register in `RowsEnricher.java`:
```java
DataPipeline pipeline = new DataPipeline(dao)
    .addStep(new CurrencyValidationStep())
    // ... existing steps ...
    .addStep(new MyNewStep());
```

3. Build and deploy:
```bash
mvn clean package
cp target/rows-enrichment-8.1-SNAPSHOT.jar /path/to/joget/wflow/app_plugins/
```

### 11.2 Step Lifecycle & AbstractDataStep API

```
shouldExecute(context)  →  true/false (skip step if false)
       ↓
execute(context, dao)   →  framework wrapper (calls performStep)
       ↓
performStep(context, dao)  →  your implementation → StepResult
```

**Override `shouldExecute`** for conditional logic (e.g. bank-only steps):
```java
@Override
public boolean shouldExecute(DataContext context) {
    return "BANK".equals(context.getSourceType());
}
```

**Inherited helper methods:**
```java
// Database
FormRow loadFormRow(dao, tableName, id)
boolean saveFormRow(dao, tableName, row)
FormRowSet loadRecords(dao, tableName, condition, params, sort, desc, limit)

// Exception & audit
void createException(context, dao, errorCode, priority, details)
void createAuditLog(context, dao, action, details)

// Utilities
double parseAmount(amountStr)
String getStringValue(obj)
void setPropertySafe(row, property, value)
```

**DataContext** carries data between steps:
```java
// Read transaction data
context.getTransactionId();
context.getSourceType();       // "BANK" or "SECU"
context.getCurrency();
context.getTransactionRow();   // original FormRow

// Write enrichment data
context.setCustomerId("CUST-123");
context.setAdditionalDataValue("myKey", value);

// Read data from previous steps
context.getAdditionalDataValue("counterpartyId");
```

### 11.3 Testing Patterns

Unit tests use JUnit 4 with Mockito:
```java
public class MyStepTest {
    private MyStep step;
    private FormDataDao mockDao;
    private DataContext context;

    @Before
    public void setUp() {
        step = new MyStep();
        mockDao = mock(FormDataDao.class);
        context = new DataContext();
        context.setTransactionId("TEST-001");
    }

    @Test
    public void testSuccess() {
        context.setCurrency("USD");
        FormRow masterRow = new FormRow();
        masterRow.setProperty("c_status", "active");
        when(mockDao.load(any(), eq("app_fd_currency"), eq("USD")))
            .thenReturn(masterRow);

        StepResult result = step.execute(context, mockDao);

        assertTrue(result.isSuccess());
    }
}
```

### 11.4 Build & Deploy

```bash
mvn clean package              # Build plugin JAR
mvn clean package -DskipTests  # Build without tests
mvn test                       # Run tests only
mvn test -Dtest=MyStepTest     # Run single test class
```

Deploy: copy JAR to `[JOGET_HOME]/wflow/app_plugins/`. OSGi auto-loads the bundle.

### 11.5 Debugging & Troubleshooting

**Logging:**
```java
import org.joget.commons.util.LogUtil;
LogUtil.info(CLASS_NAME, "Processing: " + transactionId);
LogUtil.error(CLASS_NAME, e, "Error: " + e.getMessage());
```

**Useful SQL queries:**
```sql
-- Unprocessed transactions
SELECT COUNT(*) FROM app_fd_bank_total_trx WHERE c_status = 'new';
SELECT COUNT(*) FROM app_fd_secu_total_trx WHERE c_status = 'new';

-- F14 rule configuration
SELECT * FROM app_fd_cp_txn_mapping WHERE c_status = 'active';

-- Recent enrichment results
SELECT * FROM app_fd_trx_enrichment ORDER BY dateCreated DESC LIMIT 10;

-- Audit trail for a transaction
SELECT * FROM app_fd_audit_log WHERE c_transaction_id = 'TRX-001' ORDER BY c_timestamp;

-- Statement processing status
SELECT id, c_status, COUNT(t.id) as tx_count
FROM app_fd_bank_statement s
LEFT JOIN app_fd_bank_total_trx t ON t.c_statementId = s.id
GROUP BY s.id, s.c_status;
```

**Common issues:**
- Table names must include `app_fd_` prefix (e.g. `app_fd_customer`)
- Column names must include `c_` prefix (e.g. `c_status`)
- Check logs at `[JOGET_HOME]/wflow/logs/`
- Use `exception_queue` table to review processing errors

**Key constants:**
```java
DomainConstants.SOURCE_TYPE_BANK = "BANK"
DomainConstants.SOURCE_TYPE_SECU = "SECU"
FrameworkConstants.STATUS_NEW = "new"
FrameworkConstants.STATUS_PROCESSING = "processing"
FrameworkConstants.STATUS_ENRICHED = "enriched"
FrameworkConstants.ENTITY_UNKNOWN = "UNKNOWN"
```

---

## 12. Internal Type Codes

Common internal types mapped by F14 rules:

| Code | Description |
|------|-------------|
| `LOAN_PAYMENT` | Loan principal/interest payment |
| `INT_INCOME` | Interest income |
| `INT_EXPENSE` | Interest expense |
| `EQ_BUY` | Equity purchase |
| `EQ_SELL` | Equity sale |
| `SEC_BUY` | Securities purchase |
| `SEC_SELL` | Securities sale |
| `COMM_FEE` | Commission/fee |
| `FX_EXCHANGE` | Foreign exchange conversion |
| `TAX` | Tax payment (VAT, withholding tax) |
| `MGMT_FEE` | Management/safekeeping fee |
| `BOND_INT` | Bond coupon interest |
| `UNCLASSIFIED` | No rule matched |
