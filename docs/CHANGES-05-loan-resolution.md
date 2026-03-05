# rows-enrichment — Loan Contract Resolution Change Spec

**Version:** 8.1-SNAPSHOT
**Date:** 5 March 2026
**Dependencies:**
- gam-framework 8.1-SNAPSHOT (no changes)
- rows-enrichment 8.1-SNAPSHOT (CHANGES-03 must be applied first)
- F02.04 loan_master form must be deployed to Joget before running enrichment

---

## 1. Purpose

Link loan-related bank transactions to their source loan contracts. Currently 53 of 120 bank transactions are loan-related (interest income/expense, principal repayments, disbursements, management fees) but have no linkage to a loan contract entity. This spec adds:

1. **F02.04 Loan Contract Master form** — master data for loans (fund as lender or borrower)
2. **LoanResolutionStep** — pipeline step that extracts contract references from bank statement descriptions and resolves them against loan_master
3. **F01.05 enrichment form changes** — two new fields (loan_id, loan_direction)
4. **EnrichmentDataPersister changes** — persist loan fields

The design follows the **Asset Registration Pattern** (CHANGES-03 §2.6) adapted for bidirectional loan flows and inconsistent contract number formats.

**Key constraint:** Only information present in bank and secu statements is used for resolution. External actor identification uses registration numbers, account numbers, and BIC codes — never internal counterparty_id.

---

## 2. F02.04 Loan Contract Master

### 2.1 Form Definition

**Form ID:** `loanMasterForm`
**Form name:** `02.04 - Loan Contract`
**Table name:** `loan_master`
**ID format:** `LN-` + 8-char UUID (e.g., `LN-A3F7B2C1`), same pattern as AssetResolutionStep

**Fields:**

| Field | ID | Type | Required | Description |
|---|---|---|---|---|
| Loan ID | `id` | Text (auto) | Yes | Primary key, format LN-XXXXXXXX |
| Contract Number | `contract_number` | TextField | Yes | Original contract identifier from statements (e.g., "315001", "1314/2020", "1315-2020", "#211") |
| Contract Description | `contract_description` | TextArea | No | Free-text name or notes |
| Principal Amount | `principal_amount` | TextField (numeric) | No | Original contract amount. May be unknown for auto-registered loans. |
| Currency | `contract_currency` | TextField | Yes | ISO 4217 code (EUR for current data) |
| Direction | `direction` | SelectBox | Yes | `LENDER` (fund lends out, receives interest) or `BORROWER` (fund borrows, pays interest) |
| Counterparty Account | `counterparty_account` | TextField | No | IBAN or account number of the other party (from statement other_side_account). Primary lookup key for Tier 2 resolution. |
| Counterparty Name | `counterparty_name` | TextField | No | Name of the other party (from statement other_side_name). For display only. |
| Counterparty BIC | `counterparty_bic` | TextField | No | BIC of the other party's bank (from statement other_side_bic). |
| Counterparty Reg Number | `counterparty_reg_number` | TextField | No | Company registration number if known. |
| Start Date | `start_date` | DateField | No | Contract effective date. May be unknown for auto-registered. |
| Maturity Date | `maturity_date` | DateField | No | Final repayment date. Blank for open-ended/renewable loans. |
| Interest Rate | `interest_rate` | TextField (numeric) | No | Annual % rate if known. |
| Status | `status` | SelectBox | Yes | `DRAFT`, `ACTIVE`, `MATURED`, `CLOSED` |
| Source | `source` | SelectBox | Yes | `MANUAL` or `AUTO_REGISTERED` |
| Registration Note | `registration_note` | TextArea | No | For auto-registered: extraction details and confidence. |

### 2.2 Status Lifecycle

```
DRAFT → ACTIVE → MATURED or CLOSED
```

- **DRAFT:** Auto-registered loans; pending manual review and activation
- **ACTIVE:** Confirmed; LoanResolutionStep can link transactions to it
- **MATURED:** Final payment received
- **CLOSED:** Archived

Transitions are **manual only** (operator changes status via form UI). No StatusManager involvement — loan_master is master data like asset_master, not a status-tracked entity. No EntityType addition needed in gam-framework.

**Important:** LoanResolutionStep only matches against ACTIVE loans (Tier 1 and Tier 2). DRAFT loans are not used for matching — they must be activated first by an operator.

### 2.3 Indexes

