package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.framework.AbstractDataStep;
import com.fiscaladmin.gam.enrichrows.framework.StepResult;
import com.fiscaladmin.gam.enrichrows.framework.DataContext;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 5.5: Loan Contract Resolution (Bank only)
 *
 * Links loan-related bank transactions to their source loan contracts.
 * Three-tier resolution:
 *   Tier 1: Extract contract number from description → lookup loan_master
 *   Tier 2: Match by other_side_account → lookup loan_master
 *   Tier 3: Auto-register DRAFT loan if contract number extracted but no match
 *
 * Non-blocking: transactions without loan linkage continue normally.
 */
public class LoanResolutionStep extends AbstractDataStep {

    private static final String CLASS_NAME = LoanResolutionStep.class.getName();

    @Override
    public String getStepName() {
        return "LoanResolution";
    }

    // Regex patterns for Estonian loan contract references, most-specific-first
    private static final Pattern[] CONTRACT_PATTERNS = {
        // "Laenulepingu 1315-2020 osamakse" — hyphen format with prefix
        Pattern.compile("(?i)(?:laenulepingu?|leping)\\s+([\\d]+[-][\\d]{2,4})"),

        // "laenuleping 1314/2020" — slash format with prefix
        Pattern.compile("(?i)(?:laenulepingu?|leping)\\s+([\\d]+/[\\d]{2,4})"),

        // "Leping 315001" or "leping 110401" — pure numeric with prefix
        Pattern.compile("(?i)(?:laenulepingu?|leping)\\s+([\\d]{4,})"),

        // "avaldusele #211" or "avalduse alusel #228" — application reference
        Pattern.compile("(?i)avaldus\\S*\\s+#(\\d+)"),

        // "laen 112701" — loan + number
        Pattern.compile("(?i)laen\\s+([\\d]{4,})")
    };

    @Override
    public boolean shouldExecute(DataContext context) {
        // Only bank transactions. Secu transactions don't have loan contracts.
        return "bank".equals(context.getSourceType())
            && (context.getErrorMessage() == null || context.getErrorMessage().isEmpty());
    }

    @Override
    protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
        String description = context.getPaymentDescription();

        // Tier 1: Extract contract number from description
        String contractNumber = extractContractNumber(description);

        FormRow loanRow = null;

        if (contractNumber != null) {
            loanRow = lookupByContractNumber(contractNumber, formDataDao);
            if (loanRow != null) {
                setResolved(context, loanRow, "CONTRACT_NUMBER");
                return new StepResult(true,
                        "Loan resolved by contract number: " + contractNumber);
            }
        }

        // Tier 2: Lookup by other_side_account
        String otherSideAccount = context.getOtherSideAccount();
        if (otherSideAccount != null && !otherSideAccount.isEmpty()) {
            loanRow = lookupByAccount(otherSideAccount, formDataDao);
            if (loanRow != null) {
                setResolved(context, loanRow, "ACCOUNT_NUMBER");
                return new StepResult(true,
                        "Loan resolved by account: " + otherSideAccount);
            }
        }

        // Tier 3: Auto-register if we extracted a contract number
        if (contractNumber != null) {
            try {
                loanRow = autoRegisterLoan(contractNumber, context, formDataDao);
                setResolved(context, loanRow, "AUTO_REGISTERED");
                return new StepResult(true,
                        "Loan auto-registered: " + loanRow.getId());
            } catch (Exception e) {
                LogUtil.warn(CLASS_NAME,
                        "Auto-registration failed for txn " + context.getTransactionId()
                        + ": " + e.getMessage());
            }
        }

