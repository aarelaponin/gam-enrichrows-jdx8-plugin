-- Essential checks for F14 rule mapping to work

-- 1. CRITICAL: Check if ANY F14 rules exist
SELECT COUNT(*) as total_rules FROM app_fd_cp_txn_mapping;

-- 2. CRITICAL: Check if there are SYSTEM fallback rules (these should always match if nothing else does)
SELECT * FROM app_fd_cp_txn_mapping 
WHERE c_counterpartyId = 'SYSTEM' 
AND c_status IN ('Active', 'ACTIVE');

-- 3. Check what counterparty IDs are coming from Step 3 (Counterparty Determination)
-- Look at recent enrichment records to see what counterparty_id was determined
SELECT id, c_source_transaction_id, c_additional_data 
FROM app_fd_trx_enrichment 
WHERE c_additional_data LIKE '%counterparty_id%' 
ORDER BY dateCreated DESC 
LIMIT 5;

-- 4. If no rules exist, you need to create at least one SYSTEM fallback rule:
-- INSERT INTO app_fd_cp_txn_mapping (id, c_counterpartyId, c_sourceType, c_mappingName, c_matchingField, c_matchOperator, c_matchValue, c_internalType, c_priority, c_status) 
-- VALUES ('system-fallback-bank', 'SYSTEM', 'bank', 'Default Bank Transaction', 'payment_description', 'contains', '', 'PAYMENT', 100, 'Active');

-- INSERT INTO app_fd_cp_txn_mapping (id, c_counterpartyId, c_sourceType, c_mappingName, c_matchingField, c_matchOperator, c_matchValue, c_internalType, c_priority, c_status) 
-- VALUES ('system-fallback-secu', 'SYSTEM', 'secu', 'Default Securities Transaction', 'type', 'contains', '', 'SECURITIES', 100, 'Active');