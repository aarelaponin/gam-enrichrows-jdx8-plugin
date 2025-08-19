package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;
import com.fiscaladmin.gam.enrichrows.framework.AbstractDataStep;
import com.fiscaladmin.gam.enrichrows.framework.StepResult;
import com.fiscaladmin.gam.enrichrows.framework.DataContext;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import java.util.*;
import java.text.SimpleDateFormat;
import java.text.DecimalFormat;

/**
 * Step 7: FX Conversion (Calculate Base Currency Equivalent)
 *
 * For non-EUR transactions, retrieves the appropriate FX rate based on transaction date.
 * Calculates EUR equivalent amount.
 * If rate is missing or older than 5 days, creates an exception.
 *
 * FX Rate Selection Logic:
 * - Securities transactions: Use trade date rate
 * - Bank transactions: Use payment date rate
 * - Dividends: Use ex-date rate
 * - Income: Use payment date rate
 *
 * This step is critical for multi-currency consolidation and reporting.
 * EUR transactions skip FX conversion as EUR is the base currency.
 */
public class FXConversionStep extends AbstractDataStep {

    private static final String CLASS_NAME = FXConversionStep.class.getName();

    // Table name for FX rates - updated to match your form
    private static final String TABLE_FX_RATES = "fx_rates_eur";

    // Base currency constant - now using from Constants
    private static final String BASE_CURRENCY = DomainConstants.BASE_CURRENCY;

    // Maximum age for FX rates (in days)
    private static final int MAX_RATE_AGE_DAYS = 5;

    // Date format for FX rate lookups
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    // Decimal format for amounts
    private static final DecimalFormat AMOUNT_FORMAT = new DecimalFormat("#.##");

    @Override
    public String getStepName() {
        return "FX Conversion";
    }

