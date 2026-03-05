# rows-enrichment — Change Spec

**Version:** 8.1-SNAPSHOT
**Date:** 4 March 2026
**Dependencies:**
- gam-framework 8.1-SNAPSHOT (no changes)
- statement-importer must apply CHANGES-02 before Phase 2 testing

---

## 1. Purpose

Fix Phase 1 enrichment pipeline to correctly handle bank vs secu distinctions, Estonian transaction types, and customer resolution. Add Phase 2 cross-statement pairing with bank customer identification fixes as prerequisite. Phase 1 (6 changes) is complete and tested. Phase 2 has two sub-phases: 2a fixes bank enrichment (3 changes) and 2b implements pairing (3 changes).

---

## 2. Phase 1 Fixes (6 changes, independently testable)

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

### 2.6 AssetResolutionStep — Auto-Register Unknown Assets from Statement

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/steps/AssetResolutionStep.java`

**Context:** Customers do not trade from this system. Trades happen externally (at LHV bank) and the system processes incoming statements. When a new ticker appears in a secu statement, it should be automatically registered in asset_master rather than failing with UNKNOWN_ASSET.

**Current code (line 100-108):**
```java
if (assetRow == null) {
    LogUtil.warn(CLASS_NAME, "Asset not found for ticker: " + ticker);
    createAssetException(context, formDataDao,
            DomainConstants.EXCEPTION_UNKNOWN_ASSET,
            "No asset found for ticker: " + ticker);
    setUnknownAsset(context);
    return new StepResult(true,
            "Asset not found - exception created, continuing with UNKNOWN");
}
```

**Change to:**
```java
if (assetRow == null) {
    LogUtil.info(CLASS_NAME, "Auto-registering new asset for ticker: " + ticker);
    assetRow = autoRegisterAsset(ticker, context, formDataDao);
    if (assetRow == null) {
        // Auto-registration failed — fall back to UNKNOWN
        LogUtil.error(CLASS_NAME, null, "Failed to auto-register asset for ticker: " + ticker);
        createAssetException(context, formDataDao,
                DomainConstants.EXCEPTION_UNKNOWN_ASSET,
                "No asset found and auto-registration failed for ticker: " + ticker);
        setUnknownAsset(context);
        return new StepResult(true,
                "Asset auto-registration failed - continuing with UNKNOWN");
    }
    // Auto-registration succeeded — continue with the new asset record
    LogUtil.info(CLASS_NAME, "Auto-registered asset: " + assetRow.getId() +
            " for ticker: " + ticker);
}
```

**Add the new method to AssetResolutionStep:**
```java
/**
 * Auto-register a new asset in asset_master from secu statement data.
 * The system does not originate trades — it processes statements from
 * the custodian bank. New tickers appearing in statements are legitimate
 * assets that should be registered automatically.
 *
 * Infers asset category from the description:
 * - Contains "võlakiri" or "bond" → BD/bond
 * - Otherwise → EQ/equity (default)
 *
 * The asset is created with tradingStatus=Active so enrichment
 * can continue without interruption.
 */
private FormRow autoRegisterAsset(String ticker, DataContext context,
                                  FormDataDao formDataDao) {
    try {
        FormRow assetRow = new FormRow();
        String assetId = "AST-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        assetRow.setId(assetId);

        // Core identification
        assetRow.setProperty("ticker", ticker);
        assetRow.setProperty("assetName", extractAssetName(context.getDescription()));
        assetRow.setProperty("shortName", ticker);

        // Infer category from description
        String description = context.getDescription();
        boolean isBond = description != null &&
                (description.toLowerCase().contains("võlakiri") ||
                 description.toLowerCase().contains("bond"));

        assetRow.setProperty("categoryCode", isBond ? "BD" : "EQ");
        assetRow.setProperty("asset_class", isBond ? "bond" : "equity");
        assetRow.setProperty("primaryExchange", isBond ? "OTC" : "");

        // Currency and status
        assetRow.setProperty("tradingCurrency", context.getCurrency());
        assetRow.setProperty("tradingStatus", "Active");

        // Bond-specific: try to extract maturity date from description
        if (isBond) {
            String maturityDate = extractMaturityDate(description);
            if (maturityDate != null) {
                assetRow.setProperty("maturityDate", maturityDate);
            }
            String couponRate = extractCouponRate(description);
            if (couponRate != null) {
                assetRow.setProperty("couponRate", couponRate);
            }
        }

        // Default risk/liquidity for auto-registered
        assetRow.setProperty("riskCategory", isBond ? "RISK_3" : "RISK_3");
        assetRow.setProperty("liquidityProfile", isBond ? "LIQ_3" : "LIQ_2");

        // Save to asset_master
        FormRowSet rowSet = new FormRowSet();
        rowSet.add(assetRow);
        formDataDao.saveOrUpdate(null, DomainConstants.TABLE_ASSET_MASTER, rowSet);

        // Create audit log for the auto-registration
        createAuditLog(context, formDataDao,
                "ASSET_AUTO_REGISTERED",
                String.format("Auto-registered asset: %s (ticker: %s, category: %s, currency: %s)",
                        assetId, ticker, isBond ? "BD" : "EQ", context.getCurrency()));

        return assetRow;

    } catch (Exception e) {
        LogUtil.error(CLASS_NAME, e,
                "Error auto-registering asset for ticker: " + ticker);
        return null;
    }
}