```sql
CREATE INDEX idx_loan_contract_number ON app_fd_loan_master(c_contract_number);
CREATE INDEX idx_loan_counterparty_account ON app_fd_loan_master(c_counterparty_account);
CREATE INDEX idx_loan_status ON app_fd_loan_master(c_status);
```

---

## 3. LoanResolutionStep

### 3.1 Pipeline Position

After F14RuleMappingStep (which sets internal_type), before FxConversionStep:

```
Step 1: CurrencyValidationStep
Step 2: CustomerIdentificationStep (bank only)
Step 3: CounterpartyDeterminationStep
Step 4: AssetResolutionStep (secu only)
Step 5: F14RuleMappingStep (sets internal_type)
Step 5.5: LoanResolutionStep (bank only, self-filtering)  ← NEW
Step 6: FxConversionStep
→ EnrichmentDataPersister
→ Post-enrichment: PortfolioAllocationExceptions, TransactionPairingStep
```

**Why after F14?** The step self-filters by trying to extract contract info from the description. It doesn't depend on internal_type for activation. However, running after F14 means all classification is done and the step can use internal_type as an additional signal for direction inference.

### 3.2 Activation: shouldExecute()

```java
@Override
public boolean shouldExecute(DataContext context) {
    // Only bank transactions. Secu transactions don't have loan contracts.
    return "bank".equals(context.getSourceType())
        && context.getErrorMessage() == null;
}
```

The step runs for ALL bank transactions but exits quickly (Tier 1 regex returns null) for non-loan transactions. No internal_type pre-filtering needed — the contract number extraction is the natural filter.

### 3.3 Resolution Algorithm

```
1. Extract contract number from context.getPaymentDescription()
   (regex patterns for Estonian loan references — see §3.4)

2. IF contract number extracted:
   a. Lookup loan_master WHERE contract_number = extracted AND status = 'ACTIVE'
   b. IF found → RESOLVED (Tier 1). Store loan_id, loan_direction. Done.
   c. IF not found → continue to Tier 2.

3. Lookup by other_side_account:
   a. Get account = context.getOtherSideAccount()
   b. IF account is not null/empty:
      Query loan_master WHERE counterparty_account = account AND status = 'ACTIVE'
   c. IF found → RESOLVED (Tier 2). Store loan_id, loan_direction. Done.
   d. IF not found → continue to Tier 3.

4. IF contract number was extracted in step 1 (but no match in Tiers 1-2):
   → Auto-register a DRAFT loan (Tier 3). Store loan_id, loan_direction. Done.

5. IF no contract number was extracted AND no account match:
   → SKIP. Leave loan_id null. Transaction enriches normally without loan linkage.
```

**Key principle:** Only auto-register when we have a contract number from the description. If we can't extract any contract reference, we don't create a DRAFT loan — the transaction just proceeds without loan linkage.

### 3.4 Contract Number Extraction

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/steps/LoanResolutionStep.java`

Regex patterns for Estonian bank statement descriptions. Patterns are ordered most-specific-first.

```java
private static final Pattern[] CONTRACT_PATTERNS = {
    // "Laenulepingu 1315-2020 osamakse" — hyphen format with prefix
    Pattern.compile("(?i)(?:laenulepingu?|leping)\\s+([\\d]+[-][\\d]{2,4})"),

    // "laenuleping 1314/2020" — slash format with prefix
    Pattern.compile("(?i)(?:laenulepingu?|leping)\\s+([\\d]+/[\\d]{2,4})"),

    // "Leping 315001" or "leping 110401" — pure numeric with prefix
    Pattern.compile("(?i)(?:laenulepingu?|leping)\\s+([\\d]{4,})"),

    // "avaldusele #211" or "avalduse alusel #228" — application reference
    Pattern.compile("(?i)avaldus\\S*\\s+#(\\d+)"),

    // "laen 112701" — loan + number
    Pattern.compile("(?i)laen\\s+([\\d]{4,})")
};

/**
 * Try all patterns against the description. Return the first match.
 * Returns null if no contract number found.
 */
String extractContractNumber(String description) {
    if (description == null || description.isEmpty()) return null;

    for (Pattern p : CONTRACT_PATTERNS) {
        Matcher m = p.matcher(description);
        if (m.find()) {
            return m.group(1).trim();
        }
    }
    return null;
}
```

**Note on application references:** Patterns like "#211" are stored as-is (without the `#` prefix in the captured group). The loan_master contract_number should store "211" for these.

### 3.5 Loan Lookup by Contract Number (Tier 1)

