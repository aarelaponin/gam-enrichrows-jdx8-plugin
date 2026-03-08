package com.fiscaladmin.gam.enrichrows.loader;

import com.fiscaladmin.gam.enrichrows.framework.*;
import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;
import com.fiscaladmin.gam.framework.status.EntityType;
import com.fiscaladmin.gam.framework.status.InvalidTransitionException;
import com.fiscaladmin.gam.framework.status.Status;
import com.fiscaladmin.gam.framework.status.StatusManager;
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

    private static final Set<String> WORKSPACE_PROTECTED_STATUSES = new HashSet<>(Arrays.asList(
        Status.PAIRED.getCode(),
        Status.IN_REVIEW.getCode(),
        Status.ADJUSTED.getCode(),
        Status.READY.getCode(),
        Status.CONFIRMED.getCode(),
        Status.SUPERSEDED.getCode()
    ));

    private StatusManager statusManager;

    public void setStatusManager(StatusManager statusManager) {
        this.statusManager = statusManager;
    }

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
            // Get all enrichable statements (CONSOLIDATED + ENRICHED)
            List<FormRow> enrichableStatements = fetchEnrichableStatements(dao, parameters);

            LogUtil.info(CLASS_NAME, "Found " + enrichableStatements.size() + " enrichable statements");

            // Process each statement
            for (FormRow statementRow : enrichableStatements) {
                String statementId = statementRow.getId();
                String accountType = statementRow.getProperty(DomainConstants.FIELD_ACCOUNT_TYPE);

                // Statement stays CONSOLIDATED until batch completion in persister

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
     * Fetch all enrichable statements (CONSOLIDATED for first-time, ENRICHED for re-enrichment)
     */
    private List<FormRow> fetchEnrichableStatements(FormDataDao dao, Map<String, Object> parameters) {
        String statementTable = DomainConstants.TABLE_BANK_STATEMENT;

        int batchSize = 100;
        if (parameters != null && parameters.containsKey("batchSize")) {
            try {
                batchSize = Integer.parseInt(parameters.get("batchSize").toString());
            } catch (NumberFormatException e) {
                LogUtil.warn(CLASS_NAME, "Invalid batchSize, using default 100");
            }
        }

        FormRowSet statements = loadRecords(dao,
            statementTable,
            null,
            null,
            "from_date",
            false,
            batchSize
        );

        List<FormRow> enrichable = new ArrayList<>();
        if (statements != null) {
            for (FormRow row : statements) {
                String status = row.getProperty(FrameworkConstants.FIELD_STATUS);
                if (Status.CONSOLIDATED.getCode().equals(status)
                        || Status.ENRICHED.getCode().equals(status)) {
                    enrichable.add(row);
                }
            }
        }
        LogUtil.info(CLASS_NAME, "Found " + enrichable.size()
                + " enrichable statements (consolidated + enriched)");
        return enrichable;
    }
    
    /**
     * Fetch bank transactions for a specific statement.
     * Accepts NEW (first-time), ENRICHED and MANUAL_REVIEW (re-enrichment).
     */
    private List<DataContext> fetchBankTransactions(FormDataDao dao, FormRow statementRow) {
        List<DataContext> contexts = new ArrayList<>();

        try {
            String statementId = statementRow.getId();

            FormRowSet bankTrxRows = loadRecords(dao,
                DomainConstants.TABLE_BANK_TOTAL_TRX,
                null,
                null,
                "payment_date",
                false,
                10000
            );

            Set<String> enrichableStatuses = new HashSet<>(Arrays.asList(
                Status.NEW.getCode(),
                Status.ENRICHED.getCode(),
                Status.MANUAL_REVIEW.getCode()
            ));

            if (bankTrxRows != null) {
                for (FormRow trxRow : bankTrxRows) {
                    String trxStatementId = trxRow.getProperty(DomainConstants.FIELD_STATEMENT_ID);
                    String trxStatus = trxRow.getProperty(FrameworkConstants.FIELD_STATUS);
                    if (!statementId.equals(trxStatementId)) continue;
                    if (!enrichableStatuses.contains(trxStatus)) continue;

                    DataContext context = createBankDataContext(trxRow, statementRow);
                    boolean isReEnrich = !Status.NEW.getCode().equals(trxStatus);
                    context.setReEnrichment(isReEnrich);
                    preLoadExistingEnrichment(context, dao);

                    if (Status.NEW.getCode().equals(trxStatus)) {
                        transitionToProcessing(dao, EntityType.BANK_TRX, context.getTransactionId());
                    }
                    contexts.add(context);
                }
            }

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error fetching bank transactions");
        }

        return contexts;
    }
    
    /**
     * Fetch securities transactions for a specific statement.
     * Accepts NEW (first-time), ENRICHED and MANUAL_REVIEW (re-enrichment).
     */
    private List<DataContext> fetchSecuritiesTransactions(FormDataDao dao, FormRow statementRow) {
        List<DataContext> contexts = new ArrayList<>();

        try {
            String statementId = statementRow.getId();

            FormRowSet secuTrxRows = loadRecords(dao,
                DomainConstants.TABLE_SECU_TOTAL_TRX,
                null,
                null,
                "transaction_date",
                false,
                10000
            );

            Set<String> enrichableStatuses = new HashSet<>(Arrays.asList(
                Status.NEW.getCode(),
                Status.ENRICHED.getCode(),
                Status.MANUAL_REVIEW.getCode()
            ));

            if (secuTrxRows != null) {
                for (FormRow trxRow : secuTrxRows) {
                    String trxStatementId = trxRow.getProperty(DomainConstants.FIELD_STATEMENT_ID);
                    String trxStatus = trxRow.getProperty(FrameworkConstants.FIELD_STATUS);
                    if (!statementId.equals(trxStatementId)) continue;
                    if (!enrichableStatuses.contains(trxStatus)) continue;

                    DataContext context = createSecuritiesDataContext(trxRow, statementRow);
                    boolean isReEnrich = !Status.NEW.getCode().equals(trxStatus);
                    context.setReEnrichment(isReEnrich);
                    preLoadExistingEnrichment(context, dao);

                    if (Status.NEW.getCode().equals(trxStatus)) {
                        transitionToProcessing(dao, EntityType.SECU_TRX, context.getTransactionId());
                    }
                    contexts.add(context);
                }
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
        context.setStatementDate(statementRow.getProperty("from_date"));
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
        context.setPaymentDate(trxRow.getProperty(dateField));
        context.setPaymentAmount(trxRow.getProperty(amountField));
        context.setDebitCredit(trxRow.getProperty(dcField));
        context.setOtherSideBic(trxRow.getProperty(bicField));
        context.setOtherSideName(trxRow.getProperty(nameField));
        context.setPaymentDescription(trxRow.getProperty(descField));
        context.setReferenceNumber(trxRow.getProperty(refField));
        context.setOtherSideAccount(trxRow.getProperty(DomainConstants.FIELD_OTHER_SIDE_ACCOUNT));
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
        context.setStatementDate(statementRow.getProperty("from_date"));
        context.setAccountType(DomainConstants.SOURCE_TYPE_SECU);

        // Extract securities transaction fields - use configured field mappings
        String currencyField = DomainConstants.FIELD_CURRENCY;
        String amountField = DomainConstants.FIELD_AMOUNT;
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
        context.setTotalAmount(trxRow.getProperty(DomainConstants.FIELD_TOTAL_AMOUNT));
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
     * §9b: Pre-load existing enrichment data into context for idempotency guards.
     * If a trx_enrichment record exists for this source transaction, its resolved
     * fields are populated into context.additionalData so that step guards can
     * detect already-resolved outputs and skip re-processing.
     */
    private void preLoadExistingEnrichment(DataContext context, FormDataDao dao) {
        try {
            String condition = "WHERE c_source_trx_id = ?";
            FormRowSet rows = dao.find(null,
                    DomainConstants.TABLE_TRX_ENRICHMENT,
                    condition,
                    new String[] { context.getTransactionId() },
                    null, false, 0, 1);

            if (rows == null || rows.isEmpty()) {
                return;
            }

            FormRow enrichmentRow = rows.get(0);

            // Check workspace protection — skip re-enrichment for records in workspace states
            String enrichmentStatus = enrichmentRow.getProperty(FrameworkConstants.FIELD_STATUS);
            context.setAdditionalDataValue("enrichment_status", enrichmentStatus);
            if (WORKSPACE_PROTECTED_STATUSES.contains(enrichmentStatus)) {
                context.setAdditionalDataValue("workspace_protected", "true");
                LogUtil.info(CLASS_NAME, "Transaction " + context.getTransactionId()
                        + " enrichment in workspace state " + enrichmentStatus + " — skipping");
                return;
            }

            // Store existing record ID for upsert in persister
            context.setAdditionalDataValue("existing_enrichment_id", enrichmentRow.getId());

            // Reverse-map enrichment fields → additionalData keys
            mapIfPresent(enrichmentRow, "resolved_customer_id", context, "customer_id");
            mapIfPresent(enrichmentRow, "customer_match_method", context, "customer_identification_method");
            mapIfPresent(enrichmentRow, "customer_code", context, "customer_code");
            mapIfPresent(enrichmentRow, "customer_display_name", context, "customer_name");
            mapIfPresent(enrichmentRow, "internal_type", context, "internal_type");
            mapIfPresent(enrichmentRow, "matched_rule_id", context, "f14_rule_id");
            mapIfPresent(enrichmentRow, "loan_id", context, "loan_id");
            mapIfPresent(enrichmentRow, "loan_direction", context, "loan_direction");
            mapIfPresent(enrichmentRow, "loan_resolution_method", context, "loan_resolution_method");
            mapIfPresent(enrichmentRow, "fx_rate_to_eur", context, "fx_rate");
            mapIfPresent(enrichmentRow, "fx_rate_date", context, "fx_rate_date");
            mapIfPresent(enrichmentRow, "resolved_asset_id", context, "asset_id");

            // Counterparty: check routed fields (counterparty_id, custodian_id, broker_id)
            String cpId = enrichmentRow.getProperty("counterparty_id");
            if (cpId == null || cpId.isEmpty()) cpId = enrichmentRow.getProperty("custodian_id");
            if (cpId == null || cpId.isEmpty()) cpId = enrichmentRow.getProperty("broker_id");
            if (cpId != null && !cpId.isEmpty()) {
                context.setAdditionalDataValue("counterparty_id", cpId);
            }

            // Fields that also set context properties
            String customerId = enrichmentRow.getProperty("resolved_customer_id");
            if (customerId != null && !customerId.isEmpty()) {
                context.setCustomerId(customerId);
            }

            String baseAmount = enrichmentRow.getProperty("base_amount_eur");
            if (baseAmount != null && !baseAmount.isEmpty()) {
                context.setAdditionalDataValue("base_amount", baseAmount);
                context.setBaseAmount(baseAmount);
            }

            LogUtil.info(CLASS_NAME, "Pre-loaded existing enrichment " + enrichmentRow.getId()
                    + " for transaction " + context.getTransactionId());

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error pre-loading enrichment for transaction: " + context.getTransactionId());
        }
    }

    private void mapIfPresent(FormRow source, String sourceField,
                               DataContext context, String targetKey) {
        String value = source.getProperty(sourceField);
        if (value != null && !value.isEmpty()) {
            context.setAdditionalDataValue(targetKey, value);
        }
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
     * Transition a transaction to PROCESSING status via StatusManager
     */
    private void transitionToProcessing(FormDataDao dao, EntityType entityType, String transactionId) {
        if (statusManager == null) {
            return;
        }
        try {
            statusManager.transition(dao, entityType, transactionId,
                    Status.PROCESSING, "rows-enrichment", "Pipeline processing started");
            LogUtil.info(CLASS_NAME, "Transaction " + transactionId + " transitioned to PROCESSING");
        } catch (InvalidTransitionException e) {
            LogUtil.error(CLASS_NAME, e, "Invalid status transition for transaction: " + transactionId);
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error transitioning transaction to PROCESSING: " + transactionId);
        }
    }
}