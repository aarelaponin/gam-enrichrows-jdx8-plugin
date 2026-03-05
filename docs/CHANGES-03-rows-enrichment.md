# rows-enrichment — Change Spec

**Version:** 8.1-SNAPSHOT
**Date:** 4 March 2026
**Dependencies:**
- gam-framework 8.1-SNAPSHOT (no changes)
- statement-importer must apply CHANGES-02 before Phase 2 testing

---

## 1. Purpose

Fix Phase 1 enrichment pipeline to correctly handle bank vs secu distinctions, Estonian transaction types, and customer resolution. Add Phase 2 cross-statement pairing. Changes are organized so Phase 1 fixes can be tested independently before Phase 2 is implemented.

---

## 2. Phase 1 Fixes (5 changes, independently testable)

### 2.1 CustomerIdentificationStep — Skip Secu Transactions

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/steps/CustomerIdentificationStep.java`

**Current code (line 216-220):**
```java
@Override
public boolean shouldExecute(DataContext context) {
    // Execute for both bank and secu transactions that haven't failed yet
    return context.getErrorMessage() == null || context.getErrorMessage().isEmpty();
}
```

**Change to:**
```java
@Override
public boolean shouldExecute(DataContext context) {
    // Bank only: secu transactions have no customer data (investment bank acts on behalf of customers).
    // Customer-to-portfolio allocation for secu is a manual operations process.
    if (!"bank".equals(context.getSourceType())) {
        return false;
    }
    return context.getErrorMessage() == null || context.getErrorMessage().isEmpty();
}
```

**Why:** Secu CSV contains no customer data. The investment bank executes securities transactions on behalf of its customers. Running customer identification on secu creates false MISSING_CUSTOMER exceptions for every secu transaction. Customer portfolio allocation is a separate operations task handled via the enrichment-workspace split function.

**Test:**
```sql
-- After re-run: no MISSING_CUSTOMER exceptions for secu
SELECT COUNT(*)
FROM app_fd_exception_queue
WHERE c_source_type = 'secu'
  AND c_exception_type = 'MISSING_CUSTOMER';
-- Expected: 0
```

---

### 2.2 CustomerIdentificationStep — Remove Dead CUST-XXXXXX Check

**File:** Same file, `identifyByDirectId()` method (line 241)

**Current code:**
```java
// First check if it's an actual customer ID (CUST-XXXXXX format or similar)
if (customerIdField.startsWith("CUST-") || customerIdField.matches("^[A-Z]+-\\d+$")) {
    // This looks like an actual customer ID
    if (customerExists(customerIdField, formDataDao)) {
        return customerIdField;
    } else {
        return null;
    }
}
```

**Change to:**
```java
// The customer_id field from bank CSV (Isikukood või registrikood) always contains
// a registrationNumber (8-digit, for companies) or personalId (11-digit, for individuals).
// It never contains an internal customer ID format.
```

Remove the `if (customerIdField.startsWith("CUST-")...)` block entirely. The flow should go directly to `findCustomerByRegistrationOrPersonalId()`:

```java
private String identifyByDirectId(DataContext context, FormDataDao formDataDao) {
    String customerIdField = context.getCustomerId();
    if (customerIdField == null || customerIdField.trim().isEmpty()) {
        return null;
    }
    customerIdField = customerIdField.trim();

    // Bank CSV "Isikukood või registrikood" is always registrationNumber or personalId
    return findCustomerByRegistrationOrPersonalId(customerIdField, formDataDao);
}
```

**Why:** The bank CSV field `Isikukood või registrikood` (mapped to `customer_id`) always contains a registrationNumber (8-digit) or personalId (11-digit). The value is never in `CUST-XXXXXX` format. The dead code path can never succeed with real data and adds confusion.

**Test:** After re-run, verify customer identification still works:
```sql
SELECT c_resolved_customer_id, c_customer_match_method, COUNT(*)
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'bank'
GROUP BY c_resolved_customer_id, c_customer_match_method;
-- Expected: customers resolved via DIRECT_ID or ACCOUNT_NUMBER methods
```

---

### 2.3 CounterpartyDeterminationStep — Add Estonian Transaction Types

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/steps/CounterpartyDeterminationStep.java`

