package com.fiscaladmin.gam.enrichrows.constants;

/**
 * Domain-specific constants for GL postings and financial transactions.
 * These constants are specific to the financial/accounting domain
 * and contain business logic related values.
 */
public final class DomainConstants {

    // =====================================================
    // Source Tables (GL specific)
    // =====================================================
    public static final String TABLE_BANK_STATEMENT = "bank_statement";
    public static final String TABLE_BANK_TOTAL_TRX = "bank_total_trx";
    public static final String TABLE_SECU_TOTAL_TRX = "secu_total_trx";

    // =====================================================
    // Master Data Tables
    // =====================================================
    public static final String TABLE_CUSTOMER_MASTER = "customer";  // Actual table name is 'customer'
    public static final String TABLE_COUNTERPARTY_MASTER = "counterparty_master";
    public static final String TABLE_CURRENCY_MASTER = "currency";  // Actual table name is 'currency';
    public static final String TABLE_ASSET_MASTER = "asset_master";
    public static final String TABLE_CP_TXN_MAPPING = "cp_txn_mapping";
    public static final String TABLE_TRANSACTION_TYPE_MAP = "transactionTypeMap";
    public static final String TABLE_TRANSACTION_TYPE = "trxType";
    public static final String TABLE_FX_RATES_EUR = "fx_rates_eur";
    public static final String TABLE_CUSTOMER_ACCOUNT = "customer_account";
    public static final String TABLE_BANK = "bank";
    public static final String TABLE_BROKER = "broker";
    public static final String TABLE_LOAN_MASTER = "loan_master";

    // =====================================================
    // Processing Tables (GL specific)
    // =====================================================
    public static final String TABLE_TRX_ENRICHMENT = "trxEnrichment";
    public static final String TABLE_EXCEPTION_QUEUE = "exception_queue";
    public static final String TABLE_AUDIT_LOG = "audit_log";
    public static final String TABLE_TRX_PAIR = "trx_pair";
    
    // =====================================================
    // Source Types (Financial domain)
    // =====================================================
    public static final String SOURCE_TYPE_BANK = "bank";
    public static final String SOURCE_TYPE_SECU = "secu";
    
    // =====================================================
    // Currency Constants
    // =====================================================
    public static final String BASE_CURRENCY = "EUR";
    
    // =====================================================
    // Processing Status - Domain Specific
    // =====================================================
    public static final String PROCESSING_STATUS_FX_CONVERTED = "fx_converted";
    public static final String PROCESSING_STATUS_F14_MAPPED = "f14_mapped";
    public static final String PROCESSING_STATUS_CUSTOMER_IDENTIFIED = "customer_identified";
    public static final String PROCESSING_STATUS_COUNTERPARTY_DETERMINED = "counterparty_determined";
    public static final String PROCESSING_STATUS_CURRENCY_VALIDATED = "currency_validated";
    public static final String PROCESSING_STATUS_ASSET_RESOLVED = "asset_resolved";

    
    // =====================================================
    // Domain-Specific Field Names
    // =====================================================
    // Common financial fields
    public static final String FIELD_CURRENCY = "currency";
    public static final String FIELD_CUSTOMER_ID = "customer_id";
    public static final String FIELD_STATEMENT_ID = "statement_id";
    public static final String FIELD_ACCOUNT_TYPE = "account_type";
    public static final String FIELD_BANK = "bank";
    public static final String FIELD_OTHER_SIDE_ACCOUNT = "other_side_account";
    // Bank transaction fields
    public static final String FIELD_PAYMENT_DATE = "payment_date";
    public static final String FIELD_PAYMENT_AMOUNT = "payment_amount";
    public static final String FIELD_DEBIT_CREDIT = "d_c";
    public static final String FIELD_OTHER_SIDE_BIC = "other_side_bic";
    public static final String FIELD_OTHER_SIDE_NAME = "other_side_name";
    public static final String FIELD_PAYMENT_DESCRIPTION = "payment_description";
    public static final String FIELD_REFERENCE_NUMBER = "reference_number";

