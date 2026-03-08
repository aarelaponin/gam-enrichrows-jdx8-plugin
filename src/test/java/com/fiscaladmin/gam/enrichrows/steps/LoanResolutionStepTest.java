package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.framework.DataContext;
import com.fiscaladmin.gam.enrichrows.framework.StepResult;
import com.fiscaladmin.gam.enrichrows.helpers.TestDataFactory;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for LoanResolutionStep.
 */
public class LoanResolutionStepTest {

    private LoanResolutionStep step;
    private FormDataDao mockDao;

    @Before
    public void setUp() {
        step = new LoanResolutionStep();
        mockDao = mock(FormDataDao.class);
    }

    // =========================================================================
    // Contract Number Extraction
    // =========================================================================

    @Test
    public void testExtract_numericWithLeping() {
        assertEquals("315001",
            step.extractContractNumber("Leping 315001 laenu tagasimakse"));
    }

    @Test
    public void testExtract_slashFormat() {
        assertEquals("1314/2020",
            step.extractContractNumber("Intress vastavalt laenuleping 1314/2020"));
    }

    @Test
    public void testExtract_hyphenFormat() {
        assertEquals("1315-2020",
            step.extractContractNumber("Laenulepingu 1315-2020 osamakse nr 18"));
    }

    @Test
    public void testExtract_applicationRef() {
        assertEquals("211",
            step.extractContractNumber("Tagastus avaldusele #211 põhiosa"));
    }

    @Test
    public void testExtract_laenPrefix() {
        assertEquals("112701",
            step.extractContractNumber("laen 112701 intress per 01.06-30.06.2024"));
    }

    @Test
    public void testExtract_noMatch() {
        assertNull(step.extractContractNumber("Securities buy 100 NVDA"));
    }

    @Test
    public void testExtract_null() {
        assertNull(step.extractContractNumber(null));
    }

    @Test
    public void testExtract_empty() {
        assertNull(step.extractContractNumber(""));
    }

    @Test
    public void testExtract_accountInterest() {
        assertNull(step.extractContractNumber(
            "Account interest 01.05.2024-31.05.2024 (rate: 1.00%)"));
    }

    // =========================================================================
    // Direction Inference
    // =========================================================================

    @Test
    public void testDirection_intIncome() {
        DataContext ctx = bankContextWithType("INT_INCOME");
        assertEquals("LENDER", step.inferDirection(ctx));
    }

    @Test
    public void testDirection_intExpense() {
        DataContext ctx = bankContextWithType("INT_EXPENSE");
        assertEquals("BORROWER", step.inferDirection(ctx));
    }

    @Test
    public void testDirection_loanPayment() {
        DataContext ctx = bankContextWithType("LOAN_PAYMENT");
        assertEquals("LENDER", step.inferDirection(ctx));
    }

    @Test
    public void testDirection_mgmtFee() {
        DataContext ctx = bankContextWithType("MGMT_FEE");
        assertNull(step.inferDirection(ctx));
    }

    @Test
    public void testDirection_loanDisbursement() {
        DataContext ctx = bankContextWithType("LOAN_DISBURSEMENT");
        assertEquals("BORROWER", step.inferDirection(ctx));
    }

    @Test
    public void testDirection_custInt() {
        DataContext ctx = bankContextWithType("CUST_INT");
        assertEquals("LENDER", step.inferDirection(ctx));
    }

    @Test
    public void testDirection_cashIn() {
        DataContext ctx = bankContextWithType("CASH_IN");
        assertNull(step.inferDirection(ctx));
    }

    @Test
    public void testDirection_noType() {
        DataContext ctx = TestDataFactory.bankContext();
        assertNull(step.inferDirection(ctx));
    }

    // =========================================================================
    // shouldExecute
    // =========================================================================

    @Test
    public void testShouldExecute_bank() {
        DataContext ctx = TestDataFactory.bankContext();
        assertTrue(step.shouldExecute(ctx));
    }