**Current code (line 355-376), `determineSecuritiesCounterpartyType()`:**
```java
if (transactionType.contains("BUY") || transactionType.contains("SELL") ||
        transactionType.contains("TRADE")) {
    return "Broker";
}
if (transactionType.contains("CUSTODY") || transactionType.contains("SAFEKEEPING") ||
        transactionType.contains("DIVIDEND") || transactionType.contains("CORPORATE")) {
    return "Custodian";
}
return "Custodian";
```

**Change to:**
```java
private String determineSecuritiesCounterpartyType(DataContext context) {
    String transactionType = context.getType();
    if (transactionType == null || transactionType.trim().isEmpty()) {
        return "Custodian";
    }

    String upper = transactionType.trim().toUpperCase();

    // Trading activities → Broker
    // English: BUY, SELL, TRADE
    // Estonian (LHV): ost (buy), müük (sell)
    if (upper.contains("BUY") || upper.contains("SELL") || upper.contains("TRADE")
            || upper.equals("OST") || upper.startsWith("MÜÜ")) {
        return "Broker";
    }

    // Corporate actions → Custodian
    // English: CUSTODY, SAFEKEEPING, DIVIDEND, CORPORATE
    // Estonian (LHV): split+ (split in), split- (split out)
    if (upper.contains("CUSTODY") || upper.contains("SAFEKEEPING")
            || upper.contains("DIVIDEND") || upper.contains("CORPORATE")
            || upper.startsWith("SPLIT")) {
        return "Custodian";
    }

    return "Custodian";
}
```

**Why:** LHV secu CSV uses Estonian type values: `ost` (buy), `müük` (sell), `split+` (split in), `split-` (split out). The current English-only checks (`BUY`, `SELL`) never match, so all secu transactions default to "Custodian" — buy/sell transactions should be "Broker".

**Note on `toUpperCase()`:** Estonian `müük` → `MÜÜK`. The `.startsWith("MÜÜ")` check handles this. Using `startsWith` rather than `equals` to handle potential suffixes.

**Test:**
```sql
SELECT c_source_tp, c_counterparty_source,
       SUBSTRING_INDEX(c_description, ' ', 1) AS trx_type, COUNT(*)
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'secu'
GROUP BY c_source_tp, c_counterparty_source, trx_type;
-- Expected: ost/müük → counterparty_source should reflect Broker
```

---

### 2.4 EnrichmentDataPersister — Secu Manual Review Logic

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/persister/EnrichmentDataPersister.java`

**Current `determineManualReviewStatus()` (line 460-505):**

Condition 1 checks `customer_id = "UNKNOWN"` → triggers MANUAL_REVIEW for both bank and secu.

**Change:** Add source type awareness. For secu, UNKNOWN customer is expected (not an error):

```java
boolean determineManualReviewStatus(DataContext context, Map<String, Object> config) {
    Map<String, Object> data = context.getAdditionalData();
    if (data == null) return true;

    boolean isBank = "bank".equals(context.getSourceType());

    // 1. UNKNOWN customer — bank only (secu has no customer by design)
    if (isBank) {
        String customerId = context.getCustomerId();
        if (FrameworkConstants.ENTITY_UNKNOWN.equals(customerId)) {
            return true;
        }
    }

    // 2. UNKNOWN counterparty — both bank and secu
    String counterpartyId = getStringValue(data.get("counterparty_id"));
    if (FrameworkConstants.ENTITY_UNKNOWN.equals(counterpartyId)) {
        return true;
    }

    // 3. UNMATCHED internal type — both
    String internalType = getStringValue(data.get("internal_type"));
    if (FrameworkConstants.INTERNAL_TYPE_UNMATCHED.equals(internalType)) {
        return true;
    }

    // 4. Low customer confidence — bank only
    if (isBank) {
        int confidenceThreshold = getConfigInt(config, "confidenceThresholdHigh", 80);
        Object customerConfidence = data.get("customer_confidence");
        if (customerConfidence instanceof Number) {
            double confidence = ((Number) customerConfidence).doubleValue();
            if (confidence < confidenceThreshold) {
                return true;
            }
        }
    }

    // 5. UNKNOWN asset — secu only
    String assetId = getStringValue(data.get("asset_id"));
    if (FrameworkConstants.ENTITY_UNKNOWN.equals(assetId)) {
        return true;
    }

    // 6. Missing FX rate — both
    String fxRateSource = getStringValue(data.get("fx_rate_source"));
    if ("MISSING".equals(fxRateSource)) {
        return true;
    }

    return false;
}
```

**Effect:**
- Bank with UNKNOWN customer → MANUAL_REVIEW (data error, needs investigation)
- Secu with UNKNOWN customer → ENRICHED (expected, not an error)
- Secu with UNKNOWN asset → MANUAL_REVIEW (data error)
- Secu with all automated dimensions OK → ENRICHED

This aligns with gam-framework's ENRICHMENT transition map: only ENRICHED records can transition to PAIRED (Phase 2).

---

### 2.5 PORTFOLIO_ALLOCATION_REQUIRED Exception for Secu (Two Files)

**Architectural decision:** Exception creation belongs in the orchestrator (`RowsEnricher`), not inside the persister. The persister's single responsibility is creating and saving the F01.05 enrichment record and managing its status transition. Post-persistence operational signals (like "this secu record needs portfolio allocation") are the orchestrator's concern. This follows the existing pattern where pipeline steps (not the persister) create exceptions via the exception_queue table.

#### 2.5a EnrichmentDataPersister — Store needs_manual_review flag on context

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/persister/EnrichmentDataPersister.java`

