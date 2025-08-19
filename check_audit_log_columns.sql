-- Check the exact column names in audit_log table
SHOW COLUMNS FROM app_fd_audit_log;

-- Check a sample of the NULL status records
SELECT * FROM app_fd_audit_log 
WHERE c_status IS NULL 
LIMIT 5;