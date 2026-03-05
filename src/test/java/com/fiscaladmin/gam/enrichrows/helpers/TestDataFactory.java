package com.fiscaladmin.gam.enrichrows.helpers;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.framework.DataContext;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;

import java.util.*;

/**
 * Factory for creating test data used across all test classes.
 */
public class TestDataFactory {

    // ---- DataContext builders ----

    public static DataContext bankContext() {
        return bankContext("TRX-001", "STMT-001");
    }

    public static DataContext bankContext(String txId, String stmtId) {
        DataContext ctx = new DataContext();
        ctx.setSourceType(DomainConstants.SOURCE_TYPE_BANK);
        ctx.setTransactionId(txId);
        ctx.setStatementId(stmtId);
        ctx.setTransactionDate("2026-01-15");
        ctx.setCurrency("EUR");
        ctx.setAmount("1000.00");
        ctx.setDebitCredit("D");
        ctx.setOtherSideBic("DEUTDEFF");
        ctx.setOtherSideName("Deutsche Bank AG");
        ctx.setPaymentDescription("Wire transfer");
        ctx.setReferenceNumber("REF-12345");
        ctx.setCustomerId("CUST-001");
        ctx.setStatementBank("BARCGB22");
        ctx.setAccountType(DomainConstants.SOURCE_TYPE_BANK);
        ctx.setTransactionRow(bankTrxRow(txId, stmtId));
        FormRow bankStmtRow = statementRow(stmtId, DomainConstants.SOURCE_TYPE_BANK);
        ctx.setStatementRow(bankStmtRow);
        ctx.setStatementDate(bankStmtRow.getProperty("from_date"));
        return ctx;
    }

    public static DataContext secuContext() {
        return secuContext("TRX-002", "STMT-002");
    }

    public static DataContext secuContext(String txId, String stmtId) {
        DataContext ctx = new DataContext();
        ctx.setSourceType(DomainConstants.SOURCE_TYPE_SECU);
        ctx.setTransactionId(txId);
        ctx.setStatementId(stmtId);
        ctx.setTransactionDate("2026-01-15");
        ctx.setCurrency("USD");
        ctx.setAmount("50000.00");
        ctx.setType("BUY");
        ctx.setTicker("AAPL");
        ctx.setDescription("Apple Inc. Common Stock");
        ctx.setQuantity("100");
        ctx.setPrice("500.00");
        ctx.setFee("25.00");
        ctx.setTotalAmount("50025.00");
        ctx.setReference("SEC-REF-001");
        ctx.setCustomerId("CUST-002");
        ctx.setStatementBank("UBSWCHZH");
        ctx.setAccountType(DomainConstants.SOURCE_TYPE_SECU);
        ctx.setTransactionRow(secuTrxRow(txId, stmtId));
        FormRow secuStmtRow = statementRow(stmtId, DomainConstants.SOURCE_TYPE_SECU);
        ctx.setStatementRow(secuStmtRow);
        ctx.setStatementDate(secuStmtRow.getProperty("from_date"));
        return ctx;
    }

    // ---- FormRow builders ----

    public static FormRow bankTrxRow(String txId, String stmtId) {
        FormRow row = new FormRow();
        row.setId(txId);
        row.setProperty("statement_id", stmtId);
        row.setProperty("status", "new");
        row.setProperty("currency", "EUR");
        row.setProperty("payment_amount", "1000.00");
        row.setProperty("payment_date", "2026-01-15");
        row.setProperty("d_c", "D");
        row.setProperty("other_side_bic", "DEUTDEFF");
        row.setProperty("other_side_name", "Deutsche Bank AG");
        row.setProperty("payment_description", "Wire transfer");
        row.setProperty("reference_number", "REF-12345");
        row.setProperty("customer_id", "CUST-001");
        return row;
    }

