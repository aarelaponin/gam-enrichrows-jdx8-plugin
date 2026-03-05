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
        assertEquals("BORROWER", step.inferDirection(ctx));
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

        // Setup: loan_master has ACTIVE loan with contract_number = "315001"
        FormRow activeLoan = new FormRow();
        activeLoan.setId("LN-EXISTING");
        activeLoan.setProperty("contract_number", "315001");
        activeLoan.setProperty("direction", "LENDER");
        activeLoan.setProperty("status", "ACTIVE");

        FormRowSet rs = new FormRowSet();
        rs.add(activeLoan);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_MASTER),
                contains("c_contract_number"), any(String[].class),
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
        // Setup: loan_master has ACTIVE loan with counterparty_account
        FormRow activeLoan = new FormRow();
        activeLoan.setId("LN-ACCT");
        activeLoan.setProperty("counterparty_account", "EE123456789012");
        activeLoan.setProperty("direction", "BORROWER");
        activeLoan.setProperty("status", "ACTIVE");

        FormRowSet rs = new FormRowSet();
        rs.add(activeLoan);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_MASTER),
                contains("c_counterparty_account"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(rs);

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("LN-ACCT", ctx.getAdditionalData().get("loan_id"));
        assertEquals("ACCOUNT_NUMBER", ctx.getAdditionalData().get("loan_resolution_method"));
    }

    @Test
    public void testTier3_autoRegistered() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Leping 999999 laenu tagasimakse");
        ctx.setOtherSideAccount("EE999");
        ctx.setOtherSideName("Test Company");
        ctx.setAdditionalDataValue("internal_type", "LOAN_PAYMENT");

        // Both lookups return empty
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_MASTER),
                anyString(), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
            .thenReturn(new FormRowSet());

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertNotNull(ctx.getAdditionalData().get("loan_id"));
        String loanId = ctx.getAdditionalData().get("loan_id").toString();
        assertTrue("Loan ID should start with LN-", loanId.startsWith("LN-"));
        assertEquals("AUTO_REGISTERED", ctx.getAdditionalData().get("loan_resolution_method"));

        // Verify saveOrUpdate was called for auto-registration
        verify(mockDao).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_LOAN_MASTER), any(FormRowSet.class));
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
        verify(mockDao, never()).saveOrUpdate(isNull(), eq(DomainConstants.TABLE_LOAN_MASTER), any(FormRowSet.class));
    }

    @Test
    public void testDraftLoanNotUsedForMatching() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Leping 315001 tagasimakse");
        ctx.setAdditionalDataValue("internal_type", "LOAN_PAYMENT");

        // Tier 1 & 2: no ACTIVE loan found (the DRAFT one is excluded by the WHERE clause)
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_MASTER),
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
    // Helpers
    // =========================================================================

    private DataContext bankContextWithType(String internalType) {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setAdditionalDataValue("internal_type", internalType);
        return ctx;
    }
}
