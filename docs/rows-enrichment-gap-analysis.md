# rows-enrichment Plugin — Gap Analysis: Spec v3.0 vs Implementation

**Date:** 3 March 2026
**Scope:** Compare rows-enrichment-spec.md (v3.0) against actual source code in `gam-plugins/rows-enrichment/`

---

## Executive Summary

The implementation is **substantially aligned** with the specification. The 6-step pipeline, data loading, persistence, and StatusManager integration are all in place and working correctly. The most significant gaps are around **configurability** (several values that the spec requires to be configurable are still hardcoded or use different property key names), **FrameworkConstants cleanup** (partially done — old status constants removed but some processing-related ones remain), and a few **incomplete features** (notably `isLastTransaction()` in F14RuleMappingStep). No structural or architectural gaps exist — the gaps are refinement-level.

**Verdict:** ~85% spec-complete. Remaining work is configuration alignment, minor hardcoding cleanup, and edge-case handling.

---

## 1. IMPLEMENTED CORRECTLY (Spec-Aligned)

### 1.1 Overall Architecture
- RowsEnricher extends DefaultApplicationPlugin — correct
- 6-step pipeline in correct order: CurrencyValidation → CounterpartyDetermination → CustomerIdentification → AssetResolution → F14RuleMapping → FXConversion — **matches spec Section 4**
- Pipeline configured with `stopOnError=false` — correct per spec
- StatusManager created once in RowsEnricher and shared — correct per spec Section 9.5 note
- TransactionDataLoader, DataPipeline, EnrichmentDataPersister separation — correct

### 1.2 gam-framework Integration (Spec Section 2.1)
- All status transitions go through StatusManager.transition() — **correct**
- Status enum used for status values (Status.NEW, Status.PROCESSING, Status.ENRICHED, Status.MANUAL_REVIEW, Status.ERROR, Status.OPEN, Status.CONSOLIDATED) — **correct**
- InvalidTransitionException caught and handled — **correct**
- Custom table name overload used for ENRICHMENT: `statusManager.transition(dao, tableName, EntityType.ENRICHMENT, ...)` with `DomainConstants.TABLE_TRX_ENRICHMENT` ("trxEnrichment") — **correct**
- EntityType enums used: STATEMENT, BANK_TRX, SECU_TRX, ENRICHMENT — **correct**

### 1.3 TransactionDataLoader (Spec Section 7)
- Queries bank_statement for CONSOLIDATED status — **correct** (uses `Status.CONSOLIDATED.getCode()`)
- Filters bank transactions by statement_id + Status.NEW — **correct**
- Filters secu transactions by statement_id + Status.NEW — **correct**
- Creates DataContext per row with correct field mappings (Sections 7.3, 7.4) — **correct**
- Transitions source transactions NEW → PROCESSING via StatusManager — **correct**
- Sorts transactions by date ascending — **correct**
- Uses DomainConstants for all table/field names — **correct**

### 1.4 CurrencyValidationStep (Spec Section 8.1)
- Normalizes currency (trim + uppercase) — **correct**
- Queries currency table for active records — **correct**
- Stores currency_name, decimal_places, currency_symbol in additionalData — **correct**
- Creates MISSING_CURRENCY, INVALID_CURRENCY, CURRENCY_VALIDATION_ERROR exceptions — **correct**
- Amount-based priority calculation — **correct** (thresholds: 1M=critical, 100K=high, 10K=medium, <10K=low)
- Uses DomainConstants.TABLE_CURRENCY_MASTER — **correct**

### 1.5 F14RuleMappingStep (Spec Section 8.5)
- Loads rules from cp_txn_mapping (DomainConstants.TABLE_CP_TXN_MAPPING) — **correct**
- Filters by sourceType, counterparty (specific + SYSTEM), active status, effectiveDate — **correct**
- Priority-based sorting: counterparty-specific first, then SYSTEM, then by priority number — **correct**
- Supports operators: equals, contains, startsWith, endsWith, regex, in — **correct**
- Supports arithmetic conditions — **correct**
- Supports complex combined expressions (AND/OR) — **correct**
- Sets internal_type, f14_rule_id, f14_rule_name, f14_rules_evaluated in additionalData — **correct**
- Creates NO_F14_RULES, NO_RULE_MATCH, F14_MAPPING_ERROR exceptions — **correct**
- On no match: sets UNMATCHED, continues processing (returns StepResult(true)) — **correct**
- Uses FrameworkConstants.ENTITY_SYSTEM and INTERNAL_TYPE_UNMATCHED — **correct**