The persister already stores `enriched_record_id` in the context after persistence (line 89). Add the `needs_manual_review` flag so the orchestrator can determine the final status without an extra DB round-trip.

**Current code (line 82-89 in persist()):**
```java
String recordId = enrichedRow.getId();
Status targetStatus = needsManualReview ? Status.MANUAL_REVIEW : Status.ENRICHED;

// Transition enrichment record through lifecycle via StatusManager
transitionEnrichment(dao, recordId, targetStatus, needsManualReview);

// Update context with persisted record ID
context.setAdditionalDataValue("enriched_record_id", recordId);
```

**Change to:**
```java
String recordId = enrichedRow.getId();
Status targetStatus = needsManualReview ? Status.MANUAL_REVIEW : Status.ENRICHED;

// Transition enrichment record through lifecycle via StatusManager
transitionEnrichment(dao, recordId, targetStatus, needsManualReview);

// Update context with persistence outcome (used by orchestrator for post-persistence logic)
context.setAdditionalDataValue("enriched_record_id", recordId);
context.setAdditionalDataValue("needs_manual_review", needsManualReview);
```

**Why:** One line addition. The context is a shared data object that flows through the pipeline — the persister already writes to it. This is the minimal data the orchestrator needs to decide whether to create the PORTFOLIO_ALLOCATION_REQUIRED exception, without loading the record back from DB.

#### 2.5b RowsEnricher — Post-persistence exception creation

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/lib/RowsEnricher.java`

**In `execute()`, add after `persistBatch()` returns (before the persistence logging block at line 111):**
```java
BatchPersistenceResult persistenceResult = persister.persistBatch(
    transactions,
    batchResult,
    formDataDao,
    properties
);

// Post-persistence: create operational exceptions for secu ENRICHED records
createPostEnrichmentExceptions(transactions, formDataDao);
```

**Add two new methods to RowsEnricher:**
```java
/**
 * Create post-enrichment operational exceptions.
 * Runs after persistence phase to signal downstream workflows.
 *
 * Currently: creates PORTFOLIO_ALLOCATION_REQUIRED for secu records
 * that reached ENRICHED status (all automated dimensions resolved).
 * This tells operations that the record is ready for customer
 * portfolio allocation via the enrichment-workspace split function.
 */
private void createPostEnrichmentExceptions(List<DataContext> transactions,
                                            FormDataDao formDataDao) {
    int count = 0;
    for (DataContext context : transactions) {
        if (!DomainConstants.SOURCE_TYPE_SECU.equals(context.getSourceType())) {
            continue;
        }

        Map<String, Object> data = context.getAdditionalData();
        if (data == null) continue;

        // enriched_record_id is set by the persister after successful save
        String enrichedRecordId = data.get("enriched_record_id") != null
                ? data.get("enriched_record_id").toString() : null;
        if (enrichedRecordId == null) continue;

        // needs_manual_review flag is set by the persister (§2.5a)
        Object needsReview = data.get("needs_manual_review");
        if (Boolean.FALSE.equals(needsReview)) {
            // Record went to ENRICHED → needs portfolio allocation
            createOperationalException(context, formDataDao, enrichedRecordId,
                    DomainConstants.EXCEPTION_PORTFOLIO_ALLOCATION_REQUIRED,
                    "Securities transaction enriched successfully. " +
                    "Customer portfolio allocation required via workspace split.",
                    "medium");
            count++;
        }
    }
    if (count > 0) {
        LogUtil.info(CLASS_NAME, "Created " + count +
                " PORTFOLIO_ALLOCATION_REQUIRED exceptions for secu ENRICHED records");
    }
}