/**
 * Extract asset name from the secu description.
 * Description format: "Bigbank 8% allutatud võlakiri 8% 16.02.2033"
 *                  or "CROWDSTRIKE HOLDINGS INC - A"
 * For bonds: take the issuer name part (before the percentage or "võlakiri"/"bond")
 * For equities: use the full description as name
 */
private String extractAssetName(String description) {
    if (description == null || description.trim().isEmpty()) {
        return "Unknown Asset";
    }
    // Clean up: remove leading/trailing whitespace
    return description.trim();
}

/**
 * Try to extract maturity date from bond description.
 * Looks for date patterns like "16.02.2033" or "30.05.2034" at end of description.
 * Returns in yyyy-MM-dd format or null if not found.
 */
private String extractMaturityDate(String description) {
    if (description == null) return null;
    // Pattern: dd.MM.yyyy at or near end of description
    java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(\\d{2})\\.(\\d{2})\\.(\\d{4})")
            .matcher(description);
    String day = null, month = null, year = null;
    while (m.find()) {
        // Take the last date match (maturity is typically at end)
        day = m.group(1);
        month = m.group(2);
        year = m.group(3);
    }
    if (year != null) {
        return year + "-" + month + "-" + day;
    }
    return null;
}

/**
 * Try to extract coupon rate from bond description.
 * Looks for patterns like "8%" or "9.50%" or "5.5%".
 * Returns the first percentage found as a string, or null.
 */
private String extractCouponRate(String description) {
    if (description == null) return null;
    java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(\\d+\\.?\\d*)%")
            .matcher(description);
    if (m.find()) {
        return m.group(1);
    }
    return null;
}
```

**Why this architecture:**
- **Auto-registration is in the AssetResolutionStep** — the step that discovers the missing asset is the step that creates it. No round-tripping to another class.
- **Falls back to UNKNOWN** only if auto-registration itself fails (DB error, etc.)
- **Category inference** is simple and based on the description language (Estonian "võlakiri" = bond). This covers the LHV statement data patterns.
- **Active immediately** — the asset is usable in the same pipeline run. No manual intervention needed.
- **Bond metadata extraction** is best-effort from the description text. If it can't parse maturity or coupon, those fields are just empty — not a blocker.

**Effect on the 4 manual_review secu rows:**
- BIGB080033A × 2 → auto-registered as BD/bond/EUR, enrichment continues → ENRICHED
- INBB055031A → auto-registered as BD/bond/EUR → ENRICHED
- HLMBK095034FA → auto-registered as BD/bond/EUR → ENRICHED

**Test:**
```sql
-- After re-run: no UNKNOWN_ASSET exceptions for current run
-- (stale ones from previous runs may still exist)
SELECT COUNT(*)
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'secu' AND c_resolved_asset_id = 'UNKNOWN';
-- Expected: 0

-- All 22 secu should be enriched
SELECT c_status, COUNT(*)
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'secu'
GROUP BY c_status;
-- Expected: enriched = 22, manual_review = 0

-- Auto-registered assets in asset_master
SELECT id, c_ticker, c_asset_name, c_category_code, c_trading_currency, c_maturity_date
FROM app_fd_asset_master
WHERE id LIKE 'AST-%';
```

---

## 3. Phase 1 Testing (all 6 changes together)

After applying changes 2.1–2.6, re-run the enrichment pipeline and verify:

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

## 4. Phase 2: Cross-Statement Pairing (6 changes)

**Prerequisites:**
- Phase 1 (§2.1–2.6) applied and tested — all secu rows ENRICHED
- CHANGES-02-statement-importer applied (c_transaction_reference GROUP_CONCAT'd for traceability)

Phase 2 has two sub-phases:
- **§4.0a–4.0c**: Fix bank enrichment so securities-related bank rows reach ENRICHED status (pairing prerequisite)
- **§4.1–4.3**: Cross-statement pairing algorithm

### 4.0a CustomerIdentificationStep — Skip Securities-Related Bank Transactions

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/steps/CustomerIdentificationStep.java`

**Problem:** Of 120 consolidated bank rows, 82 are in MANUAL_REVIEW with MISSING_CUSTOMER. Analysis of the raw bank CSV (161 rows) shows:
- 85 rows have empty `Isikukood või registrikood` (customer_id) AND are securities-related:
  - "Securities buy (TICKER)" — settlement of securities purchase
  - "Securities sell (TICKER)" — settlement of securities sale
  - "Securities commission fee (TICKER)" — brokerage fee
  - "Dividends (TICKER)" — dividend payment
  - "Income tax withheld (TICKER)" — tax on dividend