### 1.6 FXConversionStep (Spec Section 8.6)
- EUR base currency skip (rate=1.0, source="BASE_CURRENCY") — **correct**
- Rate lookup by exact date then recent within maxFxRateAgeDays — **correct**
- Rate inversion logic (1 EUR = X → X to EUR = 1/rate) — **correct**
- Securities: converts fee and total_amount — **correct**
- Missing rate: sets base_amount="0.00", fx_rate_source="MISSING" — **correct**
- Old rate warning: creates OLD_FX_RATE exception — **correct**
- All exception types: MISSING_CURRENCY, INVALID_FX_DATE, FX_RATE_MISSING, OLD_FX_RATE, FX_CONVERSION_ERROR — **correct**
- Uses DomainConstants.TABLE_FX_RATES_EUR — **correct**
- maxFxRateAgeDays read from properties with default 5 — **correct**

### 1.7 EnrichmentDataPersister (Spec Section 9)
- Record ID format: TRX-<6-char-UUID-uppercase> — **correct**
- Target table: DomainConstants.TABLE_TRX_ENRICHMENT ("trxEnrichment") — **correct**
- 52-field mapping matches spec Section 9.4 — **correct** (all sections: Provenance, Transaction Core, Classification, Customer, Asset, Counterparty, Currency & FX, Fee & Pairing, Status & Notes)
- Counterparty routing by type (Bank/Custodian/Broker) — **correct**
- Customer match method mapping (DIRECT_ID→direct_id, etc.) — **correct**
- Type confidence computation (high/medium/low based on internal_type + customer_confidence) — **correct**
- 6 manual review conditions — **all 6 correct** (UNKNOWN customer, UNKNOWN counterparty, UNMATCHED type, low confidence, UNKNOWN asset, MISSING fx_rate)
- Configurable confidence threshold via config map — **correct**
- Enrichment lifecycle: null→NEW→PROCESSING→{ENRICHED|MANUAL_REVIEW|ERROR} via StatusManager — **correct**
- Source transaction status mirrors enrichment status — **correct**
- Statement status update: CONSOLIDATED→ENRICHED (all OK) or ERROR (failures) — **correct**
- Statement metadata update (processing_completed, transactions_processed/success/failed) — **correct**
- Batch processing with per-statement grouping — **correct**
- Description builder with configurable field lists — **correct**
- Settlement date computation (T+0 for bank, T+N with weekend skip for secu) — **correct**
- Debit/credit: direct for bank, configurable mapping for secu — **correct**
- base_fee_eur field for securities — **correct**

### 1.8 FrameworkConstants Cleanup (Spec Section 12.3)
- Old status constants (STATUS_NEW, STATUS_ENRICHED, STATUS_POSTED, STATUS_FAILED, etc.) — **removed, correct**
- PROCESSING_STATUS_ENRICHED, PROCESSING_STATUS_MANUAL_REVIEW — **removed, correct**
- SYSTEM_USER, PIPELINE_VERSION — **removed, correct**
- Retained correctly: STATUS_ACTIVE, STATUS_ACTIVE_CAPITAL, ENTITY_UNKNOWN, ENTITY_SYSTEM, INTERNAL_TYPE_UNMATCHED — **correct**
- FIELD_STATUS retained — **acceptable** (generic field name constant)
- STOP_ON_ERROR_DEFAULT retained — **acceptable** (framework config)

### 1.9 DomainConstants (Spec Section 12.2)
- TABLE_TRX_ENRICHMENT = "trxEnrichment" — **correct** (was the critical fix)
- TABLE_CP_TXN_MAPPING = "cp_txn_mapping" — **added, correct**
- TABLE_TRANSACTION_TYPE_MAP = "transactionTypeMap" — **added, correct**
- TABLE_TRANSACTION_TYPE = "trxType" — **added, correct**
- TABLE_FX_RATES_EUR = "fx_rates_eur" — **added, correct**
- TABLE_CUSTOMER_ACCOUNT = "customer_account" — **added, correct**
- TABLE_BANK = "bank" — **added, correct**
- TABLE_BROKER = "broker" — **added, correct**
- All exception type constants — **added, correct** (19 types)
- All audit action constants — **added, correct** (6 actions)