```java
private FormRow lookupByContractNumber(String contractNumber, FormDataDao formDataDao) {
    if (contractNumber == null) return null;

    String condition = "WHERE c_contract_number = ? AND c_status = 'ACTIVE'";
    FormRowSet rows = formDataDao.find(null,
            DomainConstants.TABLE_LOAN_MASTER,
            condition,
            new String[] { contractNumber },
            null, false, 0, 1);

    return (rows != null && !rows.isEmpty()) ? rows.get(0) : null;
}
```

### 3.6 Loan Lookup by Account Number (Tier 2)

```java
private FormRow lookupByAccount(String otherSideAccount, FormDataDao formDataDao) {
    if (otherSideAccount == null || otherSideAccount.isEmpty()) return null;

    String condition = "WHERE c_counterparty_account = ? AND c_status = 'ACTIVE'";
    FormRowSet rows = formDataDao.find(null,
            DomainConstants.TABLE_LOAN_MASTER,
            condition,
            new String[] { otherSideAccount },
            null, false, 0, 1);

    return (rows != null && !rows.isEmpty()) ? rows.get(0) : null;
}
```

### 3.7 Auto-Registration (Tier 3)

```java
private FormRow autoRegisterLoan(String contractNumber, DataContext context, FormDataDao formDataDao) {
    FormRow loanRow = new FormRow();

    String loanId = "LN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    loanRow.setId(loanId);

    // Contract number from description extraction
    loanRow.setProperty("contract_number", contractNumber);

    // Counterparty info from statement fields
    loanRow.setProperty("counterparty_account", nvl(context.getOtherSideAccount()));
    loanRow.setProperty("counterparty_name", nvl(context.getOtherSideName()));
    loanRow.setProperty("counterparty_bic", nvl(context.getOtherSideBic()));

    // Best-effort fields
    loanRow.setProperty("contract_currency",
            context.getCurrency() != null ? context.getCurrency() : "EUR");
    loanRow.setProperty("direction", inferDirection(context));

    // Status and source
    loanRow.setProperty("status", "DRAFT");
    loanRow.setProperty("source", "AUTO_REGISTERED");
    loanRow.setProperty("registration_note",
            String.format("Auto-registered from bank txn %s. " +
                    "Contract number '%s' extracted from description: '%s'. " +
                    "Other side: %s (%s).",
                    context.getTransactionId(),
                    contractNumber,
                    truncate(context.getPaymentDescription(), 100),
                    nvl(context.getOtherSideName()),
                    nvl(context.getOtherSideAccount())));

    FormRowSet rowSet = new FormRowSet();
    rowSet.add(loanRow);
    formDataDao.saveOrUpdate(null, DomainConstants.TABLE_LOAN_MASTER, rowSet);

    LogUtil.info(CLASS_NAME, String.format(
            "Auto-registered loan %s: contract='%s', counterparty='%s', direction=%s, txn=%s",
            loanId, contractNumber,
            nvl(context.getOtherSideName()),
            inferDirection(context),
            context.getTransactionId()));

    return loanRow;
}
```

### 3.8 Direction Inference

```java
/**
 * Infer loan direction from the transaction's internal_type and amount sign.
 *
 * LENDER = fund has lent money out (receives interest income, principal repayments)
 * BORROWER = fund has borrowed money (pays interest expense, makes repayments)
 */
private String inferDirection(DataContext context) {
    Map<String, Object> data = context.getAdditionalData();
    String internalType = data != null ? (String) data.get("internal_type") : null;

    if (internalType != null) {
        switch (internalType) {
            case "INT_INCOME":
            case "LOAN_PAYMENT":  // received loan repayment
                return "LENDER";
            case "INT_EXPENSE":
            case "MGMT_FEE":
                return "BORROWER";
            default:
                break;
        }
    }

    // Fallback: positive amount = money coming in = likely LENDER (receiving repayment)
    //           negative amount = money going out = likely BORROWER (making payment)
    //                             OR LENDER (disbursing loan)
    // Ambiguous for CASH_IN/CASH_OUT — default to null, operator resolves manually.
    return null;
}
```

### 3.9 Full Execute Method