        // No resolution — not a loan transaction or insufficient data
        return new StepResult(true, "No loan contract resolved");
    }

    /**
     * Try all patterns against the description. Return the first match.
     * Returns null if no contract number found.
     */
    String extractContractNumber(String description) {
        if (description == null || description.isEmpty()) return null;

        for (Pattern p : CONTRACT_PATTERNS) {
            Matcher m = p.matcher(description);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return null;
    }

    /**
     * Tier 1: Lookup loan by contract number (ACTIVE only).
     */
    private FormRow lookupByContractNumber(String contractNumber, FormDataDao formDataDao) {
        if (contractNumber == null) return null;

        String condition = "WHERE c_contract_number = ? AND c_status = 'ACTIVE'";
        FormRowSet rows = formDataDao.find(null,
                DomainConstants.TABLE_LOAN_MASTER,
                condition,
                new String[] { contractNumber },
                null, false, 0, 1);

        return (rows != null && !rows.isEmpty()) ? rows.get(0) : null;
    }

    /**
     * Tier 2: Lookup loan by counterparty account number (ACTIVE only).
     */
    private FormRow lookupByAccount(String otherSideAccount, FormDataDao formDataDao) {
        if (otherSideAccount == null || otherSideAccount.isEmpty()) return null;

        String condition = "WHERE c_counterparty_account = ? AND c_status = 'ACTIVE'";
        FormRowSet rows = formDataDao.find(null,
                DomainConstants.TABLE_LOAN_MASTER,
                condition,
                new String[] { otherSideAccount },
                null, false, 0, 1);

        return (rows != null && !rows.isEmpty()) ? rows.get(0) : null;
    }

    /**
     * Tier 3: Auto-register a DRAFT loan from bank statement data.
     */
    private FormRow autoRegisterLoan(String contractNumber, DataContext context,
                                     FormDataDao formDataDao) {
        FormRow loanRow = new FormRow();

        String loanId = "LN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        loanRow.setId(loanId);

        // Contract number from description extraction
        loanRow.setProperty("contract_number", contractNumber);

        // Counterparty info from statement fields
        loanRow.setProperty("counterparty_account", nvl(context.getOtherSideAccount()));
        loanRow.setProperty("counterparty_name", nvl(context.getOtherSideName()));
        loanRow.setProperty("counterparty_bic", nvl(context.getOtherSideBic()));

        // Best-effort fields
        loanRow.setProperty("contract_currency",
                context.getCurrency() != null ? context.getCurrency() : "EUR");
        loanRow.setProperty("direction", inferDirection(context));

        // Status and source
        loanRow.setProperty("status", "DRAFT");
        loanRow.setProperty("source", "AUTO_REGISTERED");
        loanRow.setProperty("registration_note",
                String.format("Auto-registered from bank txn %s. " +
                        "Contract number '%s' extracted from description: '%s'. " +
                        "Other side: %s (%s).",
                        context.getTransactionId(),
                        contractNumber,
                        truncate(context.getPaymentDescription(), 100),
                        nvl(context.getOtherSideName()),
                        nvl(context.getOtherSideAccount())));

        FormRowSet rowSet = new FormRowSet();
        rowSet.add(loanRow);
        formDataDao.saveOrUpdate(null, DomainConstants.TABLE_LOAN_MASTER, rowSet);

        LogUtil.info(CLASS_NAME, String.format(
                "Auto-registered loan %s: contract='%s', counterparty='%s', direction=%s",
                loanId, contractNumber,
                nvl(context.getOtherSideName()),
                inferDirection(context)));

        return loanRow;
    }

    /**
     * Infer loan direction from the transaction's internal_type.
     *
     * LENDER = fund has lent money out (receives interest income, principal repayments)
     * BORROWER = fund has borrowed money (pays interest expense, makes repayments)
     */
    String inferDirection(DataContext context) {
        Map<String, Object> data = context.getAdditionalData();
        String internalType = data != null ? (String) data.get("internal_type") : null;

        if (internalType != null) {
            switch (internalType) {
                case "INT_INCOME":
                case "LOAN_PAYMENT":
                    return "LENDER";
                case "INT_EXPENSE":
                case "MGMT_FEE":
                    return "BORROWER";
                default:
                    break;
            }
        }

        return null;
    }

    private void setResolved(DataContext context, FormRow loanRow, String method) {
        context.setAdditionalDataValue("loan_id", loanRow.getId());
        context.setAdditionalDataValue("loan_direction", loanRow.getProperty("direction"));
        context.setAdditionalDataValue("loan_resolution_method", method);

        LogUtil.info(CLASS_NAME, String.format(
                "Loan resolved: txn=%s → loan=%s (method=%s, direction=%s)",
                context.getTransactionId(),
                loanRow.getId(),
                method,
                loanRow.getProperty("direction")));
    }

    private static String nvl(String value) {
        return value != null ? value : "";
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() > maxLen ? value.substring(0, maxLen) + "..." : value;
    }
}