/**
 * Create an operational exception in the exception_queue table.
 * Follows the same field pattern as step-level exception creation
 * (see CustomerIdentificationStep.createCustomerException()).
 */
private void createOperationalException(DataContext context, FormDataDao formDataDao,
                                        String enrichedRecordId, String exceptionType,
                                        String exceptionDetails, String priority) {
    try {
        FormRow exceptionRow = new FormRow();
        exceptionRow.setId(UUID.randomUUID().toString());

        // Exception identifiers
        exceptionRow.setProperty("transaction_id", context.getTransactionId());
        exceptionRow.setProperty("statement_id", context.getStatementId());
        exceptionRow.setProperty("source_type", context.getSourceType());
        exceptionRow.setProperty("enriched_record_id", enrichedRecordId);

        // Exception details
        exceptionRow.setProperty("exception_type", exceptionType);
        exceptionRow.setProperty("exception_details", exceptionDetails);
        exceptionRow.setProperty("exception_date", new Date().toString());

        // Transaction context for resolution
        exceptionRow.setProperty("amount", context.getAmount());
        exceptionRow.setProperty("currency", context.getCurrency());
        exceptionRow.setProperty("transaction_date", context.getTransactionDate());
        exceptionRow.setProperty("ticker", context.getTicker());
        exceptionRow.setProperty("description", context.getDescription());

        // Priority and assignment
        exceptionRow.setProperty("priority", priority);
        exceptionRow.setProperty("status", Status.OPEN.getCode());
        exceptionRow.setProperty("assigned_to", "operations");

        FormRowSet rowSet = new FormRowSet();
        rowSet.add(exceptionRow);
        formDataDao.saveOrUpdate(null, DomainConstants.TABLE_EXCEPTION_QUEUE, rowSet);

    } catch (Exception e) {
        LogUtil.error(CLASS_NAME, e,
                "Error creating operational exception for: " + context.getTransactionId());
    }
}
```

**Additional imports needed in RowsEnricher.java:**
```java
import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.framework.status.Status;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
```
(Check which are already imported — `DomainConstants` and `Status` likely already are from the existing code.)

**File 2:** `src/main/java/com/fiscaladmin/gam/enrichrows/constants/DomainConstants.java`

**Add to the Exception Types section (after line 113):**
```java
public static final String EXCEPTION_PORTFOLIO_ALLOCATION_REQUIRED = "PORTFOLIO_ALLOCATION_REQUIRED";
```

**Why this architecture:**
- **Persister stays pure**: `EnrichmentDataPersister.persist()` creates F01.05 records and manages status transitions — nothing else
- **Steps create step-level exceptions**: `CustomerIdentificationStep`, `CounterpartyDeterminationStep` etc. create exceptions about their own domain (MISSING_CUSTOMER, COUNTERPARTY_NOT_FOUND)
- **Orchestrator creates operational exceptions**: `RowsEnricher` creates cross-cutting operational signals that depend on the final outcome of the full pipeline + persistence, not any single step
- **Follows existing patterns**: The exception record structure matches the existing pattern in `CustomerIdentificationStep.createCustomerException()` (same table, same fields)

**Distinction for the exception queue:**
- Secu ENRICHED + PORTFOLIO_ALLOCATION_REQUIRED → normal ops workflow (customer allocation via workspace split)
- Secu MANUAL_REVIEW + UNKNOWN_ASSET → data error (needs investigation)
- Bank MANUAL_REVIEW + MISSING_CUSTOMER → data quality issue

**Test:**
```sql
-- After re-run: every secu ENRICHED record has PORTFOLIO_ALLOCATION_REQUIRED exception
SELECT e.c_source_type, e.c_exception_type, COUNT(*)
FROM app_fd_exception_queue e
WHERE e.c_exception_type = 'PORTFOLIO_ALLOCATION_REQUIRED'
GROUP BY e.c_source_type, e.c_exception_type;
-- Expected: source_type=secu, count = number of secu ENRICHED records