```java
@Override
protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
    String description = context.getPaymentDescription();

    // Tier 1: Extract contract number from description
    String contractNumber = extractContractNumber(description);

    FormRow loanRow = null;

    if (contractNumber != null) {
        loanRow = lookupByContractNumber(contractNumber, formDataDao);
        if (loanRow != null) {
            setResolved(context, loanRow, "CONTRACT_NUMBER");
            return new StepResult(true,
                    "Loan resolved by contract number: " + contractNumber);
        }
    }

    // Tier 2: Lookup by other_side_account
    String otherSideAccount = context.getOtherSideAccount();
    if (otherSideAccount != null && !otherSideAccount.isEmpty()) {
        loanRow = lookupByAccount(otherSideAccount, formDataDao);
        if (loanRow != null) {
            setResolved(context, loanRow, "ACCOUNT_NUMBER");
            return new StepResult(true,
                    "Loan resolved by account: " + otherSideAccount);
        }
    }

    // Tier 3: Auto-register if we extracted a contract number
    if (contractNumber != null) {
        try {
            loanRow = autoRegisterLoan(contractNumber, context, formDataDao);
            setResolved(context, loanRow, "AUTO_REGISTERED");
            return new StepResult(true,
                    "Loan auto-registered: " + loanRow.getId());
        } catch (Exception e) {
            LogUtil.warn(CLASS_NAME,
                    "Auto-registration failed for txn " + context.getTransactionId()
                    + ": " + e.getMessage());
        }
    }

    // No resolution — not a loan transaction or insufficient data
    // This is NOT an error. Leave loan_id null, enrichment continues.
    return new StepResult(true, "No loan contract resolved (not loan-related or insufficient data)");
}

private void setResolved(DataContext context, FormRow loanRow, String method) {
    context.addToAdditionalData("loan_id", loanRow.getId());
    context.addToAdditionalData("loan_direction", loanRow.getProperty("direction"));
    context.addToAdditionalData("loan_resolution_method", method);

    LogUtil.info(CLASS_NAME, String.format(
            "Loan resolved: txn=%s → loan=%s (method=%s, direction=%s)",
            context.getTransactionId(),
            loanRow.getId(),
            method,
            loanRow.getProperty("direction")));
}
```

---

## 4. EnrichmentDataPersister Changes

### 4.1 Add Loan Fields to createEnrichedRecord()

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/persister/EnrichmentDataPersister.java`

In `createEnrichedRecord()`, add after the existing Fee & Pairing section:

```java
// Loan Resolution (populated by LoanResolutionStep for bank transactions)
String loanId = getStringValue(data.get("loan_id"));
String loanDirection = getStringValue(data.get("loan_direction"));
if (loanId != null && !loanId.isEmpty()) {
    setPropertySafe(row, "loan_id", loanId);
    setPropertySafe(row, "loan_direction", loanDirection);
}
```

---

## 5. F01.05 Form Changes

### 5.1 New Fields: Loan Resolution Section

**File:** `F01.05-trxEnrichment.json`

Add two HiddenFields to the Fee & Pairing section (or create a new Loan Resolution section after it):

```json
{
  "className": "org.joget.apps.form.lib.HiddenField",
  "properties": {
    "id": "loan_id",
    "label": "Loan Contract ID"
  }
},
{
  "className": "org.joget.apps.form.lib.HiddenField",
  "properties": {
    "id": "loan_direction",
    "label": "Loan Direction"
  }
}
```

---

## 6. DomainConstants

### 6.1 Table Constant

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/constants/DomainConstants.java`

```java
// Loan Master Data
public static final String TABLE_LOAN_MASTER = "loan_master";
```

---

## 7. RowsEnricher Integration

### 7.1 Add LoanResolutionStep to Pipeline

**File:** `src/main/java/com/fiscaladmin/gam/enrichrows/lib/RowsEnricher.java`

LoanResolutionStep must be added to the step chain after F14RuleMappingStep. This depends on how the pipeline is currently configured. Two options:

**Option A:** If steps are in an explicit list/array, insert LoanResolutionStep after F14RuleMappingStep:

```java
steps.add(new CurrencyValidationStep());
steps.add(new CustomerIdentificationStep());
steps.add(new CounterpartyDeterminationStep());
steps.add(new AssetResolutionStep());
steps.add(new F14RuleMappingStep());
steps.add(new LoanResolutionStep());     // ← NEW
steps.add(new FxConversionStep());
```

**Option B:** If steps are invoked individually, add after the F14 call:

```java
// After F14RuleMappingStep execution:
LoanResolutionStep loanStep = new LoanResolutionStep();
loanStep.execute(context, formDataDao);
```

---

## 8. Testing

