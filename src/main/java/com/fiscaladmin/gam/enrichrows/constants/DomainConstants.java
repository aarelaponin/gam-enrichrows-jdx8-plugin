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

    // =====================================================
    // Processing Tables (GL specific)
    // =====================================================
    public static final String TABLE_TRX_ENRICHMENT = "trx_enrichment";
    public static final String TABLE_EXCEPTION_QUEUE = "exception_queue";
    public static final String TABLE_AUDIT_LOG = "audit_log";
    
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

    
    // =====================================================
    // Domain-Specific Field Names
    // =====================================================
    // Common financial fields
    public static final String FIELD_CURRENCY = "currency";
    public static final String FIELD_CUSTOMER_ID = "customer_id";
    public static final String FIELD_STATEMENT_ID = "statement_id";
    public static final String FIELD_ACCOUNT_TYPE = "account_type";
    public static final String FIELD_BANK = "bank";
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

    // =====================================================
    // Audit Actions (Domain specific)
    // =====================================================
    public static final String AUDIT_CURRENCY_VALIDATED = "CURRENCY_VALIDATED";
    public static final String AUDIT_F14_MAPPED = "F14_MAPPED";
    public static final String AUDIT_CUSTOMER_IDENTIFIED = "CUSTOMER_IDENTIFIED";
    public static final String AUDIT_BASE_CURRENCY_CALCULATED = "BASE_CURRENCY_CALCULATED";
    public static final String AUDIT_ENRICHMENT_SAVED = "ENRICHMENT_SAVED";

    // Private constructor to prevent instantiation
    private DomainConstants() {
        throw new AssertionError("DomainConstants class should not be instantiated");
    }
}