    // Securities transaction fields
    public static final String FIELD_TRANSACTION_DATE = "transaction_date";
    public static final String FIELD_TOTAL_AMOUNT = "total_amount";
    public static final String FIELD_AMOUNT = "amount";
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_TICKER = "ticker";
    public static final String FIELD_QUANTITY = "quantity";
    public static final String FIELD_PRICE = "price";
    public static final String FIELD_FEE = "fee";
    public static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_REFERENCE = "reference";

    // =====================================================
    // Exception Types (Domain specific)
    // =====================================================
    public static final String EXCEPTION_INVALID_CURRENCY = "INVALID_CURRENCY";
    public static final String EXCEPTION_MISSING_CUSTOMER = "MISSING_CUSTOMER";
    public static final String EXCEPTION_FX_RATE_MISSING = "FX_RATE_MISSING";
    public static final String EXCEPTION_MISSING_TICKER = "MISSING_TICKER";
    public static final String EXCEPTION_UNKNOWN_ASSET = "UNKNOWN_ASSET";
    public static final String EXCEPTION_INACTIVE_ASSET = "INACTIVE_ASSET";
    public static final String EXCEPTION_ASSET_RESOLUTION_ERROR = "ASSET_RESOLUTION_ERROR";
    public static final String EXCEPTION_MISSING_CURRENCY = "MISSING_CURRENCY";
    public static final String EXCEPTION_COUNTERPARTY_NOT_FOUND = "COUNTERPARTY_NOT_FOUND";
    public static final String EXCEPTION_COUNTERPARTY_DETERMINATION_ERROR = "COUNTERPARTY_DETERMINATION_ERROR";
    public static final String EXCEPTION_INACTIVE_CUSTOMER = "INACTIVE_CUSTOMER";
    public static final String EXCEPTION_LOW_CONFIDENCE = "LOW_CONFIDENCE_IDENTIFICATION";
    public static final String EXCEPTION_CUSTOMER_IDENTIFICATION_ERROR = "CUSTOMER_IDENTIFICATION_ERROR";
    public static final String EXCEPTION_NO_F14_RULES = "NO_F14_RULES";
    public static final String EXCEPTION_NO_RULE_MATCH = "NO_RULE_MATCH";
    public static final String EXCEPTION_F14_MAPPING_ERROR = "F14_MAPPING_ERROR";
    public static final String EXCEPTION_OLD_FX_RATE = "OLD_FX_RATE";
    public static final String EXCEPTION_INVALID_FX_DATE = "INVALID_FX_DATE";
    public static final String EXCEPTION_FX_CONVERSION_ERROR = "FX_CONVERSION_ERROR";
    public static final String EXCEPTION_CURRENCY_VALIDATION_ERROR = "CURRENCY_VALIDATION_ERROR";
    public static final String EXCEPTION_PORTFOLIO_ALLOCATION_REQUIRED = "PORTFOLIO_ALLOCATION_REQUIRED";

    // =====================================================
    // Audit Actions (Domain specific)
    // =====================================================
    public static final String AUDIT_CURRENCY_VALIDATED = "CURRENCY_VALIDATED";
    public static final String AUDIT_F14_MAPPED = "F14_MAPPED";
    public static final String AUDIT_CUSTOMER_IDENTIFIED = "CUSTOMER_IDENTIFIED";
    public static final String AUDIT_BASE_CURRENCY_CALCULATED = "BASE_CURRENCY_CALCULATED";
    public static final String AUDIT_COUNTERPARTY_DETERMINED = "COUNTERPARTY_DETERMINED";
    public static final String AUDIT_ASSET_RESOLVED = "ASSET_RESOLVED";
    public static final String AUDIT_ASSET_AUTO_REGISTERED = "ASSET_AUTO_REGISTERED";

    // Private constructor to prevent instantiation
    private DomainConstants() {
        throw new AssertionError("DomainConstants class should not be instantiated");
    }
}