---

## 2. GAPS — Missing or Incomplete Implementation

### 2.1 CRITICAL: `isLastTransaction()` Returns Hardcoded `false`

**Spec expectation:** F14RuleMappingStep should log summary statistics periodically and at batch completion.

**Implementation:** `isLastTransaction()` at line 812-816 returns hardcoded `false`:
```java
private boolean isLastTransaction() {
    // This is a simple heuristic - in a real implementation,
    // you might pass the total count from the pipeline
    return false;
}
```

**Impact:** Medium. The summary logging in `logSummaryIfNeeded()` at batch completion never triggers. Only the periodic every-50-transactions logging works.

**Fix:** Pass total transaction count from DataPipeline to steps, or use a pipeline completion callback.

---

### 2.2 HIGH: AbstractDataStep Has Hardcoded Table Names

**Spec Section 12.5:** "No hardcoding — all table names must be constants from DomainConstants."

**Implementation:** `AbstractDataStep.updateTransactionStatus()` at lines 120-121 uses hardcoded strings:
```java
String tableName = "bank".equals(context.getSourceType()) ?
        "bank_total_trx" : "secu_total_trx";
```

And `createAuditLog()` at line 156 uses:
```java
saveFormRow(formDataDao, "audit_log", auditRow);
```

**Impact:** Low in practice (values match DomainConstants), but violates the "no hardcoding" principle. If table names ever change, these would be missed.

**Fix:** Replace with `DomainConstants.TABLE_BANK_TOTAL_TRX` / `DomainConstants.TABLE_SECU_TOTAL_TRX` and `DomainConstants.TABLE_AUDIT_LOG`.

---

### 2.3 HIGH: AbstractDataStep.updateTransactionStatus() Writes Status Directly

**Spec Section 2.1:** "Never write status directly. All status transitions must go through StatusManager.transition()."

**Implementation:** `AbstractDataStep.updateTransactionStatus()` at lines 112-128 does:
```java
trxRow.setProperty("status", status);
```

This bypasses StatusManager entirely — no audit trail, no transition validation.

**Impact:** Medium. This method exists in the base class but may not be called by any step in the current implementation (steps use StatusManager via the loader/persister). However, its presence is a risk — any future step could accidentally call it.

**Fix:** Either remove this method entirely (since status transitions happen in loader/persister) or refactor to use StatusManager. Add `@Deprecated` annotation at minimum.

---

### 2.4 MEDIUM: Plugin Property Key Name Mismatches

The spec defines property names in Section 12.1, but the persister uses different keys in some cases:

| Spec Property Name | Persister Config Key | Match? |
|---|---|---|
| `descriptionFieldsBank` | `bankDescriptionFields` | **MISMATCH** |
| `descriptionFieldsSecu` | `secuDescriptionFields` | **MISMATCH** |
| `settlementConvention` | `settlementDays` | **MISMATCH** (spec uses string "2", impl uses int) |
| `confidenceThresholdHigh` | `confidenceThresholdHigh` | OK |
| `confidenceThresholdMedium` | `confidenceThresholdMedium` | OK |
| `pipelineVersion` | `pipelineVersion` | OK |
| `descriptionMaxLength` | `descriptionMaxLength` | OK |
| `secuDebitCreditMapping` | `secuDebitCreditMapping` | OK |
| `maxFxRateAgeDays` | `maxFxRateAgeDays` | OK (read from properties in FXConversionStep) |
| `baseCurrency` | — | **MISSING** (hardcoded as `DomainConstants.BASE_CURRENCY = "EUR"`) |
| `batchSize` | — | **NOT USED** (loader hardcodes `100` at line 99) |
| `stopOnError` | — | **HARDCODED** in RowsEnricher: `.setStopOnError(false)` |

**Fix:** Align property key names to spec. Read `baseCurrency`, `batchSize`, `stopOnError` from properties.

---

### 2.5 MEDIUM: Lineage Note Format Slightly Differs

**Spec Section 9.4:** Format is `"Pipeline v{version}: {N}/{total} steps OK. Steps: {step1},{step2},..."`

