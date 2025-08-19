package com.fiscaladmin.gam.enrichrows.framework;

import org.joget.apps.form.model.FormRow;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * Generic data context that carries data through the processing pipeline.
 * This context is passed between steps and accumulates processing results.
 * 
 * While this implementation contains GL transaction-specific fields,
 * the class can be used as-is for any domain or extended for specific needs.
 */
public class DataContext {

    // Source identification
    private String sourceType;           // "bank" or "secu"
    private String transactionId;        // Unique transaction ID
    private String statementId;          // Parent statement ID

    // Transaction rows
    private FormRow transactionRow;      // Original transaction record
    private FormRow statementRow;        // Parent statement record

    // Core transaction fields
    private String transactionDate;
    private String currency;
    private String amount;
    private String baseAmount;           // Amount converted to base currency (EUR)
    private String customerId;

    // Bank transaction specific fields
    private String paymentDate;
    private String paymentAmount;
    private String debitCredit;
    private String otherSideBic;
    private String otherSideAccount;
    private String otherSideName;
    private String paymentDescription;
    private String referenceNumber;

    // Securities transaction specific fields
    private String type;
    private String ticker;
    private String description;
    private String quantity;
    private String price;
    private String fee;
    private String totalAmount;
    private String reference;

    // Statement context
    private String statementBank;
    private String accountType;

    // Processing metadata
    private String processingStatus;
    private String errorMessage;
    private Map<String, Object> additionalData;
    private List<String> processedSteps;  // Track all steps that processed this transaction

    // Constructor
    public DataContext() {
        this.additionalData = new HashMap<>();
        this.processedSteps = new ArrayList<>();
    }

    // Getters and Setters

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getStatementId() {
        return statementId;
    }

    public void setStatementId(String statementId) {
        this.statementId = statementId;
    }

    public FormRow getTransactionRow() {
        return transactionRow;
    }

    public void setTransactionRow(FormRow transactionRow) {
        this.transactionRow = transactionRow;
    }

    public FormRow getStatementRow() {
        return statementRow;
    }

    public void setStatementRow(FormRow statementRow) {
        this.statementRow = statementRow;
    }

    public String getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(String transactionDate) {
        this.transactionDate = transactionDate;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getAmount() {
        return amount;
    }

    public void setAmount(String amount) {
        this.amount = amount;
    }

    public String getBaseAmount() {
        return baseAmount;
    }

    public void setBaseAmount(String baseAmount) {
        this.baseAmount = baseAmount;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(String paymentDate) {
        this.paymentDate = paymentDate;
    }

    public String getPaymentAmount() {
        return paymentAmount;
    }

    public void setPaymentAmount(String paymentAmount) {
        this.paymentAmount = paymentAmount;
    }

    public String getDebitCredit() {
        return debitCredit;
    }

    public void setDebitCredit(String debitCredit) {
        this.debitCredit = debitCredit;
    }

    public String getOtherSideBic() {
        return otherSideBic;
    }

    public void setOtherSideBic(String otherSideBic) {
        this.otherSideBic = otherSideBic;
    }

    public String getOtherSideAccount() {
        return otherSideAccount;
    }

    public void setOtherSideAccount(String otherSideAccount) {
        this.otherSideAccount = otherSideAccount;
    }

    public String getOtherSideName() {
        return otherSideName;
    }

    public void setOtherSideName(String otherSideName) {
        this.otherSideName = otherSideName;
    }

    public String getPaymentDescription() {
        return paymentDescription;
    }

    public void setPaymentDescription(String paymentDescription) {
        this.paymentDescription = paymentDescription;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getQuantity() {
        return quantity;
    }

    public void setQuantity(String quantity) {
        this.quantity = quantity;
    }

    public String getPrice() {
        return price;
    }

    public void setPrice(String price) {
        this.price = price;
    }

    public String getFee() {
        return fee;
    }

    public void setFee(String fee) {
        this.fee = fee;
    }

    public String getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(String totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getStatementBank() {
        return statementBank;
    }

    public void setStatementBank(String statementBank) {
        this.statementBank = statementBank;
    }

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Map<String, Object> getAdditionalData() {
        return additionalData;
    }

    public void setAdditionalData(Map<String, Object> additionalData) {
        this.additionalData = additionalData;
    }

    public List<String> getProcessedSteps() {
        return processedSteps;
    }

    public void setProcessedSteps(List<String> processedSteps) {
        this.processedSteps = processedSteps;
    }

    /**
     * Add a step to the list of processed steps
     */
    public void addProcessedStep(String stepName) {
        if (processedSteps == null) {
            processedSteps = new ArrayList<>();
        }
        if (!processedSteps.contains(stepName)) {
            processedSteps.add(stepName);
        }
    }

    /**
     * Helper method to get a value from additional data
     */
    public Object getAdditionalDataValue(String key) {
        if (additionalData != null) {
            return additionalData.get(key);
        }
        return null;
    }

    /**
     * Helper method to set a value in additional data
     */
    public void setAdditionalDataValue(String key, Object value) {
        if (additionalData == null) {
            additionalData = new HashMap<>();
        }
        additionalData.put(key, value);
    }

    /**
     * Check if data has been successfully enriched/processed
     */
    public boolean isEnriched() {
        return processingStatus != null &&
                (processingStatus.contains("enriched") ||
                        processingStatus.contains("complete"));
    }

    /**
     * Check if data processing has errors
     */
    public boolean hasError() {
        return errorMessage != null && !errorMessage.isEmpty();
    }

    @Override
    public String toString() {
        return "DataContext{" +
                "sourceType='" + sourceType + '\'' +
                ", transactionId='" + transactionId + '\'' +
                ", statementId='" + statementId + '\'' +
                ", currency='" + currency + '\'' +
                ", amount='" + amount + '\'' +
                ", baseAmount='" + baseAmount + '\'' +
                ", customerId='" + customerId + '\'' +
                ", processingStatus='" + processingStatus + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}