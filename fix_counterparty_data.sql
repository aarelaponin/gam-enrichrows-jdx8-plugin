-- Fix counterparty_master data issue
-- The c_counterpartId field must be populated for counterparty records

-- Check current state
SELECT id, c_counterpartId, c_bankId, c_counterpartyType, c_isActive 
FROM app_fd_counterparty_master 
WHERE c_bankId = 'LHVBEE22';

-- Update the LHV-EE counterparty record to set the c_counterpartId field
-- This should match the value you mentioned earlier: CPT0143
UPDATE app_fd_counterparty_master 
SET c_counterpartId = 'CPT0143' 
WHERE c_bankId = 'LHVBEE22' AND (c_counterpartId IS NULL OR c_counterpartId = '');

-- Verify the update
SELECT id, c_counterpartId, c_bankId, c_counterpartyType, c_isActive 
FROM app_fd_counterparty_master 
WHERE c_bankId = 'LHVBEE22';

-- Check if there are F14 rules for this counterparty
SELECT id, c_counterpartyId, c_sourceType, c_mappingName, c_internalType, c_priority 
FROM app_fd_cp_txn_mapping 
WHERE c_counterpartyId = 'CPT0143' AND c_sourceType = 'secu'
ORDER BY c_priority;

-- If needed, you can also check other counterparty records that might have missing c_counterpartId
SELECT id, c_counterpartId, c_bankId, c_counterpartyType 
FROM app_fd_counterparty_master 
WHERE c_counterpartId IS NULL OR c_counterpartId = '';