**Implementation:** `buildLineageNote()` produces: `"Pipeline v%s: %d steps OK. Steps: %s"` — missing the `/{total}` denominator.

**Fix:** Change format string to `"Pipeline v%s: %d/%d steps OK. Steps: %s"` and pass total step count (6).

---

### 2.6 MEDIUM: Exception Queue Missing Some Spec Fields

**Spec Section 10.1:** Exceptions should include `assigned_to` and `due_date`.

**Implementation status by step:**

| Step | assigned_to | due_date |
|---|---|---|
| CurrencyValidationStep | **MISSING** | **MISSING** |
| CounterpartyDeterminationStep | **MISSING** | **MISSING** |
| CustomerIdentificationStep | **MISSING** | **MISSING** |
| AssetResolutionStep | **MISSING** | **MISSING** |
| F14RuleMappingStep | **Present** | **Present** |
| FXConversionStep | **Present** | **Present** |

Only F14RuleMappingStep and FXConversionStep populate `assigned_to` and `due_date`. The other 4 steps' exception creation methods don't set these fields.

**Spec assignment rules:**
- critical/high → "supervisor", due +1 day
- FX exceptions → "fx_specialist", due +1 day
- medium → "operations", due +3 days
- low → "operations", due +7 days

**Fix:** Add assigned_to and due_date to exception creation in CurrencyValidation, CounterpartyDetermination, CustomerIdentification, and AssetResolution steps.

---

### 2.7 MEDIUM: Exception Status Not Transitioned via StatusManager

**Spec Section 10.1:** Exception status should use `StatusManager.transition(dao, EntityType.EXCEPTION, exceptionId, Status.OPEN, "rows-enrichment", reason)`.

**Implementation:** All steps directly set `exceptionRow.setProperty("status", Status.OPEN.getCode())` — no StatusManager transition for exceptions.

**Impact:** No audit trail for exception creation. The StatusManager would auto-generate a TransitionAuditEntry for the exception lifecycle.

**Fix:** After saving the exception row, call `statusManager.transition(dao, EntityType.EXCEPTION, exceptionId, Status.OPEN, "rows-enrichment", reason)`.

---

### 2.8 LOW: Source Row Metadata Not Updated Post-Persistence

**Spec Section 9.6:** After persistence, update source row: `enrichment_date = now()`, `enriched_record_id = <new F01.05 row ID>`.

**Implementation:** The persister stores `enriched_record_id` in context additionalData (line 89) but does NOT write back to the source transaction row (F01.03/F01.04). No `enrichment_date` or `enriched_record_id` is persisted on the source.

**Fix:** After successful persistence, load the source transaction row and set `enrichment_date` and `enriched_record_id`.

---

### 2.9 LOW: `enriched_record_id` Stored in Context but Not in Persisted Data

**Spec Section 9.6:** `additionalData.enriched_record_id = <new F01.05 row ID>` for downstream use.

**Implementation:** Done correctly in context (line 89: `context.setAdditionalDataValue("enriched_record_id", recordId)`), but not written back to the source transaction row as specified.

**Note:** This is the same gap as 2.8 — the context update is correct, but the source row update is missing.

---

### 2.10 LOW: All Steps Load Entire Tables and Filter in Memory

**Spec:** Not explicitly addressed, but the implementation pattern of loading all records and filtering in memory is consistent across all steps:

- CurrencyValidationStep: loads ALL currency rows
- CounterpartyDeterminationStep: loads ALL counterparty_master, bank, broker rows
- CustomerIdentificationStep: loads ALL customer, customer_account rows
- AssetResolutionStep: loads ALL asset_master rows
- F14RuleMappingStep: loads ALL cp_txn_mapping rows
- FXConversionStep: loads ALL fx_rates_eur rows

**Impact:** Acceptable for current scale (~20-25 transactions/day, small MDM tables). Comments in code explain this is due to Joget's c_ prefix handling with SQL WHERE clauses. Would need optimization if data volumes grow significantly.

---

### 2.11 LOW: No Circuit Breaker or Retry Mechanism

**Spec:** Not explicitly specified, but the spec mentions robust error handling.

**Implementation:** No retry on transient failures (DB connection drops, timeouts). Each step catches exceptions and creates exception queue entries, but never retries.

**Impact:** Low for current scale. Could be a future enhancement.