### 8.1 Unit Tests for Contract Number Extraction

```java
@Test
void testExtract_numericWithLeping() {
    assertEquals("315001",
        step.extractContractNumber("Leping 315001 laenu tagasimakse"));
}

@Test
void testExtract_slashFormat() {
    assertEquals("1314/2020",
        step.extractContractNumber("Intress vastavalt laenuleping 1314/2020"));
}

@Test
void testExtract_hyphenFormat() {
    assertEquals("1315-2020",
        step.extractContractNumber("Laenulepingu 1315-2020 osamakse nr 18"));
}

@Test
void testExtract_applicationRef() {
    assertEquals("211",
        step.extractContractNumber("Tagastus avaldusele #211 põhiosa"));
}

@Test
void testExtract_laenPrefix() {
    assertEquals("112701",
        step.extractContractNumber("laen 112701 intress per 01.06-30.06.2024"));
}

@Test
void testExtract_noMatch() {
    assertNull(step.extractContractNumber("Securities buy 100 NVDA"));
}

@Test
void testExtract_null() {
    assertNull(step.extractContractNumber(null));
}

@Test
void testExtract_accountInterest() {
    // Bank account interest — no contract pattern
    assertNull(step.extractContractNumber(
        "Account interest 01.05.2024-31.05.2024 (rate: 1.00%)"));
}
```

### 8.2 Unit Tests for Resolution Tiers

```java
@Test
void testTier1_resolvedByContractNumber() {
    // Setup: loan_master has ACTIVE loan with contract_number = "315001"
    // Input: description = "Leping 315001 laenu tagasimakse"
    // Expected: loan_id = LN of that loan, method = CONTRACT_NUMBER
}

@Test
void testTier2_resolvedByAccount() {
    // Setup: loan_master has ACTIVE loan with counterparty_account = "EE123456"
    // Input: description has no contract pattern, otherSideAccount = "EE123456"
    // Expected: loan_id = LN of that loan, method = ACCOUNT_NUMBER
}

@Test
void testTier3_autoRegistered() {
    // Setup: loan_master is empty
    // Input: description = "Leping 999999 test", otherSideAccount = "EE999"
    // Expected: new DRAFT loan created, loan_id set, method = AUTO_REGISTERED
}

@Test
void testNoResolution_noContractNoAccount() {
    // Input: description = "Generic transfer", otherSideAccount = null
    // Expected: loan_id = null, no auto-registration, step succeeds
}

@Test
void testSkipsSecuTransactions() {
    // Input: sourceType = "secu"
    // Expected: shouldExecute returns false
}

@Test
void testDraftLoanNotUsedForMatching() {
    // Setup: loan_master has DRAFT loan with contract_number = "315001"
    // Input: description = "Leping 315001 tagasimakse"
    // Expected: Tier 1 finds nothing (DRAFT excluded), proceeds to Tier 3 auto-register
}
```

### 8.3 Direction Inference Tests

```java
@Test
void testDirection_intIncome() {
    // internal_type = INT_INCOME → LENDER
    assertEquals("LENDER", step.inferDirection(contextWithType("INT_INCOME")));
}

@Test
void testDirection_intExpense() {
    // internal_type = INT_EXPENSE → BORROWER
    assertEquals("BORROWER", step.inferDirection(contextWithType("INT_EXPENSE")));
}

@Test
void testDirection_loanPayment() {
    // internal_type = LOAN_PAYMENT → LENDER (fund receives repayment)
    assertEquals("LENDER", step.inferDirection(contextWithType("LOAN_PAYMENT")));
}

@Test
void testDirection_cashIn() {
    // internal_type = CASH_IN → null (ambiguous)
    assertNull(step.inferDirection(contextWithType("CASH_IN")));
}
```

### 8.4 Integration Test Queries

```sql
-- 1. Loan resolution coverage
SELECT
  CASE
    WHEN c_loan_id IS NOT NULL THEN 'resolved'
    ELSE 'unresolved'
  END AS loan_status,
  COUNT(*) AS txn_count
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'bank'
GROUP BY loan_status;

-- 2. Loan-linked transactions by direction
SELECT c_loan_direction, COUNT(*) AS txn_count
FROM app_fd_trxEnrichment
WHERE c_source_tp = 'bank' AND c_loan_id IS NOT NULL
GROUP BY c_loan_direction;

-- 3. Auto-registered loans pending review
SELECT id, c_contract_number, c_counterparty_name,
       c_counterparty_account, c_direction, c_registration_note
FROM app_fd_loan_master
WHERE c_source = 'AUTO_REGISTERED' AND c_status = 'DRAFT';

-- 4. Transaction count per loan contract
SELECT l.id, l.c_contract_number, l.c_counterparty_name,
       l.c_direction, COUNT(e.id) AS txn_count
FROM app_fd_loan_master l
LEFT JOIN app_fd_trxEnrichment e ON e.c_loan_id = l.id
GROUP BY l.id, l.c_contract_number, l.c_counterparty_name, l.c_direction
ORDER BY txn_count DESC;
```

