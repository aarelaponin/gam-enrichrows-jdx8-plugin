-- Debug F14 Rule Mapping Issues
-- Run these queries to diagnose why F14 rules aren't matching

-- 1. Check what transactions are being processed
SELECT id, c_statement_id, c_status, c_currency, c_customer_id 
FROM app_fd_bank_total_trx 
WHERE c_status = 'new' 
LIMIT 10;

SELECT id, c_statement_id, c_status, c_currency, c_customer_id 
FROM app_fd_secu_total_trx 
WHERE c_status = 'new' 
LIMIT 10;

-- 2. Check counterparty_master table structure and data
SELECT id, c_counterpartId, c_bankId, c_counterpartyType, c_isActive 
FROM app_fd_counterparty_master 
LIMIT 10;

-- 3. Check if F14 rules exist at all
SELECT COUNT(*) as rule_count, c_sourceType, c_counterpartyId 
FROM app_fd_cp_txn_mapping 
GROUP BY c_sourceType, c_counterpartyId;

-- 4. Check for SYSTEM rules (universal fallback)
SELECT id, c_mappingName, c_sourceType, c_matchingField, c_matchOperator, c_matchValue, c_internalType, c_priority, c_status
FROM app_fd_cp_txn_mapping 
WHERE c_counterpartyId = 'SYSTEM'
ORDER BY c_sourceType, c_priority;

-- 5. Check all active rules
SELECT id, c_counterpartyId, c_sourceType, c_mappingName, c_internalType, c_priority, c_status
FROM app_fd_cp_txn_mapping 
WHERE c_status IN ('Active', 'ACTIVE', 'active')
ORDER BY c_counterpartyId, c_sourceType, c_priority;

-- 6. Check audit logs with NULL status
SELECT id, c_transaction_id, c_action, c_step_name, c_status, c_timestamp 
FROM app_fd_audit_log 
WHERE c_status IS NULL 
ORDER BY c_timestamp DESC 
LIMIT 20;

-- 7. Check enrichment table for any successful processing
SELECT id, c_source_transaction_id, c_processing_status, c_internal_type, dateCreated 
FROM app_fd_trx_enrichment 
ORDER BY dateCreated DESC 
LIMIT 10;