- These are cash-side settlement records for securities activity. They have no customer_id because the bank doesn't know which end-customer the trade belongs to — that linkage comes from pairing with secu (Phase 2).
- The remaining 36 empty-customer_id rows are operational (interest, admin, etc.) — these legitimately need manual review.

**Current `shouldExecute()` (after §2.1):**
```java
@Override
public boolean shouldExecute(DataContext context) {
    if (!"bank".equals(context.getSourceType())) {
        return false;  // §2.1: skip secu
    }
    return context.getErrorMessage() == null || context.getErrorMessage().isEmpty();
}
```

**Change to:**
```java
@Override
public boolean shouldExecute(DataContext context) {
    if (!"bank".equals(context.getSourceType())) {
        return false;  // §2.1: skip secu
    }
    // §4.0a: Skip securities-related bank transactions.
    // These are cash settlements for securities activity — customer comes via pairing, not identification.
    if (isSecuritiesRelatedBankTransaction(context)) {
        return false;
    }
    return context.getErrorMessage() == null || context.getErrorMessage().isEmpty();
}
```

**Add new method:**
```java
/**
 * Detect bank transactions that are cash-side records of securities activity.
 * These have no customer_id because the custodian bank doesn't track which
 * end-customer the trade belongs to. Customer linkage comes from Phase 2 pairing.
 *
 * Patterns from LHV bank statements:
 * - "Securities buy (TICKER)"
 * - "Securities sell (TICKER)"
 * - "Securities commission fee (TICKER)"
 * - "Dividends (TICKER)"
 * - "Income tax withheld (TICKER) (date)"
 */
private boolean isSecuritiesRelatedBankTransaction(DataContext context) {
    String description = context.getDescription();
    if (description == null) return false;

    String lower = description.toLowerCase();
    return lower.startsWith("securities buy")
            || lower.startsWith("securities sell")
            || lower.startsWith("securities commission")
            || lower.startsWith("dividends")
            || lower.startsWith("income tax withheld");
}
```

**Effect:**
- Securities-related bank rows: skip customer identification → no MISSING_CUSTOMER exception → pipeline continues → can reach ENRICHED → available for pairing
- Non-securities bank rows with customer_id: still go through identification as before
- Non-securities bank rows without customer_id: still get MISSING_CUSTOMER → MANUAL_REVIEW (legitimate)

**Why not check for empty customer_id instead?** Because some non-securities rows also have empty customer_id (account interest, admin fees) and those genuinely need manual investigation. The description pattern is the reliable discriminator.

**Test:**
```sql
-- After re-run: securities-related bank rows should be ENRICHED
SELECT c_status, COUNT(*)
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'bank'
  AND (c_description LIKE 'Securities%' OR c_description LIKE 'Dividends%' OR c_description LIKE 'Income tax%')
GROUP BY c_status;
-- Expected: all enriched

-- Non-securities bank rows: mix of enriched (with customer) and manual_review (missing customer)
SELECT c_status, COUNT(*)
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'bank'
  AND c_description NOT LIKE 'Securities%'
  AND c_description NOT LIKE 'Dividends%'
  AND c_description NOT LIKE 'Income tax%'
GROUP BY c_status;
```

---

### 4.0b CustomerIdentificationStep — Fix Status Check (kycStatus, not status)

**File:** Same file, `performStep()` method (line 126-135)

**Problem:** The code checks `customerRow.getProperty("status")` but the customer form (F10.01) has **no `status` field** — only `kycStatus`. The property returns null, and `!"active".equalsIgnoreCase(null)` = true → **every found customer is flagged INACTIVE_CUSTOMER**. This is why all 38 bank rows that successfully resolve a customer still get the INACTIVE_CUSTOMER exception.

**Current code (line 126-135):**
```java
// Verify customer is active
String status = customerRow.getProperty("status");
if (!FrameworkConstants.STATUS_ACTIVE.equalsIgnoreCase(status)) {
    LogUtil.warn(CLASS_NAME, "Customer " + customerId + " is not active");
    // Create exception for inactive customer
    createCustomerException(context, formDataDao,
            DomainConstants.EXCEPTION_INACTIVE_CUSTOMER,
            String.format("Customer %s is inactive", customerId),
            "high");
}
```

**Change to:**
```java
// Verify customer KYC is completed (F10.01 uses kycStatus field, not status)
String kycStatus = customerRow.getProperty("kycStatus");
if (!"completed".equalsIgnoreCase(kycStatus)) {
    LogUtil.warn(CLASS_NAME, "Customer " + customerId + " KYC not completed: " + kycStatus);
    createCustomerException(context, formDataDao,
            DomainConstants.EXCEPTION_INACTIVE_CUSTOMER,
            String.format("Customer %s KYC status: %s (expected: completed)", customerId, kycStatus),
            "high");
}
```

**Why:** The customer form F10.01 defines `kycStatus` with values: `completed`, `inprogress`, `pending`. There is no separate `status` field. The check should validate KYC completion, not a non-existent field.