    public static FormRow secuTrxRow(String txId, String stmtId) {
        FormRow row = new FormRow();
        row.setId(txId);
        row.setProperty("statement_id", stmtId);
        row.setProperty("status", "new");
        row.setProperty("currency", "USD");
        row.setProperty("amount", "50000.00");
        row.setProperty("total_amount", "50025.00");
        row.setProperty("transaction_date", "2026-01-15");
        row.setProperty("type", "BUY");
        row.setProperty("ticker", "AAPL");
        row.setProperty("description", "Apple Inc. Common Stock");
        row.setProperty("quantity", "100");
        row.setProperty("price", "500.00");
        row.setProperty("fee", "25.00");
        row.setProperty("reference", "SEC-REF-001");
        row.setProperty("customer_id", "CUST-002");
        return row;
    }

    public static FormRow statementRow(String stmtId, String accountType) {
        FormRow row = new FormRow();
        row.setId(stmtId);
        row.setProperty("status", "new");
        row.setProperty("account_type", accountType);
        row.setProperty("bank", "BARCGB22");
        row.setProperty("from_date", "2026-01-01");
        row.setProperty("to_date", "2026-01-31");
        return row;
    }

    public static FormRow currencyRow(String code, String name, String status) {
        FormRow row = new FormRow();
        row.setId(code);
        row.setProperty("code", code);
        row.setProperty("name", name);
        row.setProperty("status", status);
        row.setProperty("decimal_places", "2");
        row.setProperty("symbol", code.equals("EUR") ? "€" : "$");
        return row;
    }

    public static FormRow counterpartyRow(String id, String counterpartyId, String type,
                                          String bankId, boolean active) {
        FormRow row = new FormRow();
        row.setId(id);
        row.setProperty("counterpartyId", counterpartyId);
        row.setProperty("counterpartyType", type);
        row.setProperty("bankId", bankId);
        row.setProperty("isActive", String.valueOf(active));
        row.setProperty("shortCode", counterpartyId.substring(0, Math.min(4, counterpartyId.length())));
        return row;
    }

    public static FormRow customerRow(String id, String name, String code, String status) {
        FormRow row = new FormRow();
        row.setId(id);
        row.setProperty("name", name);
        row.setProperty("customer_code", code);
        row.setProperty("status", status);
        row.setProperty("kycStatus", "completed");
        row.setProperty("customer_type", "corporate");
        return row;
    }

    public static FormRow fxRateRow(String currency, String date, String rate, String status) {
        FormRow row = new FormRow();
        row.setId(UUID.randomUUID().toString());
        row.setProperty("targetCurrency", currency);
        row.setProperty("effectiveDate", date);
        row.setProperty("exchangeRate", rate);
        row.setProperty("status", status);
        row.setProperty("rateType", "spot");
        row.setProperty("importSource", "test");
        return row;
    }

    public static FormRow f14RuleRow(String id, String counterpartyId, String sourceType,
                                     String matchField, String operator, String matchValue,
                                     String internalType, int priority) {
        FormRow row = new FormRow();
        row.setId(id);
        row.setProperty("counterpartyId", counterpartyId);
        row.setProperty("sourceType", sourceType);
        row.setProperty("matchingField", matchField);
        row.setProperty("matchOperator", operator);
        row.setProperty("matchValue", matchValue);
        row.setProperty("internalType", internalType);
        row.setProperty("priority", String.valueOf(priority));
        row.setProperty("status", "Active");
        row.setProperty("mappingName", "Rule-" + id);
        return row;
    }

    public static FormRow f14RuleRow(String id, String counterpartyId, String sourceType,
                                     String matchField, String operator, String matchValue,
                                     String internalType, int priority,
                                     String secondaryField, String secondaryOperator, String secondaryValue) {
        FormRow row = f14RuleRow(id, counterpartyId, sourceType, matchField, operator, matchValue, internalType, priority);
        row.setProperty("secondaryField", secondaryField);
        row.setProperty("secondaryOperator", secondaryOperator);
        row.setProperty("secondaryValue", secondaryValue);
        return row;
    }

    public static FormRow assetMasterRow(String id, String ticker, String isin,
                                         String name, String category, String assetClass,
                                         String baseCurrency, String tradingStatus) {
        FormRow row = new FormRow();
        row.setId(id);
        row.setProperty("assetId", id);
        row.setProperty("ticker", ticker);
        row.setProperty("isin", isin);
        row.setProperty("name", name);
        row.setProperty("categoryCode", category);
        row.setProperty("asset_class", assetClass);
        row.setProperty("tradingCurrency", baseCurrency);
        row.setProperty("tradingStatus", tradingStatus);
        return row;
    }

