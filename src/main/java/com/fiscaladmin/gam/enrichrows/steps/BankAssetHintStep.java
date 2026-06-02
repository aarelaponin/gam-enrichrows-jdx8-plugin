package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;
import com.fiscaladmin.gam.enrichrows.framework.AbstractDataStep;
import com.fiscaladmin.gam.enrichrows.framework.DataContext;
import com.fiscaladmin.gam.enrichrows.framework.StepResult;
import com.fiscaladmin.gam.enrichrows.util.TickerExtractor;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Step 6: Bank Asset Hint (Bank income/dividend transactions only)
 *
 * Extracts ticker hints from bank payment descriptions (e.g. "Dividends (SBLK)")
 * and resolves them against asset_master. Best-effort only — no auto-registration,
 * no exceptions for misses. Sets bank_asset_hint flag so downstream consumers
 * know the resolution is description-derived.
 *
 * Runs after F14RuleMappingStep (needs internal_type), before LoanResolutionStep.
 */
public class BankAssetHintStep extends AbstractDataStep {

    private static final String CLASS_NAME = BankAssetHintStep.class.getName();

    private static final Set<String> ELIGIBLE_TYPES = new HashSet<>(Arrays.asList(
            DomainConstants.INTERNAL_TYPE_DIV_INCOME,
            DomainConstants.INTERNAL_TYPE_INT_INCOME,
            DomainConstants.INTERNAL_TYPE_DIV_TAX,
            DomainConstants.INTERNAL_TYPE_INCOME_TAX
    ));

    @Override
    public String getStepName() {
        return "Bank Asset Hint";
    }

    @Override
    public boolean shouldExecute(DataContext context) {
        // Bank transactions only
        if (!DomainConstants.SOURCE_TYPE_BANK.equals(context.getSourceType())) {
            return false;
        }

        // Idempotency: skip if asset already resolved
        if (isFieldResolved(context, "asset_id")) {
            return false;
        }

        // No error on context
        if (context.getErrorMessage() != null && !context.getErrorMessage().isEmpty()) {
            return false;
        }

        // Must have an eligible internal_type from F14 mapping
        Map<String, Object> data = context.getAdditionalData();
        if (data == null) return false;
        String internalType = (String) data.get("internal_type");
        return internalType != null && ELIGIBLE_TYPES.contains(internalType);
    }

    @Override
    protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
        String description = context.getPaymentDescription();
        String ticker = TickerExtractor.extractFromDescription(description);

        if (ticker == null) {
            return new StepResult(true, "No ticker hint in description");
        }

        LogUtil.info(CLASS_NAME, "Extracted ticker hint '" + ticker +
                "' from bank transaction: " + context.getTransactionId());

        // Load all asset master records (same pattern as AssetResolutionStep)
        FormRowSet assetRows = formDataDao.find(
                null, DomainConstants.TABLE_ASSET_MASTER,
                null, null, null, null, null, null
        );

        if (assetRows == null) {
            assetRows = new FormRowSet();
        }

        // Exact ticker match only — no ISIN, no partial name, no auto-register
        FormRow assetRow = findByTicker(ticker, assetRows);

        if (assetRow == null) {
            LogUtil.info(CLASS_NAME, "No asset found for hint: " + ticker +
                    " (transaction: " + context.getTransactionId() + ")");
            return new StepResult(true, "No asset found for hint: " + ticker);
        }

        // Check trading status — skip silently if inactive
        String tradingStatus = assetRow.getProperty("tradingStatus");
        if (tradingStatus == null || !FrameworkConstants.STATUS_ACTIVE.equalsIgnoreCase(tradingStatus)) {
            LogUtil.info(CLASS_NAME, "Asset " + assetRow.getProperty("assetId") +
                    " is inactive (" + tradingStatus + "), skipping hint");
            return new StepResult(true, "Asset inactive, hint skipped: " + ticker);
        }

        // Store asset data using same keys as AssetResolutionStep
        setAssetData(context, assetRow);

        // Set bank asset hint flags
        context.setAdditionalDataValue("bank_asset_hint", "yes");
        context.setAdditionalDataValue("bank_asset_hint_ticker", ticker);

        // Update processing status
        context.addProcessedStep(DomainConstants.PROCESSING_STATUS_BANK_ASSET_HINT);

        // Audit log
        createAuditLog(context, formDataDao,
                DomainConstants.AUDIT_BANK_ASSET_HINT_RESOLVED,
                String.format("Bank asset hint resolved: %s → %s (ISIN: %s)",
                        ticker, assetRow.getProperty("assetId"),
                        assetRow.getProperty("isin")));

        LogUtil.info(CLASS_NAME, String.format(
                "Bank asset hint resolved for transaction %s: %s → %s",
                context.getTransactionId(), ticker, assetRow.getProperty("assetId")));

        return new StepResult(true, "Asset hint resolved: " + assetRow.getProperty("assetId"));
    }

    private FormRow findByTicker(String ticker, FormRowSet assetRows) {
        for (FormRow row : assetRows) {
            String assetTicker = row.getProperty("ticker");
            if (assetTicker != null && ticker.equalsIgnoreCase(assetTicker.trim())) {
                return row;
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
}