**Effect on current data:**
- 50 customers: 42 have kycStatus=completed, 4 inprogress, 4 pending
- Bank rows matching inprogress/pending customers (CUST-000008 Balti turu: 2 rows, CUST-000018 PR&M: 3 rows) → still get INACTIVE_CUSTOMER (correct)
- Bank rows matching completed customers → no longer get false INACTIVE_CUSTOMER

**Test:**
```sql
-- INACTIVE_CUSTOMER exceptions should only exist for customers with non-completed KYC
SELECT e.c_exception_type, e.c_exception_details, COUNT(*)
FROM app_fd_exception_queue e
WHERE e.c_exception_type = 'INACTIVE_CUSTOMER'
GROUP BY e.c_exception_type, e.c_exception_details;
-- Expected: only PR & M INVESTMENTS (16765288) and Balti turu (12011397)
```

---

### 4.0c EnrichmentDataPersister — Skip Customer Check for Securities Bank Rows

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/persister/EnrichmentDataPersister.java`

**Problem:** After §4.0a skips customer identification for securities bank rows, they arrive at the persister with no customer_id set (null, not "UNKNOWN"). The persister's `determineManualReviewStatus()` (§2.4) checks:
```java
if (isBank) {
    String customerId = context.getCustomerId();
    if (FrameworkConstants.ENTITY_UNKNOWN.equals(customerId)) {
        return true;  // → MANUAL_REVIEW
    }
}
```
This check passes (null ≠ "UNKNOWN"), so securities bank rows won't be blocked here. However, we should explicitly handle this case for clarity and future-proofing.

**Current code in `determineManualReviewStatus()` (condition 1, from §2.4):**
```java
// 1. UNKNOWN customer — bank only (secu has no customer by design)
if (isBank) {
    String customerId = context.getCustomerId();
    if (FrameworkConstants.ENTITY_UNKNOWN.equals(customerId)) {
        return true;
    }
}
```

**Change to:**
```java
// 1. UNKNOWN customer — bank only, non-securities only
// Securities bank rows skip customer identification (§4.0a) — customer comes via pairing.
// Secu has no customer by design.
if (isBank) {
    String customerId = context.getCustomerId();
    if (FrameworkConstants.ENTITY_UNKNOWN.equals(customerId)) {
        // Securities-related bank rows: null customer is expected (will pair with secu)
        // Non-securities bank rows: UNKNOWN customer → manual review
        String description = context.getDescription();
        boolean isSecuritiesRelated = description != null && (
                description.toLowerCase().startsWith("securities")
                || description.toLowerCase().startsWith("dividends")
                || description.toLowerCase().startsWith("income tax withheld"));
        if (!isSecuritiesRelated) {
            return true;
        }
    }
}
```

**Why:** Defensive guard. Currently null ≠ "UNKNOWN" so the check already passes, but if a future code change sets customer to "UNKNOWN" for skipped steps, this guard prevents securities bank rows from falling into MANUAL_REVIEW.

**Test:**
```sql
-- Securities bank rows should NOT be in manual_review
SELECT c_status, COUNT(*)
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'bank'
  AND (c_description LIKE 'Securities%' OR c_description LIKE 'Dividends%' OR c_description LIKE 'Income tax%')