---

### 2.12 LOW: F14RuleMappingStep Instance Variables Not Thread-Safe

The step maintains instance-level counters (`totalProcessed`, `successCount`, `noRulesCount`, etc.) that are not thread-safe:
```java
private int totalProcessed = 0;
private int successCount = 0;
```

**Impact:** Low — the pipeline processes transactions sequentially within a single thread. If parallelization is ever added, this would be a bug.

---

## 3. MINOR INCONSISTENCIES

### 3.1 Audit Log `status` Field in AbstractDataStep

`createAuditLog()` sets a `status` field on the audit row (lines 150-154):
```java
String status = context.getProcessingStatus();
if (status == null || status.isEmpty()) {
    status = "processing";
}
auditRow.setProperty("status", status);
```

The spec's audit log structure (Section 11.2) doesn't include a `status` field — it has `transaction_id`, `step_name`, `action`, `details`, `timestamp`. The hardcoded `"processing"` fallback also violates the no-hardcoding rule.

**Fix:** Remove the `status` field from step-level audit logs, or add it to the spec if desired.

---

### 3.2 FXConversionStep Stores `rateType` But Spec Doesn't Use It

The FXRateInfo inner class stores `rateType` (e.g., "spot"), but the spec's additionalData map (Section 6.4) doesn't include an `fx_rate_type` key — this was explicitly dropped per spec Section 13.1.

**Impact:** None — the value is used internally in FXConversionStep but not persisted to F01.05.

---

### 3.3 Description Truncation Behavior

**Spec Section 13.2:** "Truncate to 2000 characters if needed (preserving complete last field)."

**Implementation:** `buildDescription()` does simple substring truncation:
```java
if (result.length() > maxLength) {
    result = result.substring(0, maxLength);
}
```

This may cut a field value mid-word rather than preserving the complete last field.

**Fix:** Truncate at the last ` | ` separator before the limit.

---

### 3.4 `computeDebitCredit` Hardcoded Fallback

The `mapSecuDebitCredit()` method in EnrichmentDataPersister has a configurable mapping from properties but also a hardcoded switch/case fallback (lines 319-325). The spec says the mapping should be fully configurable via `secuDebitCreditMapping` property.

**Impact:** Low — the hardcoded defaults are reasonable and only activate if the property is missing. But the spec says "no hardcoding."

**Fix:** Log a warning when using hardcoded defaults, or require the property to be set.

---

## 4. SUMMARY TABLE

| # | Gap | Severity | Effort |
|---|-----|----------|--------|
| 2.1 | isLastTransaction() hardcoded false | Medium | Small |
| 2.2 | AbstractDataStep hardcoded table names | High | Small |
| 2.3 | AbstractDataStep writes status directly | High | Small |
| 2.4 | Plugin property key name mismatches | Medium | Medium |
| 2.5 | Lineage note missing total step count | Medium | Trivial |
| 2.6 | 4 steps missing assigned_to/due_date on exceptions | Medium | Medium |
| 2.7 | Exception status not via StatusManager | Medium | Small |
| 2.8 | Source row metadata not updated post-persistence | Low | Small |
| 2.10 | All tables loaded into memory | Low | N/A (by design) |
| 2.11 | No retry mechanism | Low | Future |
| 2.12 | Instance variables not thread-safe | Low | Future |
| 3.1 | Audit log extra status field | Minor | Trivial |
| 3.3 | Description truncation doesn't preserve last field | Minor | Small |
| 3.4 | Secu D/C hardcoded fallback | Minor | Trivial |

---

## 5. RECOMMENDED PRIORITY ORDER

1. **Property key alignment** (2.4) — ensures Joget plugin configuration works correctly
2. **AbstractDataStep cleanup** (2.2, 2.3) — remove hardcoded table names, deprecate/remove direct status write
3. **Exception queue completeness** (2.6, 2.7) — add assigned_to/due_date to all steps, use StatusManager for exception status
4. **Source row update** (2.8) — write enrichment_date and enriched_record_id back to source rows
5. **Lineage note fix** (2.5) — trivial format string change
6. **isLastTransaction** (2.1) — pass batch size to enable end-of-batch summary logging
7. **Minor fixes** (3.1, 3.3, 3.4) — cleanup items

---

**End of Gap Analysis**
