-- Check what currencies are in the transactions
SELECT DISTINCT c_currency, COUNT(*) as count
FROM app_fd_bank_total_trx
WHERE c_status = 'new'
GROUP BY c_currency;

SELECT DISTINCT c_currency, COUNT(*) as count  
FROM app_fd_secu_total_trx
WHERE c_status = 'new'
GROUP BY c_currency;

-- Check what currencies exist in the master table
SELECT id, c_code, c_name, c_symbol
FROM app_fd_currency
ORDER BY c_code;

-- If the currency table is empty or has different column names, check:
SHOW COLUMNS FROM app_fd_currency;