GROUP BY c_status;
-- Expected: all enriched
```

---

### 4.0 Understanding the Pairing Problem

Both bank and secu consolidation merge the same underlying execution events into single rows:

- 7 raw ADBE sell executions → 1 consolidated secu row (qty summed, references GROUP_CONCAT'd)
- 7 raw "Securities sell (ADBE)" bank rows → 1 consolidated bank row (amounts summed, references GROUP_CONCAT'd)

The pairing operates at the **consolidated level** — it matches consolidated secu rows with consolidated bank rows. The individual reference numbers are already aggregated on both sides.

**Primary pairing key: amount + date (±1 day tolerance)**

| Matching Dimension | Secu F01.05 Source | Bank F01.05 Source | Example |
|---|---|---|---|
| Amount | `original_amount` (total including fee) | principal `original_amount` + fee `original_amount` on same date | -14219.88 = -14200.00 + -19.88 |
| Date | F01.05 settlement_date (from F01.04.value_date) | F01.05 payment_date | 2024-06-28 ↔ 2024-06-27 |
| Direction | Sign of amount: negative = buy (cash outflow), positive = sell | Same sign convention | buy = negative |

**Why amount-based, not ticker-based:** The secu F01.05 stores `resolved_asset_id` as an internal asset ID (e.g., AST000296), not the ticker symbol. Bank descriptions contain tickers in parentheses (e.g., "Securities buy (MU)"). Rather than mapping between these two representations, amount matching is simpler and proven unique: secu total = bank principal + bank fee is an exact match (0% tolerance) because both sides derive from the same underlying execution. The amounts are precise to cents and no two different securities transactions have the same combined amount on adjacent dates.

**Critical date finding from test data analysis:**

The bank books cash movements **1 business day before** the secu settlement date. This is consistent across all USD equity transactions:

| Secu Ticker | Secu Settle (value_date) | Bank Payment Date | Offset |
|---|---|---|---|
| ADBE (buy) | 2024-06-19 | 2024-06-18 | **-1d** |
| ADBE (sell) | 2024-07-26 | 2024-07-25 | **-1d** |
| CRWD (sell) | 2024-07-19 | 2024-07-18 | **-1d** |
| CRWD (buy) | 2024-07-31 | 2024-07-30 | **-1d** |
| MU (buy) | 2024-06-28 | 2024-06-27 | **-1d** |
| MU (sell) | 2024-07-26 | 2024-07-25 | **-1d** |
| NVDA (sell) | 2024-06-26 | 2024-06-25 | **-1d** |
| EUR bonds/equities | Various | Same date | **0d** |

EUR-denominated securities (bonds BIGB080033A, HLMBK095034FA, INBB055031A; equity LHV1T) settle on the same date (0d offset). USD securities consistently show -1d. NEM is an exception at 0d (possibly T+1 settlement).

The pairing algorithm must use **±1 business day tolerance** on date matching.

**The fee row** is identified by internal_type COMM_FEE on the same bank payment_date. For each bank principal (SEC_BUY/SEC_SELL), the matching fee is the COMM_FEE row on the same date. The combined amount (principal + fee) must exactly equal the secu total.

**Bank securities rows (46 consolidated):**
- 11 Securities buy (principal) — will pair
- 7 Securities sell (principal) — will pair
- 17 Securities commission fee — will pair as fee leg
- 6 Dividends — bank-only, won't pair
- 5 Income tax withheld — bank-only, won't pair

**Secu rows that will NOT pair (3 of 22):**
- NVDA splits ×2 (TRX-93B655, TRX-A0AE55): amount=0, Custodian counterparty, no cash movement on bank side
- BIGB080033A 2nd buy (TRX-66D3B0, settle 2024-08-01): bank payment falls outside statement period
- MSFT (TRX-789138, settle 2024-08-02): bank payment falls outside statement period
- SMCI (TRX-ACF8D1, settle 2024-08-02): bank payment falls outside statement period

**Expected pairing result: 17/22 secu rows paired** (with 17 bank principals + up to 17 bank fees).

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

The ticker is extracted from bank payment_description at runtime for the PAIR audit record only — it is not used for matching (matching uses amount + date).

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

**Entry point:** Invoked from `RowsEnricher.execute()` after `createPostEnrichmentExceptions()` — see §4.3a below.

**Algorithm:**

```
1. Load all F01.05 records where status = ENRICHED AND pair_id IS NULL
2. Separate into secu list and bank list
3. Filter secu list: EXCLUDE split transactions (amount = 0 AND counterparty type = Custodian)
   These are corporate actions with no cash movement — no bank counterpart exists.
4. Build bank combo index by date:
   a. Group bank records by payment_date
   b. For each date, identify principals (internal_type = SEC_BUY or SEC_SELL)
      and fees (internal_type = COMM_FEE)
      SKIP: DIV_INCOME, INCOME_TAX — bank-only, no pairing.
   c. For each principal, try combining with each fee on the same date:
      combo_amount = principal.original_amount + fee.original_amount
      Store as BankCombo(principal, fee, combo_amount, date)
   d. Also store each principal alone (combo_amount = principal.original_amount, fee = null)
      This handles the no-fee case (e.g., LHV1T EUR domestic equity).
   Index key: payment_date → list of BankCombo
5. For each secu record:
   a. Get secu total amount from F01.05 original_amount
   b. Get settlement date from F01.05 settlement_date (originally from F01.04.value_date)
   c. Search bank combos with ±1 day tolerance on date:
      - Collect all BankCombos from dates: settle_date, settle_date - 1 day, settle_date + 1 day
   d. Find the combo where: secu.original_amount == combo.combo_amount (exact, 0% tolerance)
      - Same sign required (both negative for buys, both positive for sells)
      - Currency must match
   e. If exactly one match found:
      - Generate pair_id (UUID)
      - Update secu F01.05: pair_id, fee_trx_id (if fee row exists), has_fee
      - Update bank principal F01.05: pair_id
      - Update bank fee F01.05: pair_id (if fee row exists)
      - Transition all matched: ENRICHED → PAIRED
      - Create PAIR entity record in trx_pair table
      - Cross-verify: source_reference overlap as confirmation (log only)
      - Record date_offset on PAIR record (0 = exact, -1 = bank 1 day before, +1 = bank 1 day after)
   f. If no match: leave unpaired (will retry on next run when more statements arrive)
   g. If multiple matches (ambiguous): log warning and leave unpaired for manual review
```

**Split detection:**
```java
private boolean isSplitTransaction(FormRow secuRecord) {
    // Splits have amount = 0 and counterparty type = Custodian
    String amount = secuRecord.getProperty("original_amount");
    String counterpartyType = secuRecord.getProperty("counterparty_type");
    boolean zeroAmount = "0".equals(amount) || "0.00".equals(amount) || "0.0".equals(amount);
    boolean isCustodian = "Custodian".equalsIgnoreCase(counterpartyType);
    return zeroAmount && isCustodian;
}
```

**Bank record classification by internal_type:**
```java
private boolean isBankPrincipal(FormRow bankRecord) {
    String type = bankRecord.getProperty("internal_type");
    return "SEC_BUY".equals(type) || "SEC_SELL".equals(type);
}