    @Override
    protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
        try {
            LogUtil.info(CLASS_NAME,
                    "Starting FX conversion for transaction: " + context.getTransactionId());

            String currency = context.getCurrency();

            // Check if currency is null or empty
            if (currency == null || currency.trim().isEmpty()) {
                LogUtil.error(CLASS_NAME, null,
                        "No currency specified for transaction: " + context.getTransactionId());

                createFXException(context, formDataDao,
                        "MISSING_CURRENCY",
                        "Currency not specified for transaction",
                        "high");

                return new StepResult(false, "FX conversion failed: No currency specified");
            }

            currency = currency.trim().toUpperCase();

            // Skip FX conversion for EUR transactions
            if (BASE_CURRENCY.equals(currency)) {
                LogUtil.info(CLASS_NAME,
                        "Transaction is in base currency (EUR), no FX conversion needed");

                // Set base amount same as original amount
                String amount = context.getAmount();
                updateContextWithBaseAmount(context, amount, amount, BASE_CURRENCY,
                        1.0, null, "BASE_CURRENCY", formDataDao);

                return new StepResult(true, "No FX conversion needed - transaction in EUR");
            }

            // Determine the appropriate date for FX rate lookup
            String fxDate = determineFXDate(context);
            if (fxDate == null || fxDate.trim().isEmpty()) {
                LogUtil.error(CLASS_NAME, null,
                        "Could not determine FX date for transaction: " + context.getTransactionId());

                createFXException(context, formDataDao,
                        "INVALID_FX_DATE",
                        "Could not determine appropriate date for FX rate lookup",
                        "high");

                return new StepResult(false, "FX conversion failed: Invalid date");
            }

            LogUtil.info(CLASS_NAME,
                    String.format("Looking up FX rate for %s to EUR on %s", currency, fxDate));

            // Lookup FX rate
            FXRateInfo rateInfo = lookupFXRate(currency, BASE_CURRENCY, fxDate, formDataDao);

            if (rateInfo == null) {
                LogUtil.warn(CLASS_NAME,
                        String.format("No FX rate found for %s to EUR on %s", currency, fxDate));

                // Try to find most recent rate within acceptable range
                rateInfo = findRecentFXRate(currency, BASE_CURRENCY, fxDate, formDataDao);
            }

            // Check if we found a valid rate
            if (rateInfo != null && rateInfo.isValid()) {
                // Calculate base currency amount
                double originalAmount = parseAmount(context.getAmount());
                double baseAmount = originalAmount * rateInfo.getRate();

                String baseAmountStr = AMOUNT_FORMAT.format(baseAmount);

                LogUtil.info(CLASS_NAME,
                        String.format("FX conversion: %s %s = %s EUR (Rate: %f, Date: %s)",
                                context.getAmount(), currency, baseAmountStr,
                                rateInfo.getRate(), rateInfo.getRateDate()));

                // Update context with base amount and FX details
                updateContextWithBaseAmount(context, context.getAmount(), baseAmountStr,
                        currency, rateInfo.getRate(), rateInfo.getRateDate(),
                        rateInfo.getSource(), formDataDao);

                // Check if rate is old and create warning
                if (rateInfo.getAgeDays() > 0) {
                    createFXException(context, formDataDao,
                            "OLD_FX_RATE",
                            String.format("Using FX rate from %s (%d days old)",
                                    rateInfo.getRateDate(), rateInfo.getAgeDays()),
                            "low");
                }

                // Update processing status
                context.setProcessingStatus(DomainConstants.PROCESSING_STATUS_FX_CONVERTED);
                context.addProcessedStep(DomainConstants.PROCESSING_STATUS_FX_CONVERTED);

                // Create audit log
                createAuditLog(context, formDataDao,
                        DomainConstants.AUDIT_BASE_CURRENCY_CALCULATED,
                        String.format("FX conversion applied: %s %s = %s EUR (Rate: %f)",
                                context.getAmount(), currency, baseAmountStr, rateInfo.getRate()));

                return new StepResult(true,
                        String.format("FX conversion successful: %s EUR", baseAmountStr));

            } else {
                // No valid rate found - create exception
                LogUtil.error(CLASS_NAME, null,
                        String.format("No valid FX rate for %s to EUR within %d days of %s",
                                currency, MAX_RATE_AGE_DAYS, fxDate));

                createFXException(context, formDataDao,
                        DomainConstants.EXCEPTION_FX_RATE_MISSING,
                        String.format("No FX rate available for %s to EUR on %s (max age: %d days)",
                                currency, fxDate, MAX_RATE_AGE_DAYS),
                        "high");

                // Set a placeholder base amount to allow processing to continue
                updateContextWithBaseAmount(context, context.getAmount(), "0.00",
                        currency, 0.0, null, "MISSING", formDataDao);

                return new StepResult(true,
                        "FX rate missing - exception created, continuing with placeholder");
            }

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Unexpected error in FX conversion for transaction: " + context.getTransactionId());

            createFXException(context, formDataDao,
                    "FX_CONVERSION_ERROR",
                    "Error during FX conversion: " + e.getMessage(),
                    "high");

            return new StepResult(false, "FX conversion error: " + e.getMessage());
        }
    }

    @Override
    public boolean shouldExecute(DataContext context) {
        // Execute for all transactions that haven't failed yet
        return context.getErrorMessage() == null || context.getErrorMessage().isEmpty();
    }

    /**
     * Determine the appropriate date for FX rate lookup based on transaction type
     */
    private String determineFXDate(DataContext context) {
        String transactionDate = context.getTransactionDate();
        String internalType = getInternalTypeFromContext(context);

        // For securities transactions
        if (DomainConstants.SOURCE_TYPE_SECU.equals(context.getSourceType())) {
            // Use trade date for most securities transactions
            return transactionDate;
        }

        // For bank transactions
        if (DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType())) {
            // Check if it's a dividend or income type
            if (internalType != null) {
                if (internalType.contains("DIV") || internalType.contains("INCOME")) {
                    // Use payment date for dividends and income
                    return transactionDate;
                }
            }
            // Use payment date for bank transactions
            return transactionDate;
        }

        // Default to transaction date
        return transactionDate;
    }

    /**
     * Get internal type from context (set by F14 rule mapping)
     */
    private String getInternalTypeFromContext(DataContext context) {
        Map<String, Object> additionalData = context.getAdditionalData();
        if (additionalData != null) {
            Object internalType = additionalData.get("internal_type");
            if (internalType != null) {
                return internalType.toString();
            }
        }
        return null;
    }

    /**
     * Lookup FX rate for a specific date
     * Updated to match the fx_rates_eur table structure
     */
    private FXRateInfo lookupFXRate(String fromCurrency, String toCurrency,
                                    String rateDate, FormDataDao formDataDao) {
        try {
            // Since the table stores rates against EUR, we need to handle this differently
            // If toCurrency is EUR, we look for fromCurrency
            // If fromCurrency is EUR, we look for toCurrency (and will use 1/rate)

            String targetCurrency;
            boolean needInverse = false;

            if (BASE_CURRENCY.equals(toCurrency)) {
                // Looking for XXX to EUR rate
                targetCurrency = fromCurrency;
                needInverse = false;
            } else if (BASE_CURRENCY.equals(fromCurrency)) {
                // Looking for EUR to XXX rate
                targetCurrency = toCurrency;
                needInverse = true;
            } else {
                // Cross-currency rate not directly supported
                LogUtil.warn(CLASS_NAME,
                        "Cross-currency rate requested: " + fromCurrency + " to " + toCurrency);
                return null;
            }

            LogUtil.info(CLASS_NAME,
                    "Looking up FX rate for " + targetCurrency + " against EUR on " + rateDate);

            // Load all FX rates
            FormRowSet rateRows = formDataDao.find(
                    null,
                    TABLE_FX_RATES,
                    null,  // Load all and filter in memory
                    null,
                    null,
                    null,
                    null,
                    null
            );

            if (rateRows != null && !rateRows.isEmpty()) {
                for (FormRow row : rateRows) {
                    String effectiveDateStr = row.getProperty("effectiveDate");
                    String currency = row.getProperty("targetCurrency");
                    String status = row.getProperty("status");

                    // Check if rate is active
                    if (!"active".equalsIgnoreCase(status)) {
                        continue;
                    }

                    // Check if date and currency match
                    if (rateDate.equals(effectiveDateStr) && targetCurrency.equals(currency)) {
                        // Get the exchange rate (1 EUR = X target currency)
                        String exchangeRateStr = row.getProperty("exchangeRate");
                        if (exchangeRateStr == null || exchangeRateStr.isEmpty()) {
                            exchangeRateStr = row.getProperty("midRate");
                        }

                        if (exchangeRateStr != null && !exchangeRateStr.isEmpty()) {
                            double rate = Double.parseDouble(exchangeRateStr);

                            // If we need EUR to XXX rate, we already have it
                            // If we need XXX to EUR rate, we need to invert it
                            if (!needInverse) {
                                rate = 1.0 / rate;  // Convert to XXX -> EUR rate
                            }

                            String source = row.getProperty("importSource");
                            if (source == null || source.isEmpty()) {
                                source = "manual";
                            }

                            String rateType = row.getProperty("rateType");
                            if (rateType == null || rateType.isEmpty()) {
                                rateType = "spot";
                            }

                            LogUtil.info(CLASS_NAME,
                                    String.format("Found FX rate: %s to %s = %f on %s (Source: %s)",
                                            fromCurrency, toCurrency, rate, rateDate, source));

                            return new FXRateInfo(rate, rateDate, source, rateType, 0);
                        }
                    }
                }
            }

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error looking up FX rate for " + fromCurrency + " to " + toCurrency);
        }

        return null;
    }

    /**
     * Find the most recent FX rate within acceptable age
     * Updated to match the fx_rates_eur table structure
     */
    private FXRateInfo findRecentFXRate(String fromCurrency, String toCurrency,
                                        String targetDate, FormDataDao formDataDao) {
        try {
            // Determine target currency and if we need inverse
            String targetCurrency;
            boolean needInverse = false;

            if (BASE_CURRENCY.equals(toCurrency)) {
                targetCurrency = fromCurrency;
                needInverse = false;
            } else if (BASE_CURRENCY.equals(fromCurrency)) {
                targetCurrency = toCurrency;
                needInverse = true;
            } else {
                LogUtil.warn(CLASS_NAME,
                        "Cross-currency rate not supported: " + fromCurrency + " to " + toCurrency);
                return null;
            }

            LogUtil.info(CLASS_NAME,
                    String.format("Searching for recent FX rate for %s against EUR within %d days of %s",
                            targetCurrency, MAX_RATE_AGE_DAYS, targetDate));

            Date target = DATE_FORMAT.parse(targetDate);
            FXRateInfo bestRate = null;
            int bestAgeDays = Integer.MAX_VALUE;

            // Load all FX rates
            FormRowSet rateRows = formDataDao.find(
                    null,
                    TABLE_FX_RATES,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            if (rateRows != null && !rateRows.isEmpty()) {
                for (FormRow row : rateRows) {
                    String effectiveDateStr = row.getProperty("effectiveDate");
                    String currency = row.getProperty("targetCurrency");
                    String status = row.getProperty("status");

                    // Check if rate is active
                    if (!"active".equalsIgnoreCase(status)) {
                        continue;
                    }

                    // Check currency match
                    if (targetCurrency.equals(currency)) {
                        try {
                            Date rateDate = DATE_FORMAT.parse(effectiveDateStr);

                            // Check if rate is before or on target date
                            if (!rateDate.after(target)) {
                                // Calculate age in days
                                long diffMs = target.getTime() - rateDate.getTime();
                                int ageDays = (int) (diffMs / (1000 * 60 * 60 * 24));

                                // Check if within acceptable age
                                if (ageDays <= MAX_RATE_AGE_DAYS && ageDays < bestAgeDays) {
                                    String exchangeRateStr = row.getProperty("exchangeRate");
                                    if (exchangeRateStr == null || exchangeRateStr.isEmpty()) {
                                        exchangeRateStr = row.getProperty("midRate");
                                    }

                                    if (exchangeRateStr != null && !exchangeRateStr.isEmpty()) {
                                        double rate = Double.parseDouble(exchangeRateStr);

                                        // Adjust rate based on direction
                                        if (!needInverse) {
                                            rate = 1.0 / rate;  // Convert to XXX -> EUR rate
                                        }

                                        String source = row.getProperty("importSource");
                                        if (source == null || source.isEmpty()) {
                                            source = "manual";
                                        }

                                        String rateType = row.getProperty("rateType");
                                        if (rateType == null || rateType.isEmpty()) {
                                            rateType = "spot";
                                        }

                                        bestRate = new FXRateInfo(rate, effectiveDateStr, source, rateType, ageDays);
                                        bestAgeDays = ageDays;

                                        LogUtil.info(CLASS_NAME,
                                                String.format("Found recent FX rate: %s to %s = %f on %s (%d days old)",
                                                        fromCurrency, toCurrency, rate, effectiveDateStr, ageDays));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            LogUtil.warn(CLASS_NAME,
                                    "Could not parse rate date: " + effectiveDateStr);
                        }
                    }
                }
            }

            return bestRate;

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error finding recent FX rate for " + fromCurrency + " to " + toCurrency);
        }

        return null;
    }

    /**
     * Get rate value from FX rate row
     * Updated to match the fx_rates_eur table structure
     */
    private double getRate(FormRow row, String rateType) {
        String rateStr = null;

        // Try different rate field names based on your form
        if ("mid".equalsIgnoreCase(rateType)) {
            rateStr = row.getProperty("midRate");
            if (rateStr == null || rateStr.isEmpty()) {
                rateStr = row.getProperty("exchangeRate");
            }
        } else if ("bid".equalsIgnoreCase(rateType)) {
            rateStr = row.getProperty("bidRate");
        } else if ("ask".equalsIgnoreCase(rateType)) {
            rateStr = row.getProperty("askRate");
        }

        // Default to exchangeRate if not found
        if (rateStr == null || rateStr.isEmpty()) {
            rateStr = row.getProperty("exchangeRate");
        }

        if (rateStr != null && !rateStr.isEmpty()) {
            try {
                return Double.parseDouble(rateStr);
            } catch (NumberFormatException e) {
                LogUtil.error(CLASS_NAME, e, "Could not parse rate: " + rateStr);
            }
        }

        return 0.0;
    }

    /**
     * Update transaction context with base currency amount and FX details
     */
    private void updateContextWithBaseAmount(DataContext context, String originalAmount,
                                             String baseAmount, String originalCurrency,
                                             double fxRate, String rateDate, String rateSource,
                                             FormDataDao formDataDao) {
        // Get or create additional data map
        Map<String, Object> additionalData = context.getAdditionalData();
        if (additionalData == null) {
            additionalData = new HashMap<>();
            context.setAdditionalData(additionalData);
        }

        // Store FX conversion details
        additionalData.put("original_amount", originalAmount);
        additionalData.put("original_currency", originalCurrency);
        additionalData.put("base_amount", baseAmount);
        additionalData.put("base_currency", BASE_CURRENCY);
        additionalData.put("fx_rate", fxRate);
        additionalData.put("fx_rate_date", rateDate);
        additionalData.put("fx_rate_source", rateSource);

        // Calculate and store amounts for different transaction components
        if (DomainConstants.SOURCE_TYPE_SECU.equals(context.getSourceType())) {
            // For securities, also convert fee if present
            String feeStr = context.getFee();
            if (feeStr != null && !feeStr.isEmpty()) {
                try {
                    double fee = parseAmount(feeStr);
                    double baseFee = fee * fxRate;
                    additionalData.put("base_fee", AMOUNT_FORMAT.format(baseFee));
                } catch (Exception e) {
                    LogUtil.warn(CLASS_NAME, "Could not convert fee: " + feeStr);
                }
            }

            // Calculate total amount in base currency
            String totalAmountStr = context.getAmount();
            if (totalAmountStr != null && !totalAmountStr.isEmpty()) {
                try {
                    double totalAmount = parseAmount(totalAmountStr);
                    double baseTotalAmount = totalAmount * fxRate;
                    additionalData.put("base_total_amount", AMOUNT_FORMAT.format(baseTotalAmount));
                } catch (Exception e) {
                    LogUtil.warn(CLASS_NAME, "Could not convert total amount: " + totalAmountStr);
                }
            }
        }

        // Store in context
        context.setBaseAmount(baseAmount);

        LogUtil.info(CLASS_NAME,
                String.format("Context updated with base amount: %s %s = %s %s (Rate: %f)",
                        originalAmount, originalCurrency, baseAmount, BASE_CURRENCY, fxRate));
    }

    /**
     * Create exception record for FX conversion issues
     */
    private void createFXException(DataContext context, FormDataDao formDataDao,
                                   String exceptionType, String exceptionDetails,
                                   String priority) {
        try {
            FormRow exceptionRow = new FormRow();

            // Generate unique ID for the exception
            String exceptionId = UUID.randomUUID().toString();
            exceptionRow.setId(exceptionId);

            // Set exception identifiers
            exceptionRow.setProperty("transaction_id", context.getTransactionId());
            exceptionRow.setProperty("statement_id", context.getStatementId());
            exceptionRow.setProperty("source_type", context.getSourceType());

            // Set exception details
            exceptionRow.setProperty("exception_type", exceptionType);
            exceptionRow.setProperty("exception_details", exceptionDetails);
            exceptionRow.setProperty("exception_date", new Date().toString());

            // Set transaction details for reference
            exceptionRow.setProperty("amount", context.getAmount());
            exceptionRow.setProperty("currency", context.getCurrency());
            exceptionRow.setProperty("transaction_date", context.getTransactionDate());

            // Set priority
            exceptionRow.setProperty("priority", priority);

            // Set status
            exceptionRow.setProperty("status", FrameworkConstants.STATUS_PENDING);

            // Additional FX context
            String internalType = getInternalTypeFromContext(context);
            if (internalType != null) {
                exceptionRow.setProperty("internal_type", internalType);
            }

            // Set assignment based on priority
            if ("high".equals(priority)) {
                exceptionRow.setProperty("assigned_to", "fx_specialist");
                exceptionRow.setProperty("due_date", calculateDueDate(1));
            } else if ("medium".equals(priority)) {
                exceptionRow.setProperty("assigned_to", "operations");
                exceptionRow.setProperty("due_date", calculateDueDate(3));
            } else {
                exceptionRow.setProperty("assigned_to", "operations");
                exceptionRow.setProperty("due_date", calculateDueDate(7));
            }

            // Save exception
            FormRowSet rowSet = new FormRowSet();
            rowSet.add(exceptionRow);
            formDataDao.saveOrUpdate(null, DomainConstants.TABLE_EXCEPTION_QUEUE, rowSet);

            LogUtil.info(CLASS_NAME,
                    String.format("Created FX exception for transaction %s: Type=%s, Priority=%s",
                            context.getTransactionId(), exceptionType, priority));

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error creating FX exception for transaction: " + context.getTransactionId());
        }
    }

    /**
     * Calculate due date based on number of days
     */
    private String calculateDueDate(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, days);
        return DATE_FORMAT.format(cal.getTime());
    }

    /**
     * Inner class to hold FX rate information
     */
    private static class FXRateInfo {
        private final double rate;
        private final String rateDate;
        private final String source;
        private final String rateType;
        private final int ageDays;

        public FXRateInfo(double rate, String rateDate, String source,
                          String rateType, int ageDays) {
            this.rate = rate;
            this.rateDate = rateDate;
            this.source = source;
            this.rateType = rateType;
            this.ageDays = ageDays;
        }

        public double getRate() { return rate; }
        public String getRateDate() { return rateDate; }
        public String getSource() { return source; }
        public String getRateType() { return rateType; }
        public int getAgeDays() { return ageDays; }

        public boolean isValid() {
            return rate > 0 && ageDays <= MAX_RATE_AGE_DAYS;
        }
    }
}