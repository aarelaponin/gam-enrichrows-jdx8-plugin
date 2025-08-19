package com.fiscaladmin.gam.enrichrows.loader;

import com.fiscaladmin.gam.enrichrows.framework.*;
import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import java.util.*;

/**
 * Data loader for GL transactions.
 * This replaces TransactionFetcherStep and separates data loading from processing.
 */
public class TransactionDataLoader extends AbstractDataLoader<DataContext> {
    
    private static final String CLASS_NAME = TransactionDataLoader.class.getName();
    
    @Override
    public String getLoaderName() {
        return "GL Transaction Data Loader";
    }
    
    @Override
    public boolean validateDataSource(FormDataDao dao) {
        // Check if required tables exist
        try {
            dao.find(null, DomainConstants.TABLE_BANK_STATEMENT, null, null, null, false, 0, 1);
            dao.find(null, DomainConstants.TABLE_BANK_TOTAL_TRX, null, null, null, false, 0, 1);
            dao.find(null, DomainConstants.TABLE_SECU_TOTAL_TRX, null, null, null, false, 0, 1);
            return true;
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Required tables not found");
            return false;
        }
    }
    
    @Override
    protected List<DataContext> performLoad(FormDataDao dao, 
                                                   Map<String, Object> parameters) {
        List<DataContext> transactions = new ArrayList<>();
        
        try {
            // Get all unprocessed statements
            List<FormRow> unprocessedStatements = fetchUnprocessedStatements(dao);
            
            LogUtil.info(CLASS_NAME, "Found " + unprocessedStatements.size() + " unprocessed statements");

            // Process each statement
            for (FormRow statementRow : unprocessedStatements) {
                String statementId = statementRow.getId();
                String accountType = statementRow.getProperty(DomainConstants.FIELD_ACCOUNT_TYPE);
                
                // Mark statement as processing
                markStatementAsProcessing(statementRow, dao);
                
                if (DomainConstants.SOURCE_TYPE_BANK.equals(accountType)) {
                    transactions.addAll(fetchBankTransactions(dao, statementRow));
                } else if (DomainConstants.SOURCE_TYPE_SECU.equals(accountType)) {
                    transactions.addAll(fetchSecuritiesTransactions(dao, statementRow));
                }
            }
            
            // Sort transactions by date for proper processing order
            sortTransactionsByDate(transactions);

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error fetching unprocessed transactions");
        }
        
        return transactions;
    }
    
    /**
     * Fetch all unprocessed statements
     */
    private List<FormRow> fetchUnprocessedStatements(FormDataDao dao) {
        // Get ALL statements first - use configured table name
        String statementTable = DomainConstants.TABLE_BANK_STATEMENT;
        String statusField = FrameworkConstants.FIELD_STATUS;
        String newStatus = FrameworkConstants.STATUS_NEW;
        
        FormRowSet statements = loadRecords(dao, 
            statementTable,
            null,  // No condition - get all
            null,  // No params
            "from_date",  // Sort by from_date
            false,  // Ascending order
            100  // Use configured batch size
        );
        
        // Filter for unprocessed statements
        return filterByStatus(statements, statusField, newStatus);
    }
    
    /**
     * Fetch bank transactions for a specific statement
     */
    private List<DataContext> fetchBankTransactions(FormDataDao dao, FormRow statementRow) {
        List<DataContext> contexts = new ArrayList<>();
        
        try {
            String statementId = statementRow.getId();
            
            // Get all bank transactions - use configured table name
            String bankTable = DomainConstants.TABLE_BANK_TOTAL_TRX;
            String statementIdField = DomainConstants.FIELD_STATEMENT_ID;
            String statusField = FrameworkConstants.FIELD_STATUS;
            String newStatus = FrameworkConstants.STATUS_NEW;
            
            FormRowSet bankTrxRows = loadRecords(dao,
                bankTable,
                null,  // Get all transactions
                null,
                "payment_date",
                false,
                10000  // Get all transactions
            );
            
            // Filter for this statement's unprocessed transactions
            Map<String, String> criteria = new HashMap<>();
            criteria.put(statementIdField, statementId);
            criteria.put(statusField, newStatus);
            
            List<FormRow> filteredRows = filterByCriteria(bankTrxRows, criteria);
            
            // Convert to contexts
            for (FormRow trxRow : filteredRows) {
                DataContext context = createBankDataContext(trxRow, statementRow);
                contexts.add(context);
            }

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error fetching bank transactions");
        }
        
        return contexts;
    }
    
    /**
     * Fetch securities transactions for a specific statement
     */
    private List<DataContext> fetchSecuritiesTransactions(FormDataDao dao, FormRow statementRow) {
        List<DataContext> contexts = new ArrayList<>();
        
        try {
            String statementId = statementRow.getId();
            
            // Get all securities transactions - use configured table name
            String secuTable = DomainConstants.TABLE_SECU_TOTAL_TRX;
            String statementIdField = DomainConstants.FIELD_STATEMENT_ID;
            String statusField = FrameworkConstants.FIELD_STATUS;
            String newStatus = FrameworkConstants.STATUS_NEW;
            
            FormRowSet secuTrxRows = loadRecords(dao,
                secuTable,
                null,  // Get all transactions
                null,
                "transaction_date",
                false,
                10000  // Get all transactions
            );
            
            // Filter for this statement's unprocessed transactions
            Map<String, String> criteria = new HashMap<>();
            criteria.put(statementIdField, statementId);
            criteria.put(statusField, newStatus);
            
            List<FormRow> filteredRows = filterByCriteria(secuTrxRows, criteria);
            
            // Convert to contexts
            for (FormRow trxRow : filteredRows) {
                DataContext context = createSecuritiesDataContext(trxRow, statementRow);
                contexts.add(context);
            }

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error fetching securities transactions");
        }
        
        return contexts;
    }
    