private boolean isBankFee(FormRow bankRecord) {
    return "COMM_FEE".equals(bankRecord.getProperty("internal_type"));
}

private boolean shouldSkipBankRecord(FormRow bankRecord) {
    String type = bankRecord.getProperty("internal_type");
    return "DIV_INCOME".equals(type) || "INCOME_TAX".equals(type);
}
```

**Amount matching — exact (0% tolerance):**
```java
private boolean amountsMatch(double secuTotal, double comboAmount) {
    // Exact match: secu total = bank principal + bank fee
    // Both derive from the same execution, so they must be identical to the cent.
    return Math.abs(secuTotal - comboAmount) < 0.01; // rounding guard only
}
```

**Ticker extraction from bank description (retained for PAIR record audit trail):**
```java
String extractTickerFromDescription(String description) {
    // Used to populate ticker on PAIR record for human readability — NOT for matching.
    if (description == null) return null;
    int open = description.indexOf('(');
    int close = description.indexOf(')');
    if (open >= 0 && close > open) {
        return description.substring(open + 1, close).trim();
    }
    return null;
}
```

**Note:** Uses `indexOf` (first match), not `lastIndexOf`, because "Income tax withheld (NVDA) (01.07.2024)" has two parenthesized groups — the ticker is always in the first one.

**Source reference cross-verification (audit, not matching):**
```java
// After amount+date matching, verify reference overlap as confirmation
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
pairRow.setProperty("has_fee", feeRecord != null ? "yes" : "no");
pairRow.setProperty("ticker", extractTickerFromDescription(principalRecord.getProperty("description")));  // audit only, not used for matching
pairRow.setProperty("secu_settle_date", secuSettleDate);
pairRow.setProperty("bank_pay_date", bankPayDate);
pairRow.setProperty("date_offset", String.valueOf(dateOffset));  // -1, 0, or +1
pairRow.setProperty("secu_amount", secuRecord.getProperty("original_amount"));
pairRow.setProperty("bank_amount", principalRecord.getProperty("original_amount"));
pairRow.setProperty("fee_amount", feeRecord != null ? feeRecord.getProperty("original_amount") : "");
pairRow.setProperty("currency", secuRecord.getProperty("original_currency"));
pairRow.setProperty("references_overlap", refsOverlap ? "yes" : "no");
pairRow.setProperty("pair_date", timestamp);
// Status: AUTO_ACCEPTED (exact amount match) — all amount-matched pairs are auto-accepted
```

**Test:**
```sql
-- 1. Overall status after pairing
SELECT c_source_tp, c_status, COUNT(*)
FROM app_fd_trxEnrichment
GROUP BY c_source_tp, c_status
ORDER BY c_source_tp, c_status;
-- Expected:
--   bank, enriched: ~49 (11 dividend/tax + 1 LHV1T no-fee + ~33 non-secu with customer + ~4 non-secu inactive)
--   bank, manual_review: 36 (non-securities, missing customer)
--   bank, paired: ~35 (17 principals + ~16 fees + LHV1T principal)
--   secu, enriched: 5 (2 NVDA splits + MSFT + SMCI + BIGB080033A 2nd buy)
--   secu, paired: 17

-- 2. Verify pair linkages by ticker
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
ORDER BY s.c_resolved_asset_id;
-- Expected: 17 rows, each with secu + bank principal + optional bank fee

-- 3. Unpaired secu (splits + outside statement period)
SELECT id, c_description, c_original_amount, c_status
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'secu' AND c_status = 'enriched' AND (c_pair_id IS NULL OR c_pair_id = '');
-- Expected: 5 rows (2 NVDA splits, MSFT, SMCI, BIGB080033A 2nd)

-- 4. PAIR entity records with date offset verification
SELECT c_ticker, c_secu_settle_date, c_bank_pay_date, c_date_offset,
       c_secu_amount, c_bank_amount, c_has_fee, c_references_overlap
FROM app_fd_trx_pair
ORDER BY c_ticker, c_secu_settle_date;
-- Expected: 17 pairs, date_offset mostly -1 for USD, 0 for EUR
```

---

### 4.3a RowsEnricher — Invoke Pairing Step

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/lib/RowsEnricher.java`

TransactionPairingStep must be explicitly invoked from the enrichment pipeline. Add after `createPostEnrichmentExceptions()` (after line 115):

```java
// Phase 2b: Cross-statement pairing (amount + date matching)
TransactionPairingStep pairingStep = new TransactionPairingStep();
pairingStep.setStatusManager(statusManager);
int pairsCreated = pairingStep.executePairing(formDataDao);
LogUtil.info(CLASS_NAME, "Cross-statement pairing: " + pairsCreated + " pairs created");
```

**Why this is separate from §4.3:** The TransactionPairingStep class was created but never wired into the pipeline. Without this invocation, `executePairing()` is never called and zero pairs are created.

---