-- No MISSING_CUSTOMER for secu
SELECT COUNT(*)
FROM app_fd_exception_queue
WHERE c_source_type = 'secu' AND c_exception_type = 'MISSING_CUSTOMER';
-- Expected: 0
```

---

## 3. Phase 1 Testing (all 5 changes together)

After applying changes 2.1–2.5, re-run the enrichment pipeline and verify:

```sql
-- 1. Bank status distribution
SELECT c_status, COUNT(*)
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'bank'
GROUP BY c_status;
-- Expected: enriched (most), manual_review (few with real issues), no false MISSING_CUSTOMER

-- 2. Secu status distribution
SELECT c_status, COUNT(*)
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'secu'
GROUP BY c_status;
-- Expected: enriched (most), manual_review (only if asset/counterparty/type/FX issues)
-- NOT manual_review for customer — that's expected behavior

-- 3. Secu customer fields are UNKNOWN (expected)
SELECT c_resolved_customer_id, c_customer_match_method, COUNT(*)
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'secu'
GROUP BY c_resolved_customer_id, c_customer_match_method;
-- Expected: all UNKNOWN / unresolved (or NULL if step skipped)

-- 4. Exception types
SELECT c_source_type, c_exception_type, COUNT(*)
FROM app_fd_exception_queue
GROUP BY c_source_type, c_exception_type
ORDER BY c_source_type, c_exception_type;
-- Expected:
--   bank, MISSING_CUSTOMER: only if real data issues
--   secu, PORTFOLIO_ALLOCATION_REQUIRED: one per secu ENRICHED record
--   secu, MISSING_CUSTOMER: 0 (should not exist)

-- 5. Secu counterparty types
SELECT
  CASE WHEN c_description LIKE '%ost%' OR c_description LIKE '%Ost%' THEN 'buy'
       WHEN c_description LIKE '%müük%' OR c_description LIKE '%Müük%' THEN 'sell'
       ELSE 'other' END AS trx_type,
  c_counterparty_source, COUNT(*)
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'secu'
GROUP BY trx_type, c_counterparty_source;
-- Expected: buy → Broker, sell → Broker, other → Custodian
```

---

## 4. Phase 2: Cross-Statement Pairing (3 changes)

**Prerequisite:** CHANGES-02-statement-importer applied (c_transaction_reference GROUP_CONCAT'd for traceability, though it is NOT the primary pairing key).

### 4.0 Understanding the Pairing Problem

Both bank and secu consolidation merge the same underlying execution events into single rows:

- 7 raw ADBE sell executions → 1 consolidated secu row (qty summed, references GROUP_CONCAT'd)
- 7 raw "Securities sell (ADBE)" bank rows → 1 consolidated bank row (amounts summed, references GROUP_CONCAT'd)

The pairing operates at the **consolidated level** — it matches consolidated secu rows with consolidated bank rows. The individual reference numbers are already aggregated on both sides.

**Primary pairing key: ticker + date + type**

| Matching Dimension | Secu F01.05 Source | Bank F01.05 Source | Example |
|---|---|---|---|
| Ticker | `resolved_asset_id` → asset_master.ticker, or extracted from F01.04.ticker | Extracted from description parentheses: "Securities buy (**ADBE**)" | ADBE |
| Date | F01.04.value_date (settlement date) | F01.03.payment_date | 2024-06-07 |
| Type | F01.04.type: ost → buy, müük → sell | Description pattern: "Securities buy" / "Securities sell" | buy |

The fee row is identified separately:
- Description "Securities commission fee (TICKER)" on the same date → fee for that ticker's transaction

### 4.1 F01.05 Form — Add source_reference Field

**File:** F01.05-trxEnrichment.json (Joget form definition)

Add a new field to the Fee & Pairing section:

```json
{
  "className": "org.joget.apps.form.lib.HiddenField",
  "properties": {
    "id": "source_reference",
    "label": "Source Reference"
  }
}
```

This stores the GROUP_CONCAT'd reference numbers from the consolidated source. Serves as audit trail and secondary verification, not primary pairing key.

### 4.2 EnrichmentDataPersister — Populate source_reference and pairing_ticker

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/persister/EnrichmentDataPersister.java`

In `createEnrichedRecord()`, add to the Fee & Pairing section:

```java
// Source references (GROUP_CONCAT'd from consolidation) — for audit trail
if ("secu".equals(context.getSourceType())) {
    setPropertySafe(row, "source_reference", context.getReference());
} else if ("bank".equals(context.getSourceType())) {
    FormRow trxRow = context.getTransactionRow();
    if (trxRow != null) {
        setPropertySafe(row, "source_reference",
            trxRow.getProperty("transaction_reference"));
    }
}
```

The pairing_ticker for bank transactions is extracted from the payment_description by the pairing step at runtime (not stored on F01.05).

**Test:**
```sql
-- Verify source_reference populated (will be comma-separated when multiple raw rows consolidated)
SELECT c_source_tp, c_source_reference, c_description
FROM app_fd_trxEnrichment
WHERE c_source_reference IS NOT NULL AND c_source_reference != ''
LIMIT 10;
```

### 4.3 TransactionPairingStep — New Class

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/steps/TransactionPairingStep.java`

This is a new class that runs independently from the Phase 1 pipeline. It operates entirely on F01.05 records.

**Entry point:** Can be invoked from RowsEnricher as a separate method, or as a separate Joget process tool.

**Algorithm:**

```
1. Load all F01.05 records where status = ENRICHED AND pair_id IS NULL
2. Separate into secu list and bank list
3. Build bank index: for each bank record, extract ticker from description:
     "Securities buy (ADBE)" → ticker="ADBE", role="principal"
     "Securities sell (ADBE)" → ticker="ADBE", role="principal"
     "Securities commission fee (ADBE)" → ticker="ADBE", role="fee"
   Index key: (ticker, payment_date)
4. For each secu record:
   a. Get ticker from resolved_asset_id or F01.04.ticker (via DataContext)
   b. Get settlement date = F01.04.value_date (which matches bank payment_date)
   c. Look up bank records by (ticker, date) in the index
   d. From matches, identify:
      - principal: description matches "Securities buy|sell (TICKER)"
      - fee: description matches "Securities commission fee (TICKER)"
   e. Validate:
      - secu total_amount ≈ bank principal amount (sign-adjusted)
      - secu fee ≈ bank fee amount (if fee exists)
      - currency matches
   f. If match found:
      - Generate pair_id (UUID)
      - Update secu F01.05: pair_id, fee_trx_id (if fee), has_fee
      - Update bank principal F01.05: pair_id
      - Update bank fee F01.05: pair_id (if exists)
      - Transition all matched: ENRICHED → PAIRED
      - Update F01.04: bank_payment_trx_id, bank_fee_trx_id
      - Create PAIR entity record (trx_pair table)
      - Cross-verify: source_reference overlap as confirmation
   g. If no match: leave unpaired (will retry on next run)
```

**Ticker extraction from bank description:**
```java
private String extractTickerFromDescription(String description) {
    // Pattern: "Securities buy (TICKER)" or "Securities commission fee (TICKER)"
    if (description == null) return null;
    int open = description.lastIndexOf('(');
    int close = description.lastIndexOf(')');
    if (open >= 0 && close > open) {
        return description.substring(open + 1, close).trim();
    }
    return null;
}
```

**Source reference cross-verification (audit, not matching):**
```java
// After ticker+date matching, verify reference overlap as confirmation
Set<String> secuRefs = new HashSet<>(Arrays.asList(
    secuRecord.getProperty("source_reference").split(",")));
Set<String> bankRefs = new HashSet<>(Arrays.asList(
    bankRecord.getProperty("source_reference").split(",")));
secuRefs.retainAll(bankRefs); // intersection
boolean refsOverlap = !secuRefs.isEmpty();
// If refs overlap → high confidence match
// If no overlap → still valid (different consolidation groups may not share refs)
// Log the verification result either way
```

**PAIR entity record fields:**
```java
pairRow.setProperty("secu_enrichment_id", secuRecord.getId());
pairRow.setProperty("bank_principal_enrichment_id", principalRecord.getId());
pairRow.setProperty("bank_fee_enrichment_id", feeRecord != null ? feeRecord.getId() : "");
pairRow.setProperty("ticker", ticker);
pairRow.setProperty("pair_date_match", settlementDate);
pairRow.setProperty("secu_amount", secuRecord.getProperty("original_amount"));
pairRow.setProperty("bank_amount", principalRecord.getProperty("original_amount"));
pairRow.setProperty("currency", secuRecord.getProperty("original_currency"));
pairRow.setProperty("references_overlap", refsOverlap ? "yes" : "no");
pairRow.setProperty("pair_date", timestamp);
// Status: AUTO_ACCEPTED (amounts match + refs overlap) or PENDING_REVIEW (amounts differ or no ref overlap)
```

**Test:**
```sql
-- After Phase 2 run: paired records
SELECT c_status, c_source_tp, COUNT(*)
FROM app_fd_trxEnrichment
WHERE c_pair_id IS NOT NULL
GROUP BY c_status, c_source_tp;
-- Expected: status=paired for both bank and secu

