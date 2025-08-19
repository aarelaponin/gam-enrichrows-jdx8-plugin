# DEBUG JAR - What to Look For

## JAR Location
`target/gl-postings-8.1-SNAPSHOT.jar` (2.4 MB)

## What This Debug JAR Does
This JAR has extensive debug logging to trace why F14 rules aren't matching. It will help identify where the data flow is breaking between CounterpartyDeterminationStep and F14RuleMappingStep.

## What to Look for in Joget Logs

### 1. COUNTERPARTY DETERMINATION (Step 3)
Look for these debug messages:

```
=== DEBUG: STORING COUNTERPARTY IN CONTEXT ===
Transaction ID: [some_id]
Counterparty ID being stored: '[value]'  <-- THIS IS KEY! Note what value is stored
Counterparty Type: '[value]'
Counterparty BIC: '[value]'
Counterparty Name: '[value]'
Verifying storage - counterparty_id in additionalData: '[value]'
==============================================
```

**CHECK**: Is the Counterparty ID a real value (like 'CPT0143') or is it NULL/empty?

### 2. F14 RULE MAPPING (Step 4)
Look for these debug messages:

```
=== DEBUG: RETRIEVING COUNTERPARTY FROM CONTEXT ===
Transaction ID: [some_id]
additionalData is NOT null  <-- OR "additionalData is NULL!"
additionalData contents: {key=value, key=value}  <-- Check if counterparty_id is here
Found counterparty_id: '[value]'  <-- THIS SHOULD MATCH what was stored in Step 3
====================================================
```

**CHECK**: 
- Is additionalData null?
- Is counterparty_id present in additionalData?
- Does the counterparty_id value match what was stored in Step 3?

### 3. F14 RULES LOADING
Look for:

```
=== DEBUG: F14 RULES LOADING ===
Looking for rules matching counterpartyId: '[value]' and sourceType: '[bank/secu]'
Total rules in cp_txn_mapping table: [number]

Checking rule: id=X, sourceType='Y', counterpartyId='Z', status='W'
  -> Skipped: not active  <-- OR other skip reasons
```

**CHECK**:
- How many total rules exist in the table?
- What counterpartyId values do the rules have?
- Are rules being skipped? Why?

## Key Questions to Answer

1. **Is the counterparty_id being found in Step 3?**
   - If NULL/empty: The counterparty lookup is failing
   - If has value: Note the exact value

2. **Is the counterparty_id being passed to Step 4?**
   - If additionalData is NULL: Critical problem with data flow
   - If counterparty_id is missing: Data not being stored correctly
   - If counterparty_id exists but different value: Data corruption

3. **Are F14 rules being found?**
   - If 0 rules in table: Database issue
   - If rules exist but all skipped: Check skip reasons

## Most Likely Issues

1. **Field name mismatch**: The database field for counterparty ID might be named differently than expected
2. **Data flow broken**: additionalData not being passed between steps
3. **Rule matching**: Rules exist but counterparty IDs don't match

## Quick SQL Checks

Run these to verify data:

```sql
-- Check what counterparty IDs exist
SELECT DISTINCT c_counterpartId FROM app_fd_counterparty_master;

-- Check what counterparty IDs the rules expect
SELECT DISTINCT c_counterpartyId FROM app_fd_cp_txn_mapping;

-- Do they match? If not, that's the problem!
```

## Report Back With:
1. The exact counterparty_id value from Step 3 debug log
2. Whether that same value appears in Step 4 debug log
3. How many F14 rules were found and why they were skipped