### 4.3b buildBankComboIndex — Fall Back to settlement_date

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/steps/TransactionPairingStep.java`

In `buildBankComboIndex()`, 7 of 35 bank securities rows (EUR instruments: LHV1T, BIGB080033A, INBB055031A, HLMBK095034FA and their fees) have null `transaction_date`. The `settlement_date` is always populated.

Add fallback at the point where the date key is read:

```java
String date = row.getProperty("transaction_date");
if (date == null || date.isEmpty()) {
    date = row.getProperty("settlement_date");
}
if (date == null || date.isEmpty()) continue;  // skip rows with no date at all
```

Without this, the EUR bank securities rows are silently dropped from the index and their secu counterparts remain unmatched.

---

### 4.3c transitionToPaired — Use Explicit Table Name

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/steps/TransactionPairingStep.java`

`StatusManager.transition()` has two overloads:
- 4-argument: uses `EntityType.getTableName()` → resolves to `"trx_enrichment"` → Joget creates `app_fd_trx_enrichment` → **table does not exist**
- 5-argument: accepts explicit table name → pass `DomainConstants.TABLE_TRX_ENRICHMENT` (`"trxEnrichment"`) → correct

In `transitionToPaired()`, use the 5-argument overload:

```java
statusManager.transition(formDataDao, DomainConstants.TABLE_TRX_ENRICHMENT,
        EntityType.ENRICHMENT, recordId,
        Status.PAIRED, "rows-enrichment", "Paired with cross-statement match");
```

**Root cause:** `EntityType.ENRICHMENT("trx_enrichment")` used snake_case, but the Joget form `F01.05-trxEnrichment` was created with camelCase. The mismatch was known (StatusManager Javadoc at line 204 documents it; the 5-arg overload exists as a workaround). `EnrichmentDataPersister.transitionEnrichment()` already uses the 5-arg form correctly.

**Note:** The root cause was separately fixed by changing `EntityType.ENRICHMENT("trx_enrichment")` → `ENRICHMENT("trxEnrichment")` in gam-framework (see BUG-REPORT-pairing-table-name.md). After that fix, the 4-arg overload also works — but the explicit 5-arg call is kept as defensive practice.

---

## 5. Edge Cases and Considerations

### 5.1 Multiple Secu Transactions for Same Ticker on Same Date

If there are two separate secu consolidated rows for the same ticker on the same date (e.g., ADBE buy AND ADBE sell), the amount-based matching naturally distinguishes them: the buy has a negative amount and the sell has a positive amount, so they match different bank combos. Sign matching (same-sign required) prevents cross-matching.

### 5.2 Date Tolerance — Bank Books 1 Day Before Secu Settlement

Analysis of test data (142 enriched rows) confirms a systematic pattern:

**USD equities:** bank payment_date = secu settlement_date **- 1 business day**. This is consistent across all 14 USD equity pairs (ADBE, CRWD, FLNC, HYLN, ILMN, META, MU, NVDA). The bank processes the cash settlement on T+1, while the securities settle on T+2.

**EUR securities (bonds + LHV1T):** bank payment_date = secu settlement_date (**same day**, 0d offset). EUR domestic instruments settle differently.

**NEM:** 0d offset (exception — possibly T+1 settlement for commodities).

The algorithm uses ±1 day tolerance:
- Try exact date match first → `date_offset = 0` on PAIR record
- Try settle_date - 1 → `date_offset = -1` (most USD equities will match here)
- Try settle_date + 1 → `date_offset = +1` (unlikely but defensive)

All matches within tolerance create AUTO_ACCEPTED pairs. No PENDING_REVIEW needed for date offset within ±1 day since this is expected behavior, not an anomaly.

### 5.3 Amount Matching — Exact (0% tolerance)

The pairing uses **exact amount matching** (0% tolerance, with a 0.01 rounding guard only). This works because secu total = bank principal + bank fee — both derive from the same underlying execution. Verified on all 17 matchable pairs in test data: every match is exact to the cent.

The amounts are sign-consistent: secu buy (negative = cash outflow) matches bank buy combo (negative principal + negative fee = negative combo). Secu sell (positive = cash inflow) matches bank sell combo (positive principal + negative fee = net positive combo).

**Test data validation (all 17 pairs, 0 ambiguous, 0 false matches):**

| Secu | Secu Amount | Bank Principal | Bank Fee | Combined | Match |
|---|---|---|---|---|---|
| MU buy | -14,219.88 | -14,200.00 | -19.88 | -14,219.88 | exact |
| ADBE buy | -10,182.03 | -10,167.80 | -14.23 | -10,182.03 | exact |
| ADBE sell | 21,395.04 | 21,425.04 | -30.00 | 21,395.04 | exact |
| LHV1T buy | -8,050.00 | -8,050.00 | (none) | -8,050.00 | exact |
| ... | ... | ... | ... | ... | exact |

### 5.4 Bank Rows Without Secu Counterpart

