package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;
import com.fiscaladmin.gam.enrichrows.framework.AbstractDataStep;
import com.fiscaladmin.gam.enrichrows.framework.StepResult;
import com.fiscaladmin.gam.enrichrows.framework.DataContext;
import com.fiscaladmin.gam.framework.status.EntityType;
import com.fiscaladmin.gam.framework.status.Status;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 4: Asset Resolution (Securities only)
 *
 * Resolves ticker symbol to asset master record for securities transactions.
 * Resolution strategy:
 * 1. Exact ticker match
 * 2. ISIN match (if ticker looks like an ISIN)
 * 3. Partial name match against description
 *
 * Verifies asset trading status is active.
 * Stores asset_id, asset_isin, asset_category, asset_class,
 * asset_base_currency, and currency_mismatch_flag in additionalData.
 */
public class AssetResolutionStep extends AbstractDataStep {

    private static final String CLASS_NAME = AssetResolutionStep.class.getName();

    @Override
    public String getStepName() {
        return "Asset Resolution";
    }

    @Override
    public boolean shouldExecute(DataContext context) {
        // §9b: Skip if asset already resolved (UNKNOWN = resolved, no sentinel)
        if (isFieldResolved(context, "asset_id")) {
            return false;
        }
        // Only execute for securities transactions
        if (!DomainConstants.SOURCE_TYPE_SECU.equals(context.getSourceType())) {
            return false;
        }
        return context.getErrorMessage() == null || context.getErrorMessage().isEmpty();
    }