    @Test
    public void testShouldExecute_secu() {
        DataContext ctx = TestDataFactory.secuContext();
        assertFalse(step.shouldExecute(ctx));
    }

    @Test
    public void testShouldExecute_bankWithError() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setErrorMessage("some error");
        assertFalse(step.shouldExecute(ctx));
    }

    // =========================================================================
    // Resolution Tiers
    // =========================================================================

    @Test
    public void testTier1_resolvedByContractNumber() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Leping 315001 laenu tagasimakse");

        ctx.setAdditionalDataValue("internal_type", "LOAN_PAYMENT");

        // Setup: loanContract has active loan with referenceNumber = "315001"
        FormRow activeLoan = new FormRow();
        activeLoan.setId("LN-EXISTING");
        activeLoan.setProperty("referenceNumber", "315001");
        activeLoan.setProperty("status", "ls-active");

        FormRowSet rs = new FormRowSet();
        rs.add(activeLoan);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                contains("c_referenceNumber"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(rs);

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("LN-EXISTING", ctx.getAdditionalData().get("loan_id"));
        assertEquals("LENDER", ctx.getAdditionalData().get("loan_direction"));
        assertEquals("CONTRACT_NUMBER", ctx.getAdditionalData().get("loan_resolution_method"));
    }

    @Test
    public void testTier2_resolvedByAccount() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Generic transfer from client");
        ctx.setOtherSideAccount("EE123456789012");

        // No contract number match (Tier 1 skipped)
        // Setup: loanContract has active loan with account_number
        FormRow activeLoan = new FormRow();
        activeLoan.setId("LN-ACCT");
        activeLoan.setProperty("account_number", "EE123456789012");
        activeLoan.setProperty("status", "ls-active");

        FormRowSet rs = new FormRowSet();
        rs.add(activeLoan);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                contains("c_account_number"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(rs);

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("LN-ACCT", ctx.getAdditionalData().get("loan_id"));
        assertEquals("TIER_2_ACCOUNT", ctx.getAdditionalData().get("loan_resolution_method"));
    }

    @Test
    public void testTier3_autoRegistered() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Leping 999999 laenu tagasimakse");
        ctx.setOtherSideAccount("EE999");
        ctx.setOtherSideName("Test Company");
        ctx.setAdditionalDataValue("internal_type", "LOAN_PAYMENT");

        // Both lookups return empty
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                anyString(), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(new FormRowSet());

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertNotNull(ctx.getAdditionalData().get("loan_id"));
        String loanId = ctx.getAdditionalData().get("loan_id").toString();
        assertFalse("Loan ID should be UUID, not LN- prefix", loanId.startsWith("LN-"));
        assertEquals("AUTO_REGISTERED", ctx.getAdditionalData().get("loan_resolution_method"));

        // Verify saveOrUpdate was called for auto-registration
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT), any(FormRowSet.class));
    }

    @Test
    public void testNoResolution_noContractNoAccount() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Generic transfer");
        ctx.setOtherSideAccount(null);

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertNull(ctx.getAdditionalData().get("loan_id"));

        // No DB calls for loan_master
        verify(mockDao, never()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT), any(FormRowSet.class));
    }

    @Test
    public void testDraftLoanNotUsedForMatching() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Leping 315001 tagasimakse");
        ctx.setAdditionalDataValue("internal_type", "LOAN_PAYMENT");

        // Tier 1 & 2: no ACTIVE loan found (the DRAFT one is excluded by the WHERE clause)
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                anyString(), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(new FormRowSet());

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        // Should auto-register (Tier 3) since contract number was extracted
        assertEquals("AUTO_REGISTERED", ctx.getAdditionalData().get("loan_resolution_method"));
        assertEquals("LENDER", ctx.getAdditionalData().get("loan_direction"));
    }

    // =========================================================================
    // Reclassification (§3.10)
    // =========================================================================

    @Test
    public void testReclassify_unclassifiedCredit_becomesLoanPayment() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Leping 315001 makse");
        ctx.setDebitCredit("C");
        ctx.setAdditionalDataValue("internal_type", "UNCLASSIFIED");

        FormRow activeLoan = new FormRow();
        activeLoan.setId("LN-RECLASS-C");
        activeLoan.setProperty("referenceNumber", "315001");
        activeLoan.setProperty("status", "ls-active");

        FormRowSet rs = new FormRowSet();
        rs.add(activeLoan);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                contains("c_referenceNumber"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(rs);

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("LOAN_PAYMENT", ctx.getAdditionalData().get("internal_type"));
        assertEquals("LENDER", ctx.getAdditionalData().get("loan_direction"));
        assertEquals("LN-RECLASS-C", ctx.getAdditionalData().get("loan_id"));
    }

    @Test
    public void testReclassify_unclassifiedDebit_becomesLoanDisbursement() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Leping 999999 väljamakse");
        ctx.setDebitCredit("D");
        ctx.setOtherSideAccount("EE999");
        ctx.setOtherSideName("Borrower OÜ");
        ctx.setAdditionalDataValue("internal_type", "UNCLASSIFIED");

        // No active loan found — triggers Tier 3 auto-registration
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                anyString(), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(new FormRowSet());

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("LOAN_DISBURSEMENT", ctx.getAdditionalData().get("internal_type"));
        assertEquals("BORROWER", ctx.getAdditionalData().get("loan_direction"));
        assertEquals("AUTO_REGISTERED", ctx.getAdditionalData().get("loan_resolution_method"));
    }

    @Test
    public void testReclassify_doesNotOverrideF14Type() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Leping 315001 intress");
        ctx.setDebitCredit("C");
        ctx.setAdditionalDataValue("internal_type", "INT_INCOME");

        FormRow activeLoan = new FormRow();
        activeLoan.setId("LN-KEEP-TYPE");
        activeLoan.setProperty("referenceNumber", "315001");
        activeLoan.setProperty("status", "ls-active");

        FormRowSet rs = new FormRowSet();
        rs.add(activeLoan);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                contains("c_referenceNumber"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(rs);

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("INT_INCOME", ctx.getAdditionalData().get("internal_type"));
        assertEquals("LENDER", ctx.getAdditionalData().get("loan_direction"));
    }

    @Test
    public void testReclassify_unclassifiedNullDC_staysUnclassified() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Leping 315001 makse");
        ctx.setDebitCredit(null); // D/C unknown
        ctx.setAdditionalDataValue("internal_type", "UNCLASSIFIED");

        FormRow activeLoan = new FormRow();
        activeLoan.setId("LN-NO-DC");
        activeLoan.setProperty("referenceNumber", "315001");
        activeLoan.setProperty("status", "ls-active");

        FormRowSet rs = new FormRowSet();
        rs.add(activeLoan);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                contains("c_referenceNumber"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(rs);

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        // Reclassification can't determine type without D/C
        assertEquals("UNCLASSIFIED", ctx.getAdditionalData().get("internal_type"));
        assertEquals("LN-NO-DC", ctx.getAdditionalData().get("loan_id"));
        assertNull(ctx.getAdditionalData().get("loan_direction"));
    }

    @Test
    public void testReclassify_noLoanResolved_staysUnclassified() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Generic transfer");
        ctx.setDebitCredit("C");
        ctx.setAdditionalDataValue("internal_type", "UNCLASSIFIED");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("UNCLASSIFIED", ctx.getAdditionalData().get("internal_type"));
        assertNull(ctx.getAdditionalData().get("loan_id"));
    }

    // =========================================================================
    // Tier 2b — Customer Account Chain Resolution
    // =========================================================================

    @Test
    public void testTier2b_resolvedByCustomerAccount_repaidLoan() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Generic transfer from client");
        ctx.setOtherSideAccount("EE087700771001909756");
        ctx.setAdditionalDataValue("internal_type", "LOAN_PAYMENT");

        // Tier 1: no contract number in description → skip
        // Tier 2: no direct account match on loan_contract
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                contains("c_account_number"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(new FormRowSet());

        // Tier 2b Step 1: customer_account lookup → customerId=14002051
        FormRow accountRow = new FormRow();
        accountRow.setProperty("accountNumber", "EE087700771001909756");
        accountRow.setProperty("customerId", "14002051");
        accountRow.setProperty("status", "Active");

        FormRowSet accountRows = new FormRowSet();
        accountRows.add(accountRow);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                contains("c_accountNumber"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(accountRows);

        // Tier 2b Step 2: no active loan for customer
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                contains("c_customerId"), eq(new String[] { "14002051" }),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(new FormRowSet());

        // Tier 2b fallback: repaid loan found
        FormRow repaidLoan = new FormRow();
        repaidLoan.setId("loan-123003");
        repaidLoan.setProperty("customerId", "14002051");
        repaidLoan.setProperty("status", "ls-repaid");

        FormRowSet fallbackLoans = new FormRowSet();
        fallbackLoans.add(repaidLoan);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                contains("NOT IN"), eq(new String[] { "14002051" }),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(fallbackLoans);

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("loan-123003", ctx.getAdditionalData().get("loan_id"));
        assertEquals("TIER_2B_CUSTOMER", ctx.getAdditionalData().get("loan_resolution_method"));
        assertEquals("LENDER", ctx.getAdditionalData().get("loan_direction"));
    }

    @Test
    public void testTier2b_activeLoanPreferredOverRepaid() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Generic transfer from client");
        ctx.setOtherSideAccount("EE112233445566");
        ctx.setAdditionalDataValue("internal_type", "LOAN_PAYMENT");

        // Tier 2: no direct account match
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                contains("c_account_number"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(new FormRowSet());

        // Tier 2b Step 1: customer_account → customerId
        FormRow accountRow = new FormRow();
        accountRow.setProperty("accountNumber", "EE112233445566");
        accountRow.setProperty("customerId", "CUST-99");
        accountRow.setProperty("status", "Active");

        FormRowSet accountRows = new FormRowSet();
        accountRows.add(accountRow);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                contains("c_accountNumber"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(accountRows);

        // Tier 2b Step 2: active loan found for customer → should be preferred
        FormRow activeLoan = new FormRow();
        activeLoan.setId("loan-A");
        activeLoan.setProperty("customerId", "CUST-99");
        activeLoan.setProperty("status", "ls-active");

        FormRowSet activeLoans = new FormRowSet();
        activeLoans.add(activeLoan);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                contains("c_customerId"), eq(new String[] { "CUST-99" }),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(activeLoans);

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("loan-A", ctx.getAdditionalData().get("loan_id"));
        assertEquals("TIER_2B_CUSTOMER", ctx.getAdditionalData().get("loan_resolution_method"));
    }

    @Test
    public void testTier2b_accountNotInCustomerAccount() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Generic transfer");
        ctx.setOtherSideAccount("EE000000000000");

        // Tier 2: no direct account match
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                contains("c_account_number"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(new FormRowSet());

        // Tier 2b: customer_account lookup returns empty
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                contains("c_accountNumber"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(new FormRowSet());

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        // No loan resolved — falls through all tiers (no contract number → no Tier 3 either)
        assertNull(ctx.getAdditionalData().get("loan_id"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private DataContext bankContextWithType(String internalType) {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setAdditionalDataValue("internal_type", internalType);
        return ctx;
    }

    // =========================================================================
    // §9b Idempotency guard tests
    // =========================================================================

    @Test
    public void testIdempotency_skipsResolved() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withLoan(ctx, "LN-000001", "LENDER", "CONTRACT_NUMBER");
        assertFalse("Should skip when loan already resolved", step.shouldExecute(ctx));
    }

    @Test
    public void testIdempotency_reEvaluatesNull() {
        DataContext ctx = TestDataFactory.bankContext();
        // No loan_id set → should execute
        assertTrue("Should execute when loan_id is null", step.shouldExecute(ctx));
    }
}