---

## 9. Edge Cases

### 9.1 Multiple Loans with Same Counterparty Account

If one counterparty has multiple ACTIVE loans, Tier 2 returns the first match (LIMIT 1). This could link to the wrong loan. Mitigation: Tier 1 (contract number) takes priority and is more precise. Tier 2 is a fallback for recurring transactions from known parties.

For ambiguous cases, the operator can correct loan_id manually in the enrichment workspace.

### 9.2 Contract Number Appears in Non-Loan Description

If a non-loan transaction description coincidentally matches a contract pattern (e.g., "Invoice 315001"), it would incorrectly attempt loan resolution. Mitigation: the contract patterns are specific to Estonian loan terminology ("leping", "laenuleping", "laen", "avaldusele"). Generic numbers without these prefixes are not matched.

### 9.3 Same Contract Number Used Across Different Counterparties

The loan_master stores contract numbers as-is. If two counterparties use the same contract number format (e.g., both have a contract "001"), Tier 1 returns the first ACTIVE match. Mitigation: contract numbers in practice include enough specificity (year suffix, unique numbering per institution).

### 9.4 Bank Account Interest

"Account interest" transactions (TXMP-000007) don't contain contract patterns. They fall through all tiers and remain unlinked. This is correct — bank account interest is not a loan contract.

### 9.5 Dividend Income from Loan-Related Entities

Dividends from companies like "Hüpoteeklaen AS" may share the same other_side_account as loan transactions. Tier 2 could incorrectly link a dividend to a loan. Mitigation: dividends have internal_type DIV_INCOME, and their descriptions don't contain contract patterns. If the account matches, the link is at worst benign — operator can clear loan_id.

---

## 10. Execution Order

1. **Phase A — Form and data** (no Java):
   - A1: Deploy F02.04 loan_master form to Joget
   - A2: Load initial loan data (manually curated from 53-transaction analysis)
   - A3: Add loan_id and loan_direction fields to F01.05 form

2. **Phase B — Java code** (rows-enrichment only):
   - B1: Add TABLE_LOAN_MASTER to DomainConstants.java
   - B2: Create LoanResolutionStep.java
   - B3: Update EnrichmentDataPersister.java (two field mappings)
   - B4: Wire LoanResolutionStep into pipeline (RowsEnricher or step chain)
   - B5: Unit tests

3. **Phase C — Integration test**:
   - C1: Build and deploy rows-enrichment
   - C2: Re-run enrichment on full dataset (22 secu + 120 bank)
   - C3: Verify loan linkage with §8.4 queries
   - C4: Review auto-registered DRAFT loans

### Expected Final State

```
Secu (22 rows): unchanged from CHANGES-03
  - 17 PAIRED, 5 ENRICHED

Bank (120 rows):
  - Securities-related (35): 33 PAIRED, 2 ENRICHED (unchanged)
  - Loan-related (~53): ENRICHED, with loan_id populated for those
    with contract references or known counterparty accounts
  - Other (~32): ENRICHED or MANUAL_REVIEW (unchanged, loan_id = null)

Loan master:
  - N ACTIVE loans (manually loaded)
  - M DRAFT loans (auto-registered, pending operator review)
```

---

## 11. Non-Changes (Intentional)

- **gam-framework EntityType** — No LOAN_CONTRACT type. Loans are master data like asset_master, managed by form UI.
- **StatusManager** — No loan status transitions. Loan lifecycle is manual.
- **Exception queue** — No UNKNOWN_LOAN exception. Loan resolution is non-blocking; unresolved transactions continue normally.
- **TransactionPairingStep** — No changes. Loan linkage is 1:many (one contract → many transactions), not pair-matching.
- **F14 rules** — No changes. The existing INT_INCOME, INT_EXPENSE, LOAN_PAYMENT, CASH_IN, CASH_OUT types are sufficient.