-- Verify pair linkages by ticker
SELECT s.id AS secu_id, s.c_resolved_asset_id,
       bp.id AS bank_principal_id, bp.c_description AS bank_desc,
       bp.c_original_amount AS bank_amount,
       bf.id AS bank_fee_id, bf.c_original_amount AS fee_amount
FROM app_fd_trxEnrichment s
JOIN app_fd_trxEnrichment bp ON s.c_pair_id = bp.c_pair_id AND bp.c_source_tp = 'bank'
     AND bp.c_description NOT LIKE '%commission%'
LEFT JOIN app_fd_trxEnrichment bf ON s.c_pair_id = bf.c_pair_id AND bf.c_source_tp = 'bank'
     AND bf.c_description LIKE '%commission%'
WHERE s.c_source_tp = 'secu'
LIMIT 10;

-- Unpaired secu (bank statement not yet imported)
SELECT COUNT(*)
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'secu' AND c_status = 'enriched' AND c_pair_id IS NULL;

-- PAIR entity records with verification
SELECT c_ticker, c_secu_amount, c_bank_amount, c_references_overlap, c_status
FROM app_fd_trx_pair
LIMIT 10;
```

---

## 5. Edge Cases and Considerations

### 5.1 Multiple Secu Transactions for Same Ticker on Same Date

If there are two separate secu consolidated rows for the same ticker on the same date (e.g., ADBE buy AND ADBE sell), the pairing distinguishes by type:
- secu type "ost" ↔ bank "Securities buy (ADBE)"
- secu type "müük" ↔ bank "Securities sell (ADBE)"

### 5.2 Date Tolerance

Secu `value_date` (settlement) should exactly match bank `payment_date`. If not, try ±1 business day tolerance and flag PENDING_REVIEW on the PAIR record.

### 5.3 Amount Validation Tolerance

Allow rounding tolerance of 0.02 (two cents) for amount matching. Larger discrepancies create PENDING_REVIEW pairs.

### 5.4 Bank Rows Without Secu Counterpart

Not all bank "Securities" descriptions correspond to secu transactions. Some may be dividends, interest, or other events that appear only on the bank side. The pairing step only looks for bank rows that match a secu record — unmatched bank rows remain ENRICHED.

---

## 6. DomainConstants.java — New Constants

Add to `DomainConstants.java` (after the existing exception type constants, ~line 113):

```java
// Exception type for secu portfolio allocation (used by RowsEnricher §2.5)
public static final String EXCEPTION_PORTFOLIO_ALLOCATION_REQUIRED = "PORTFOLIO_ALLOCATION_REQUIRED";

// Table for pairing records (used by TransactionPairingStep §4.3)
public static final String TABLE_TRX_PAIR = "trx_pair";
```

---

## 7. Execution Order

1. Apply Phase 1 fixes:
   - §6: DomainConstants.java (new constants — needed by §2.5)
   - §2.1–2.2: CustomerIdentificationStep.java (skip secu + remove dead code)
   - §2.3: CounterpartyDeterminationStep.java (Estonian types)
   - §2.4: EnrichmentDataPersister.java (secu manual review logic)
   - §2.5a: EnrichmentDataPersister.java (one line: store needs_manual_review flag on context)
   - §2.5b: RowsEnricher.java (two methods: post-persistence PORTFOLIO_ALLOCATION_REQUIRED)
2. Run enrichment → test with §3 queries
3. Apply CHANGES-02-statement-importer (GROUP_CONCAT for traceability)
4. Re-import statements (so c_transaction_reference is preserved as GROUP_CONCAT)
5. Apply Phase 2 changes (§4.1–4.3) → run pairing → test with §4.3 queries
