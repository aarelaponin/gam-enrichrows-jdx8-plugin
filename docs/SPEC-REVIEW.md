# SPEC.md Review — Discrepancies & Fixes

**Date:** 2026-03-14
**Scope:** Comprehensive review of `docs/SPEC.md` against the actual codebase
**Result:** 6 discrepancies found — all are spec documentation errors (code is correct)

---

## Discrepancies Found

### 1. §8 Exception Types — Priorities are dynamic, not static

**Spec says:** Static priority per exception type (e.g. MISSING_CURRENCY = "high")

**Code does:** `calculateExceptionPriority(context)` computes priority from transaction amount:

| Amount threshold | Priority |
|-----------------|----------|
| ≥ 1,000,000 | `critical` |
| ≥ 100,000 | `high` |
| ≥ 10,000 | `medium` |
| < 10,000 | `low` |
| Unparseable | `medium` (default) |

**Location:** `CurrencyValidationStep.java:281-306` (pattern replicated in other steps)

**Fix:** Replace static priority column in §8 table with a note explaining dynamic priority calculation. Add the threshold table.

---

### 2. §8 Exception Types — 7 internal error exceptions not listed

**Code has these additional exception types in `DomainConstants.java:109-122`:**

| Exception | Purpose |
|-----------|---------|
| `CURRENCY_VALIDATION_ERROR` | Unexpected error in currency step |
| `COUNTERPARTY_DETERMINATION_ERROR` | Unexpected error in counterparty step |
| `CUSTOMER_IDENTIFICATION_ERROR` | Unexpected error in customer step |
| `ASSET_RESOLUTION_ERROR` | Unexpected error in asset step |
| `F14_MAPPING_ERROR` | Unexpected error in F14 step |
| `INVALID_FX_DATE` | Invalid date in FX conversion |
| `FX_CONVERSION_ERROR` | Unexpected error in FX step |

**Fix:** Add a "Step Error Exceptions" subsection to §8 listing these as catch-all error handlers with dynamic priority.

---

### 3. §10 Source Structure — 4 framework files missing from tree

**Actual `framework/` directory has 13 files, spec lists 9.** Missing:

| File | Purpose |
|------|---------|
| `DataLoader.java` | Interface for data loaders |
| `DataPersister.java` | Interface for data persisters |
| `DataStep.java` | Interface for pipeline steps |
| `PersistenceResult.java` | Single-record persistence result |

**Fix:** Add the 4 missing interfaces/classes to the source tree in §10:

```
├── framework/
│   ├── DataLoader.java              # Loader interface
│   ├── DataPersister.java           # Persister interface
│   ├── DataStep.java                # Step interface
│   ├── AbstractDataLoader.java
│   ├── AbstractDataPersister.java
│   ├── AbstractDataStep.java
│   ├── DataContext.java
│   ├── DataPipeline.java
│   ├── StepResult.java
│   ├── PipelineResult.java
│   ├── PersistenceResult.java       # Single-record persistence result
│   ├── BatchPipelineResult.java
│   └── BatchPersistenceResult.java
```

---

### 4. §11.2 Helper Methods — 3 errors in the API listing

| Listed method | Spec says | Reality |
|--------------|-----------|---------|
| `loadRecords(dao, tableName, condition, ...)` | In AbstractDataStep | Does NOT exist in AbstractDataStep. Only exists in `AbstractDataLoader`. |
| `createException(context, dao, errorCode, ...)` | In AbstractDataStep | Does NOT exist as shared method. Each step has its own private `create*Exception()` method. |
| `getStringValue(obj)` | In AbstractDataStep | In **AbstractDataPersister**:123, not AbstractDataStep |
| `setPropertySafe(row, property, value)` | In AbstractDataStep | In **AbstractDataPersister**:114, not AbstractDataStep |

**Fix:** Split the helper methods listing into two groups:

**AbstractDataStep helpers** (available in pipeline steps):
- `loadFormRow(dao, tableName, id)` — load single row by ID
- `saveFormRow(dao, tableName, row)` — save/update a row
- `createAuditLog(context, dao, action, details)` — create audit trail entry
- `parseAmount(amountStr)` — parse amount string to double
- `isFieldResolved(context, fieldName, sentinels...)` — check if field already resolved (re-enrichment guard)

**AbstractDataPersister helpers** (available in persisters):
- `setPropertySafe(row, property, value)` — set property, skips nulls
- `getStringValue(obj)` — null-safe toString
- `saveFormRow(dao, tableName, row)` — save/update a row
- `loadFormRow(dao, tableName, id)` — load single row by ID
- `updateFormRow(dao, tableName, row)` — update existing row

Remove `loadRecords` and `createException` from the listing entirely.

---

### 5. §12 Internal Type Codes — 3 types missing

Used by `BankAssetHintStep` (eligible types set at `BankAssetHintStep.java:33-35`) but not listed in §12:

| Code | Description |
|------|-------------|
| `DIV_INCOME` | Dividend income |
| `DIV_TAX` | Dividend tax withholding |
| `INCOME_TAX` | Income tax withholding |

**Note:** These are string literals in BankAssetHintStep, not yet extracted to DomainConstants.

**Fix:** Add all 3 to the §12 internal types table.

---

### 6. §7 Data Model — 1 table missing

`transactionTypeMap` exists in `DomainConstants.java:25` as `TABLE_TRANSACTION_TYPE_MAP` but is not listed in §7 Master Data tables.

**Fix:** Add `transactionTypeMap` to the Master Data table in §7: `| transactionTypeMap | Transaction type mapping |`

---

## Items Verified as CORRECT

- §2 Execution flow — pipeline order, post-enrichment, pairing invocation all match
- §3.1-3.8 All pipeline steps — implementation matches spec
- §3.3 CustomerIdentificationStep — DOES check "income tax withheld" (line 273), all 6 methods with correct confidence values
- §3.6 BankAssetHintStep — fully matches spec (eligible types, non-blocking, TickerExtractor, no auto-registration)
- §4 Re-enrichment — workspace protection, isFieldResolved, re-enrichment flag all correct
- §5 Persistence — TRX-XXXXXX format, REQUIRES_NEW, self-transition skip, status assignment all correct
- §6 Cross-Statement Pairing — two-phase matching, full pairing requirement, fee disambiguation all correct
- §7 All other tables — all 18 table constants in DomainConstants match spec
- §9 State Management — all status transitions, workspace-protected statuses correct
- §12 Existing internal types — all 13 listed types are used in code