    // ---- FormRowSet builders ----

    public static FormRowSet rowSet(FormRow... rows) {
        FormRowSet set = new FormRowSet();
        Collections.addAll(set, rows);
        return set;
    }

    public static FormRowSet emptyRowSet() {
        return new FormRowSet();
    }

    // ---- Plugin properties ----

    public static Map<String, Object> defaultProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("maxFxRateAgeDays", "5");
        props.put("confidenceThresholdHigh", "80");
        props.put("settlementDays", "2");
        props.put("pipelineVersion", "3.0");
        return props;
    }

    // ---- Helper to add enrichment data to context ----

    public static DataContext withCounterparty(DataContext ctx, String cpId, String cpType) {
        ctx.setAdditionalDataValue("counterparty_id", cpId);
        ctx.setAdditionalDataValue("counterparty_type", cpType);
        ctx.setAdditionalDataValue("counterparty_bic", "BARCGB22");
        ctx.setAdditionalDataValue("counterparty_name", "Test Bank");
        ctx.setAdditionalDataValue("counterparty_short_code", "BARC");
        return ctx;
    }

    public static DataContext withCustomer(DataContext ctx, String custId, int confidence) {
        ctx.setAdditionalDataValue("customer_id", custId);
        ctx.setAdditionalDataValue("customer_name", "Test Customer");
        ctx.setAdditionalDataValue("customer_code", "TC01");
        ctx.setAdditionalDataValue("customer_confidence", confidence);
        ctx.setAdditionalDataValue("customer_identification_method", "DIRECT_ID");
        return ctx;
    }

    public static DataContext withF14(DataContext ctx, String internalType, String ruleId) {
        ctx.setAdditionalDataValue("internal_type", internalType);
        ctx.setAdditionalDataValue("f14_rule_id", ruleId);
        ctx.setAdditionalDataValue("f14_rule_name", "Test Rule");
        return ctx;
    }

    public static DataContext withFx(DataContext ctx, double rate, String rateDate) {
        ctx.setAdditionalDataValue("fx_rate", rate);
        ctx.setAdditionalDataValue("fx_rate_date", rateDate);
        ctx.setAdditionalDataValue("fx_rate_source", "test");
        ctx.setBaseAmount(String.valueOf(Double.parseDouble(ctx.getAmount()) * rate));
        return ctx;
    }

    public static DataContext withAsset(DataContext ctx, String assetId, String isin) {
        ctx.setAdditionalDataValue("asset_id", assetId);
        ctx.setAdditionalDataValue("asset_isin", isin);
        ctx.setAdditionalDataValue("asset_category", "equity");
        ctx.setAdditionalDataValue("asset_class", "common_stock");
        ctx.setAdditionalDataValue("asset_base_currency", "USD");
        ctx.setAdditionalDataValue("currency_mismatch_flag", "no");
        return ctx;
    }

    public static DataContext fullyEnrichedBankContext() {
        DataContext ctx = bankContext();
        withCounterparty(ctx, "CPT0143", "Bank");
        withCustomer(ctx, "CUST-001", 100);
        withF14(ctx, "WIRE_TRANSFER", "RULE-001");
        withFx(ctx, 1.0, "2026-01-15");
        ctx.setProcessingStatus("fx_converted");
        return ctx;
    }

    public static DataContext fullyEnrichedSecuContext() {
        DataContext ctx = secuContext();
        withCounterparty(ctx, "CPT0200", "Custodian");
        withCustomer(ctx, "CUST-002", 100);
        withAsset(ctx, "ASSET-001", "US0378331005");
        withF14(ctx, "EQUITY_BUY", "RULE-002");
        withFx(ctx, 0.92, "2026-01-15");
        // base_fee is normally computed by FXConversionStep; set for persister tests
        ctx.setAdditionalDataValue("base_fee", "23");
        ctx.setProcessingStatus("fx_converted");
        return ctx;
    }
}