    /**
     * Create context for bank transaction
     */
    private DataContext createBankDataContext(FormRow trxRow, FormRow statementRow) {
        DataContext context = new DataContext();
        
        // Set source type and raw data
        context.setSourceType(DomainConstants.SOURCE_TYPE_BANK);
        context.setTransactionRow(trxRow);
        context.setStatementRow(statementRow);

        // Set identifiers
        context.setTransactionId(trxRow.getId());
        context.setStatementId(statementRow.getId());
        
        // Set statement context - use configured field names
        String bankField = DomainConstants.FIELD_BANK;
        context.setStatementBank(statementRow.getProperty(bankField));
        context.setAccountType(DomainConstants.SOURCE_TYPE_BANK);
        
        // Extract bank transaction fields - use configured field mappings
        String currencyField = DomainConstants.FIELD_CURRENCY;
        String amountField = DomainConstants.FIELD_PAYMENT_AMOUNT;
        String dateField = DomainConstants.FIELD_PAYMENT_DATE;
        String dcField = DomainConstants.FIELD_DEBIT_CREDIT;
        String bicField = DomainConstants.FIELD_OTHER_SIDE_BIC;
        String nameField = DomainConstants.FIELD_OTHER_SIDE_NAME;
        String descField = DomainConstants.FIELD_PAYMENT_DESCRIPTION;
        String refField = DomainConstants.FIELD_REFERENCE_NUMBER;
        String customerField = DomainConstants.FIELD_CUSTOMER_ID;
        
        context.setCurrency(trxRow.getProperty(currencyField));
        context.setAmount(trxRow.getProperty(amountField));
        context.setTransactionDate(trxRow.getProperty(dateField));
        context.setDebitCredit(trxRow.getProperty(dcField));
        context.setOtherSideBic(trxRow.getProperty(bicField));
        context.setOtherSideName(trxRow.getProperty(nameField));
        context.setPaymentDescription(trxRow.getProperty(descField));
        context.setReferenceNumber(trxRow.getProperty(refField));
        String customerId = trxRow.getProperty(customerField);
        context.setCustomerId(customerId);

        return context;
    }
    
    /**
     * Create context for securities transaction
     */
    private DataContext createSecuritiesDataContext(FormRow trxRow, FormRow statementRow) {
        DataContext context = new DataContext();
        
        // Set source type and raw data
        context.setSourceType(DomainConstants.SOURCE_TYPE_SECU);
        context.setTransactionRow(trxRow);
        context.setStatementRow(statementRow);
        
        // Set identifiers
        context.setTransactionId(trxRow.getId());
        context.setStatementId(statementRow.getId());
        
        // Set statement context - use configured field names
        String bankField = DomainConstants.FIELD_BANK;
        context.setStatementBank(statementRow.getProperty(bankField));
        context.setAccountType(DomainConstants.SOURCE_TYPE_SECU);
        
        // Extract securities transaction fields - use configured field mappings
        String currencyField = DomainConstants.FIELD_CURRENCY;
        String amountField = DomainConstants.FIELD_TOTAL_AMOUNT;
        String dateField = DomainConstants.FIELD_TRANSACTION_DATE;
        String typeField = DomainConstants.FIELD_TYPE;
        String tickerField = DomainConstants.FIELD_TICKER;
        String descField = DomainConstants.FIELD_DESCRIPTION;
        String quantityField = DomainConstants.FIELD_QUANTITY;
        String priceField = DomainConstants.FIELD_PRICE;
        String feeField = DomainConstants.FIELD_FEE;
        String refField = DomainConstants.FIELD_REFERENCE;
        String customerField = DomainConstants.FIELD_CUSTOMER_ID;
        
        context.setCurrency(trxRow.getProperty(currencyField));
        context.setAmount(trxRow.getProperty(amountField));
        context.setTransactionDate(trxRow.getProperty(dateField));
        context.setType(trxRow.getProperty(typeField));
        context.setTicker(trxRow.getProperty(tickerField));
        context.setDescription(trxRow.getProperty(descField));
        context.setQuantity(trxRow.getProperty(quantityField));
        context.setPrice(trxRow.getProperty(priceField));
        context.setFee(trxRow.getProperty(feeField));
        context.setReference(trxRow.getProperty(refField));
        
        // Set customer_id for securities transactions
        String customerId = trxRow.getProperty(customerField);
        context.setCustomerId(customerId);

        return context;
    }
    
    /**
     * Sort transactions by date for proper processing order
     */
    private void sortTransactionsByDate(List<DataContext> transactions) {
        transactions.sort((t1, t2) -> {
            String date1 = t1.getTransactionDate();
            String date2 = t2.getTransactionDate();
            if (date1 == null) return -1;
            if (date2 == null) return 1;
            return date1.compareTo(date2);
        });
    }
    
    /**
     * Mark statement as processing when we start loading its transactions
     */
    private void markStatementAsProcessing(FormRow statementRow, FormDataDao dao) {
        try {
            String statementId = statementRow.getId();
            LogUtil.info(CLASS_NAME, "Marking statement " + statementId + " as processing");
            
            // Update status to processing
            statementRow.setProperty(FrameworkConstants.FIELD_STATUS, "processing");
            statementRow.setProperty("processing_started", new Date().toString());
            
            // Save the update
            FormRowSet rowSet = new FormRowSet();
            rowSet.add(statementRow);
            dao.saveOrUpdate(null, DomainConstants.TABLE_BANK_STATEMENT, rowSet);
            
            LogUtil.info(CLASS_NAME, "Statement " + statementId + " marked as processing");
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error marking statement as processing: " + statementRow.getId());
            // Continue processing even if status update fails
        }
    }
}