package com.fiscaladmin.gam.enrichrows.steps;

import com.fiscaladmin.gam.enrichrows.constants.DomainConstants;
import com.fiscaladmin.gam.enrichrows.constants.FrameworkConstants;
import com.fiscaladmin.gam.enrichrows.framework.DataContext;
import com.fiscaladmin.gam.enrichrows.framework.StepResult;
import com.fiscaladmin.gam.enrichrows.helpers.TestDataFactory;
import org.joget.apps.form.dao.FormDataDao;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * §9b.7: Re-enrichment idempotency integration tests.
 * Verifies that steps correctly skip or re-evaluate based on pre-loaded enrichment data.
 */
public class ReEnrichmentIdempotencyTest {

    private CustomerIdentificationStep customerStep;
    private CounterpartyDeterminationStep counterpartyStep;
    private AssetResolutionStep assetStep;
    private F14RuleMappingStep f14Step;
    private LoanResolutionStep loanStep;
    private FXConversionStep fxStep;
    private FormDataDao mockDao;

    @Before
    public void setUp() {
        customerStep = new CustomerIdentificationStep();
        counterpartyStep = new CounterpartyDeterminationStep();
        assetStep = new AssetResolutionStep();
        f14Step = new F14RuleMappingStep();
        loanStep = new LoanResolutionStep();
        fxStep = new FXConversionStep();
        mockDao = Mockito.mock(FormDataDao.class);
    }

    @Test
    public void reEnrichment_preservesExistingLoanLink() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withLoan(ctx, "LN-000001", "LENDER", "CONTRACT_NUMBER");
        assertFalse("Loan step should skip when loan already resolved",
                loanStep.shouldExecute(ctx));
    }

    @Test
    public void reEnrichment_preservesExistingCustomer() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCustomer(ctx, "12345678", 100);
        assertFalse("Customer step should skip when customer already resolved",
                customerStep.shouldExecute(ctx));
    }

    @Test
    public void reEnrichment_resolvesUnknownCustomer() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCustomer(ctx, "UNKNOWN", 0);
        ctx.setCustomerId("10000001");
        ctx.setOtherSideAccount("EE123456789012345678");

        // Mock customer_account lookup: find customer by other_side_account
        FormRow accountRow = new FormRow();
        accountRow.setId("acc-1");
        accountRow.setProperty("accountNumber", "EE123456789012345678");
        accountRow.setProperty("customerId", "CUST-NEW");
        accountRow.setProperty("status", "Active");
        FormRowSet accountRows = TestDataFactory.rowSet(accountRow);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                anyString(), any(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(accountRows);

        // Mock customer master
        FormRow customerRow = TestDataFactory.customerRow("CUST-NEW", "New Customer", "NC01", "active");
        FormRowSet customerRows = TestDataFactory.rowSet(customerRow);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                anyString(), any(Object[].class), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(customerRows);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(customerRows);

        // Should re-evaluate because UNKNOWN is a sentinel
        assertTrue("Customer step should re-evaluate when UNKNOWN",
                customerStep.shouldExecute(ctx));

        StepResult result = customerStep.execute(ctx, mockDao);
        assertTrue(result.isSuccess());
    }

    @Test
    public void reEnrichment_resolvesUnclassifiedType() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");
        TestDataFactory.withF14(ctx, "UNMATCHED", null);
        ctx.setPaymentDescription("Wire transfer payment");

        // Mock F14 rules
        FormRowSet rules = TestDataFactory.rowSet(
                TestDataFactory.f14RuleRow("R1", "CPT0143", "bank",
                        "payment_description", "contains", "wire",
                        "WIRE_TRANSFER", 1)
        );
        when(mockDao.find(isNull(), eq("cp_txn_mapping"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rules);

        // Should re-evaluate because UNMATCHED is a sentinel
        assertTrue("F14 step should re-evaluate when UNMATCHED",
                f14Step.shouldExecute(ctx));

        StepResult result = f14Step.execute(ctx, mockDao);
        assertTrue(result.isSuccess());
        assertEquals("WIRE_TRANSFER", ctx.getAdditionalDataValue("internal_type"));
    }

    @Test
    public void reEnrichment_resolvesNewLoan() {
        DataContext ctx = TestDataFactory.bankContext();
        // No loan_id set
        ctx.setPaymentDescription("Laenulepingu 1315-2020 osamakse");

        // Mock loan lookup — no match for contract number
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_LOAN_CONTRACT),
                anyString(), any(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());

        assertTrue("Loan step should execute when loan_id is null",
                loanStep.shouldExecute(ctx));

        StepResult result = loanStep.execute(ctx, mockDao);
        assertTrue(result.isSuccess());
        // Should auto-register (Tier 3) since contract number was extracted but not found
        assertNotNull("Loan should be resolved", ctx.getAdditionalDataValue("loan_id"));
    }

    @Test
    public void reEnrichment_preservesF14ClassifiedType() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withF14(ctx, "INT_INCOME", "RULE-001");
        assertFalse("F14 step should skip when type is classified",
                f14Step.shouldExecute(ctx));
    }

    @Test
    public void reEnrichment_fullPipeline_convergent() {
        // All fields populated — all 6 steps should skip
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");
        TestDataFactory.withCustomer(ctx, "CUST-001", 100);
        TestDataFactory.withF14(ctx, "WIRE_TRANSFER", "RULE-001");
        TestDataFactory.withLoan(ctx, "LN-000001", "LENDER", "CONTRACT_NUMBER");
        ctx.setAdditionalDataValue("base_amount", "1000.00");

        assertFalse("Counterparty step should skip", counterpartyStep.shouldExecute(ctx));
        assertFalse("Customer step should skip", customerStep.shouldExecute(ctx));
        assertFalse("F14 step should skip", f14Step.shouldExecute(ctx));
        assertFalse("Loan step should skip", loanStep.shouldExecute(ctx));
        assertFalse("FX step should skip", fxStep.shouldExecute(ctx));
        // Asset step skips for bank transactions anyway, so also check with secu
        DataContext secuCtx = TestDataFactory.secuContext();
        TestDataFactory.withAsset(secuCtx, "ASSET-001", "US0378331005");
        assertFalse("Asset step should skip", assetStep.shouldExecute(secuCtx));
    }
}