    @Override
    protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
        try {
            String ticker = context.getTicker();

            // Check if ticker is provided
            if (ticker == null || ticker.trim().isEmpty()) {
                LogUtil.warn(CLASS_NAME, "Missing ticker for securities transaction: " +
                        context.getTransactionId());
                createAssetException(context, formDataDao,
                        DomainConstants.EXCEPTION_MISSING_TICKER,
                        "Ticker symbol is missing for securities transaction");

                setUnknownAsset(context);
                return new StepResult(true,
                        "Missing ticker - exception created, continuing with UNKNOWN");
            }

            ticker = ticker.trim().toUpperCase();
            LogUtil.info(CLASS_NAME, "Resolving asset for ticker: " + ticker +
                    " (transaction: " + context.getTransactionId() + ")");

            // Load all asset master records
            FormRowSet assetRows = formDataDao.find(
                    null,
                    DomainConstants.TABLE_ASSET_MASTER,
                    null, null, null, null, null, null
            );

            if (assetRows == null) {
                assetRows = new FormRowSet();
            }

            // Strategy 1: Exact ticker match
            FormRow assetRow = findByTicker(ticker, assetRows);

            // Strategy 2: ISIN match
            if (assetRow == null) {
                assetRow = findByIsin(ticker, assetRows);
            }

            // Strategy 3: Partial name match using description
            if (assetRow == null && context.getDescription() != null) {
                assetRow = findByNameMatch(context.getDescription(), assetRows);
            }

            if (assetRow == null) {
                LogUtil.info(CLASS_NAME, "Auto-registering new asset for ticker: " + ticker);
                assetRow = autoRegisterAsset(ticker, context, formDataDao);
                if (assetRow == null) {
                    LogUtil.error(CLASS_NAME, null, "Failed to auto-register asset for ticker: " + ticker);
                    createAssetException(context, formDataDao,
                            DomainConstants.EXCEPTION_UNKNOWN_ASSET,
                            "No asset found and auto-registration failed for ticker: " + ticker);
                    setUnknownAsset(context);
                    return new StepResult(true,
                            "Asset auto-registration failed - continuing with UNKNOWN");
                }
                LogUtil.info(CLASS_NAME, "Auto-registered asset: " + assetRow.getId() +
                        " for ticker: " + ticker);
            }

            // Verify trading status is active
            String tradingStatus = assetRow.getProperty("tradingStatus");
            if (tradingStatus == null || !FrameworkConstants.STATUS_ACTIVE.equalsIgnoreCase(tradingStatus)) {
                LogUtil.warn(CLASS_NAME, "Asset " + assetRow.getProperty("assetId") +
                        " has inactive trading status: " + tradingStatus);
                createAssetException(context, formDataDao,
                        DomainConstants.EXCEPTION_INACTIVE_ASSET,
                        "Asset " + assetRow.getProperty("assetId") + " trading status is: " + tradingStatus);
                // Store actual asset details for reference but still create exception
                setAssetData(context, assetRow);
                return new StepResult(true,
                        "Asset inactive - exception created, continuing with actual asset data");
            }

            // Store asset information in context
            setAssetData(context, assetRow);

            // Update processing status
            context.setProcessingStatus(DomainConstants.PROCESSING_STATUS_ASSET_RESOLVED);
            context.addProcessedStep(DomainConstants.PROCESSING_STATUS_ASSET_RESOLVED);

            // Audit log
            createAuditLog(context, formDataDao,
                    DomainConstants.AUDIT_ASSET_RESOLVED,
                    String.format("Asset resolved: %s (ISIN: %s, Category: %s)",
                            assetRow.getProperty("assetId"),
                            assetRow.getProperty("isin"),
                            assetRow.getProperty("categoryCode")));

            LogUtil.info(CLASS_NAME, String.format(
                    "Asset resolved for transaction %s: %s (ISIN: %s)",
                    context.getTransactionId(), assetRow.getProperty("assetId"),
                    assetRow.getProperty("isin")));

            return new StepResult(true,
                    "Asset resolved: " + assetRow.getProperty("assetId"));

        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error resolving asset for transaction: " + context.getTransactionId());
            createAssetException(context, formDataDao,
                    DomainConstants.EXCEPTION_ASSET_RESOLUTION_ERROR,
                    "Error during asset resolution: " + e.getMessage());
            setUnknownAsset(context);
            return new StepResult(true,
                    "Asset resolution error - exception created, continuing with UNKNOWN");
        }
    }

    private FormRow findByTicker(String ticker, FormRowSet assetRows) {
        for (FormRow row : assetRows) {
            String assetTicker = row.getProperty("ticker");
            if (assetTicker != null && ticker.equalsIgnoreCase(assetTicker.trim())) {
                LogUtil.info(CLASS_NAME, "Found asset by exact ticker match: " + row.getId());
                return row;
            }
        }
        return null;
    }

    private FormRow findByIsin(String value, FormRowSet assetRows) {
        // ISIN is 12 characters: 2 letter country code + 9 alphanumeric + 1 check digit
        if (value.length() != 12) {
            return null;
        }

        for (FormRow row : assetRows) {
            String isin = row.getProperty("isin");
            if (isin != null && value.equalsIgnoreCase(isin.trim())) {
                LogUtil.info(CLASS_NAME, "Found asset by ISIN match: " + row.getId());
                return row;
            }
        }
        return null;
    }

    private FormRow findByNameMatch(String description, FormRowSet assetRows) {
        if (description == null || description.trim().isEmpty()) {
            return null;
        }

        String upperDesc = description.trim().toUpperCase();

        for (FormRow row : assetRows) {
            String name = row.getProperty("name");
            if (name != null) {
                String upperName = name.trim().toUpperCase();
                // Require substantial overlap
                if (upperDesc.contains(upperName) || upperName.contains(upperDesc)) {
                    if (Math.min(upperDesc.length(), upperName.length()) >= 5) {
                        LogUtil.info(CLASS_NAME,
                                "Found asset by name match: " + row.getId());
                        return row;
                    }
                }
            }
        }
        return null;
    }

    private void setAssetData(DataContext context, FormRow assetRow) {
        String assetBaseCurrency = assetRow.getProperty("tradingCurrency");
        boolean currencyMismatch = assetBaseCurrency != null &&
                context.getCurrency() != null &&
                !assetBaseCurrency.equalsIgnoreCase(context.getCurrency());

        Map<String, Object> data = context.getAdditionalData();
        data.put("asset_id", assetRow.getProperty("assetId"));
        data.put("asset_isin", assetRow.getProperty("isin"));
        data.put("asset_category", assetRow.getProperty("categoryCode"));
        data.put("asset_class", assetRow.getProperty("asset_class"));
        data.put("asset_base_currency", assetBaseCurrency);
        data.put("currency_mismatch_flag", currencyMismatch ? "yes" : "no");
    }

    private void setUnknownAsset(DataContext context) {
        Map<String, Object> data = context.getAdditionalData();
        data.put("asset_id", FrameworkConstants.ENTITY_UNKNOWN);
        data.put("asset_isin", FrameworkConstants.ENTITY_UNKNOWN);
        data.put("asset_category", FrameworkConstants.ENTITY_UNKNOWN);
        data.put("asset_class", FrameworkConstants.ENTITY_UNKNOWN);
        data.put("asset_base_currency", FrameworkConstants.ENTITY_UNKNOWN);
        data.put("currency_mismatch_flag", "no");
    }

    private void createAssetException(DataContext context, FormDataDao formDataDao,
                                      String exceptionType, String exceptionDetails) {
        try {
            FormRow exceptionRow = new FormRow();
            String exceptionId = UUID.randomUUID().toString();
            exceptionRow.setId(exceptionId);

            exceptionRow.setProperty("transaction_id", context.getTransactionId());
            exceptionRow.setProperty("statement_id", context.getStatementId());
            exceptionRow.setProperty("source_type", context.getSourceType());
            exceptionRow.setProperty("exception_type", exceptionType);
            exceptionRow.setProperty("exception_details", exceptionDetails);
            exceptionRow.setProperty("exception_date", new Date().toString());
            exceptionRow.setProperty("amount", context.getAmount());
            exceptionRow.setProperty("currency", context.getCurrency());
            exceptionRow.setProperty("transaction_date", context.getTransactionDate());
            exceptionRow.setProperty("ticker", context.getTicker());
            exceptionRow.setProperty("description", context.getDescription());

            // Set priority based on amount
            String priority = calculateExceptionPriority(context);
            exceptionRow.setProperty("priority", priority);

            // Set assignment based on priority
            if ("critical".equals(priority) || "high".equals(priority)) {
                exceptionRow.setProperty("assigned_to", "supervisor");
                exceptionRow.setProperty("due_date", calculateDueDate(1));
            } else if ("medium".equals(priority)) {
                exceptionRow.setProperty("assigned_to", "operations");
                exceptionRow.setProperty("due_date", calculateDueDate(3));
            } else {
                exceptionRow.setProperty("assigned_to", "operations");
                exceptionRow.setProperty("due_date", calculateDueDate(7));
            }

            exceptionRow.setProperty("status", Status.OPEN.getCode());

            FormRowSet rowSet = new FormRowSet();
            rowSet.add(exceptionRow);
            formDataDao.saveOrUpdate(null, DomainConstants.TABLE_EXCEPTION_QUEUE, rowSet);

            if (statusManager != null) {
                try {
                    statusManager.transition(formDataDao, EntityType.EXCEPTION, exceptionId,
                            Status.OPEN, "rows-enrichment", exceptionDetails);
                } catch (Exception e) {
                    LogUtil.warn(CLASS_NAME, "Could not transition exception status: " + e.getMessage());
                }
            }

            LogUtil.info(CLASS_NAME, String.format(
                    "Created asset exception for transaction %s: Type=%s",
                    context.getTransactionId(), exceptionType));
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e,
                    "Error creating asset exception for transaction: " +
                            context.getTransactionId());
        }
    }

    /**
     * Calculate exception priority based on transaction amount
     */
    private String calculateExceptionPriority(DataContext context) {
        try {
            String amountStr = context.getAmount();
            if (amountStr != null && !amountStr.trim().isEmpty()) {
                String cleaned = amountStr.replaceAll("[^0-9.-]", "");
                double amount = Math.abs(Double.parseDouble(cleaned));

                if (amount >= 1000000) {
                    return "critical";
                } else if (amount >= 100000) {
                    return "high";
                } else if (amount >= 10000) {
                    return "medium";
                } else {
                    return "low";
                }
            }
        } catch (Exception e) {
            LogUtil.warn(CLASS_NAME, "Could not parse amount for priority calculation: " +
                    context.getAmount());
        }
        return "medium";
    }

    /**
     * Calculate due date based on number of days
     */
    private String calculateDueDate(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, days);
        return new java.text.SimpleDateFormat("yyyy-MM-dd").format(cal.getTime());
    }

    /**
     * Auto-register an unknown asset in the asset_master table.
     * Infers category (bond vs equity) from the transaction description.
     */
    private FormRow autoRegisterAsset(String ticker, DataContext context, FormDataDao formDataDao) {
        try {
            FormRow assetRow = new FormRow();
            String assetId = "AST-" + UUID.randomUUID().toString().substring(0, 6);
            assetRow.setId(assetId);
            assetRow.setProperty("assetId", assetId);
            assetRow.setProperty("ticker", ticker);

            String description = context.getDescription();
            assetRow.setProperty("name", extractAssetName(description));

            // Infer category from description
            boolean isBond = false;
            if (description != null) {
                String lowerDesc = description.toLowerCase();
                isBond = lowerDesc.contains("võlakiri") || lowerDesc.contains("bond");
            }

            if (isBond) {
                assetRow.setProperty("categoryCode", "BD");
                assetRow.setProperty("asset_class", "bond");
                assetRow.setProperty("primaryExchange", "OTC");
                assetRow.setProperty("riskRating", "RISK_3");
                assetRow.setProperty("liquidityRating", "LIQ_3");

                String maturityDate = extractMaturityDate(description);
                if (maturityDate != null) {
                    assetRow.setProperty("maturityDate", maturityDate);
                }
                String couponRate = extractCouponRate(description);
                if (couponRate != null) {
                    assetRow.setProperty("couponRate", couponRate);
                }
            } else {
                assetRow.setProperty("categoryCode", "EQ");
                assetRow.setProperty("asset_class", "equity");
                assetRow.setProperty("riskRating", "RISK_3");
                assetRow.setProperty("liquidityRating", "LIQ_2");
            }

            String currency = context.getCurrency();
            assetRow.setProperty("tradingCurrency", currency != null ? currency : "USD");
            assetRow.setProperty("tradingStatus", "Active");

            FormRowSet rowSet = new FormRowSet();
            rowSet.add(assetRow);
            formDataDao.saveOrUpdate(null, DomainConstants.TABLE_ASSET_MASTER, rowSet);

            createAuditLog(context, formDataDao,
                    DomainConstants.AUDIT_ASSET_AUTO_REGISTERED,
                    "Auto-registered asset: " + assetId + " for ticker: " + ticker);

            return assetRow;
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Error auto-registering asset for ticker: " + ticker);
            return null;
        }
    }

    private String extractAssetName(String description) {
        if (description == null || description.trim().isEmpty()) {
            return "Unknown Asset";
        }
        return description.trim();
    }

    /**
     * Extract the last dd.MM.yyyy date from description and return as yyyy-MM-dd.
     */
    private String extractMaturityDate(String description) {
        if (description == null) return null;
        Matcher matcher = Pattern.compile("(\\d{2})\\.(\\d{2})\\.(\\d{4})").matcher(description);
        String lastMatch = null;
        while (matcher.find()) {
            lastMatch = matcher.group(3) + "-" + matcher.group(2) + "-" + matcher.group(1);
        }
        return lastMatch;
    }

    /**
     * Extract the first percentage value from description (e.g. "8.5%" → "8.5").
     */
    private String extractCouponRate(String description) {
        if (description == null) return null;
        Matcher matcher = Pattern.compile("(\\d+\\.?\\d*)%").matcher(description);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