Not all securities-related bank rows have a secu counterpart to pair with:
- **Dividends (TICKER)** — 6 rows: cash dividend payment, bank-only (no secu row exists)
- **Income tax withheld (TICKER) (date)** — 5 rows: withholding tax on dividends, bank-only
- These remain ENRICHED (not PAIRED) after the pairing step. They skip customer identification (§4.0a) because they are securities-related, but they don't participate in pairing since there's no matching secu transaction.

The pairing step only indexes bank rows whose description matches "Securities buy|sell (TICKER)" or "Securities commission fee (TICKER)" — i.e., trade settlements and their fees.

### 5.5 Securities Splits — No Cash Movement

Stock splits (e.g., NVDA 10:1 on 2024-06-10) appear as two secu rows:
- split+ (TRX-93B655): quantity +200, amount 0, Custodian counterparty
- split- (TRX-A0AE55): quantity -20, amount 0, Custodian counterparty

These have **no cash movement** on the bank side — no bank counterpart exists. The pairing step detects splits by checking `amount = 0 AND counterparty_type = Custodian` and excludes them from pairing. They remain ENRICHED (not PAIRED).

### 5.6 Secu Rows Outside Statement Period

Secu transactions whose settlement date falls outside the bank statement period have no bank counterpart in the current data:
- MSFT buy: settle 2024-08-02 — August settlement, June-July bank statement
- SMCI buy: settle 2024-08-02 — same
- BIGB080033A 2nd buy: settle 2024-08-01 — same

These remain ENRICHED and will pair when the August bank statement is imported.

### 5.7 LHV1T — No Commission Fee

LHV1T (EUR domestic equity) has a bank principal row but no commission fee row. This is normal for EUR domestic equities traded on Tallinn exchange. The pair is created without a fee leg: `has_fee = false`, `fee_trx_id = null`.

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

1. **Phase 1** (DONE — all 6 changes applied and tested):
   - §6: DomainConstants.java (new constants)
   - §2.1–2.2: CustomerIdentificationStep.java (skip secu + remove dead code)
   - §2.3: CounterpartyDeterminationStep.java (Estonian types)
   - §2.4: EnrichmentDataPersister.java (secu manual review logic)
   - §2.5a: EnrichmentDataPersister.java (store needs_manual_review flag on context)
   - §2.5b: RowsEnricher.java (post-persistence PORTFOLIO_ALLOCATION_REQUIRED)
   - §2.6: AssetResolutionStep.java (auto-register unknown assets from statement)
   - ✅ Result: 22/22 secu ENRICHED, 38 bank ENRICHED (false INACTIVE), 82 bank MANUAL_REVIEW
2. **Phase 2a — Bank enrichment fixes** (DONE — all 3 changes applied and tested):
   - §4.0a: CustomerIdentificationStep.java (skip securities-related bank transactions)
   - §4.0b: CustomerIdentificationStep.java (fix kycStatus check)
   - §4.0c: EnrichmentDataPersister.java (defensive guard for securities bank rows)
   - ✅ Result: 84 bank ENRICHED + 36 bank MANUAL_REVIEW, all securities bank rows ENRICHED
3. **Phase 2b — Pairing** (DONE — all changes applied and tested):
   - §4.1: F01.05 form (add source_reference field)
   - §4.2: EnrichmentDataPersister.java (populate source_reference)
   - §4.3: TransactionPairingStep.java (new class — pairing algorithm)
   - §4.3a: RowsEnricher.java (invoke pairing step after enrichment)
   - §4.3b: TransactionPairingStep.java (settlement_date fallback for EUR bank rows)
   - §4.3c: TransactionPairingStep.java (5-arg StatusManager.transition with explicit table name)
   - EntityType.ENRICHMENT root cause fix in gam-framework ("trx_enrichment" → "trxEnrichment")
   - ✅ Result: 17/22 secu PAIRED, 33/35 bank securities PAIRED, 5 secu ENRICHED (expected), 17 pair records created

### Expected Final State (after all phases)

```
Secu (22 rows):
  - 17 PAIRED (matched with bank principal + fee)
  - 2 ENRICHED (NVDA splits — no bank counterpart, amount=0)
  - 3 ENRICHED (MSFT, SMCI, BIGB080033A 2nd buy — settle in August, outside statement period)
  - All 22 have PORTFOLIO_ALLOCATION_REQUIRED exception

Bank (120 rows):
  - Securities-related:
    - ~35 PAIRED (17 principals + ~16 fees that matched with secu)
    - 11 ENRICHED (6 dividends + 5 income tax — bank-only, no pairing)
    - 1 ENRICHED (LHV1T has no fee row)
  - Non-securities:
    - ~33 ENRICHED (customer resolved, KYC completed)
    - ~5 ENRICHED + INACTIVE_CUSTOMER (customer resolved but KYC inprogress/pending)
    - ~36 MANUAL_REVIEW + MISSING_CUSTOMER (no customer_id, non-securities)

Pair records (trx_pair table):
  - 17 pairs, each linking: 1 secu + 1 bank principal + 0-1 bank fee
  - date_offset: mostly -1 (USD equities), some 0 (EUR securities)
  - All AUTO_ACCEPTED (amounts should match within tolerance)
```
