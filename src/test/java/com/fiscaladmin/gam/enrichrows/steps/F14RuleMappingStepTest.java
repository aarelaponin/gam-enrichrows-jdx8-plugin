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

public class F14RuleMappingStepTest {

    private F14RuleMappingStep step;
    private FormDataDao mockDao;

    @Before
    public void setUp() {
        step = new F14RuleMappingStep();
        mockDao = Mockito.mock(FormDataDao.class);
    }

    @Test
    public void testStepName() {
        assertEquals("F14 Rule Mapping", step.getStepName());
    }

    @Test
    public void testRuleMatch() {
        FormRowSet rules = TestDataFactory.rowSet(
                TestDataFactory.f14RuleRow("R1", "CPT0143", "bank",
                        "payment_description", "contains", "wire",
                        "WIRE_TRANSFER", 1)
        );
        when(mockDao.find(isNull(), eq("cp_txn_mapping"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rules);

        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");
        ctx.setPaymentDescription("Wire transfer payment");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("WIRE_TRANSFER", ctx.getAdditionalDataValue("internal_type"));
        assertEquals("R1", ctx.getAdditionalDataValue("f14_rule_id"));
        assertEquals(DomainConstants.PROCESSING_STATUS_F14_MAPPED, ctx.getProcessingStatus());
    }

    @Test
    public void testNoRules() {
        when(mockDao.find(isNull(), eq("cp_txn_mapping"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.emptyRowSet());

        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess()); // Continues with UNMATCHED
        assertEquals(FrameworkConstants.INTERNAL_TYPE_UNMATCHED,
                ctx.getAdditionalDataValue("internal_type"));
    }

    @Test
    public void testNoMatch() {
        FormRowSet rules = TestDataFactory.rowSet(
                TestDataFactory.f14RuleRow("R1", "CPT0143", "bank",
                        "payment_description", "equals", "EXACT_MATCH_ONLY",
                        "SOME_TYPE", 1)
        );
        when(mockDao.find(isNull(), eq("cp_txn_mapping"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rules);

        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");
        ctx.setPaymentDescription("Something completely different");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess()); // Continues with UNMATCHED
        assertEquals(FrameworkConstants.INTERNAL_TYPE_UNMATCHED,
                ctx.getAdditionalDataValue("internal_type"));
    }

    @Test
    public void testPriorityOrdering() {
        // Rule with priority 2 should match before priority 10
        FormRowSet rules = TestDataFactory.rowSet(
                TestDataFactory.f14RuleRow("R2", "CPT0143", "bank",
                        "payment_description", "contains", "wire",
                        "LOW_PRIORITY_TYPE", 10),
                TestDataFactory.f14RuleRow("R1", "CPT0143", "bank",
                        "payment_description", "contains", "wire",
                        "HIGH_PRIORITY_TYPE", 2)
        );
        when(mockDao.find(isNull(), eq("cp_txn_mapping"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rules);

        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");
        ctx.setPaymentDescription("Wire transfer");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("HIGH_PRIORITY_TYPE", ctx.getAdditionalDataValue("internal_type"));
    }

    @Test
    public void testSecondaryConditionMatch() {
        // Primary: payment_description contains "intress", Secondary: d_c equals C
        FormRowSet rules = TestDataFactory.rowSet(
                TestDataFactory.f14RuleRow("R1", "CPT0143", "bank",
                        "payment_description", "contains", "intress",
                        "INT_INCOME", 1,
                        "d_c", "equals", "C")
        );
        when(mockDao.find(isNull(), eq("cp_txn_mapping"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rules);

        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");
        ctx.setPaymentDescription("Intressid 2Q24");
        ctx.setDebitCredit("C");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("INT_INCOME", ctx.getAdditionalDataValue("internal_type"));
    }

    @Test
    public void testSecondaryConditionNoMatch() {
        // Primary matches but secondary doesn't -> rule should NOT match
        FormRowSet rules = TestDataFactory.rowSet(
                TestDataFactory.f14RuleRow("R1", "CPT0143", "bank",
                        "payment_description", "contains", "intress",
                        "INT_INCOME", 1,
                        "d_c", "equals", "C")
        );
        when(mockDao.find(isNull(), eq("cp_txn_mapping"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rules);

        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");
        ctx.setPaymentDescription("Intressid 2Q24");
        ctx.setDebitCredit("D"); // Debit - doesn't match secondary condition

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals(FrameworkConstants.INTERNAL_TYPE_UNMATCHED,
                ctx.getAdditionalDataValue("internal_type"));
    }

    @Test
    public void testNoSecondaryCondition() {
        // No secondary fields -> behaves like single-field rule (backward compat)
        FormRowSet rules = TestDataFactory.rowSet(
                TestDataFactory.f14RuleRow("R1", "CPT0143", "bank",
                        "payment_description", "contains", "wire",
                        "WIRE_TRANSFER", 1)
        );
        when(mockDao.find(isNull(), eq("cp_txn_mapping"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rules);

        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");
        ctx.setPaymentDescription("Wire transfer payment");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("WIRE_TRANSFER", ctx.getAdditionalDataValue("internal_type"));
    }

    @Test
    public void testSecondaryConditionDirectionFilter() {
        // Real scenario: two rules for "intress" - one for C (income), one for D (expense)
        FormRowSet rules = TestDataFactory.rowSet(
                TestDataFactory.f14RuleRow("R1", "CPT0143", "bank",
                        "payment_description", "contains", "intress",
                        "INT_INCOME", 1,
                        "d_c", "equals", "C"),
                TestDataFactory.f14RuleRow("R2", "CPT0143", "bank",
                        "payment_description", "contains", "intress",
                        "INT_EXPENSE", 1,
                        "d_c", "equals", "D")
        );
        when(mockDao.find(isNull(), eq("cp_txn_mapping"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rules);

        // Test D (debit) -> should match INT_EXPENSE
        DataContext ctxDebit = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctxDebit, "CPT0143", "Bank");
        ctxDebit.setPaymentDescription("Intressid 2Q24");
        ctxDebit.setDebitCredit("D");

        StepResult resultDebit = step.execute(ctxDebit, mockDao);

        assertTrue(resultDebit.isSuccess());
        assertEquals("INT_EXPENSE", ctxDebit.getAdditionalDataValue("internal_type"));

        // Test C (credit) -> should match INT_INCOME
        DataContext ctxCredit = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctxCredit, "CPT0143", "Bank");
        ctxCredit.setPaymentDescription("Intressid 2Q24");
        ctxCredit.setDebitCredit("C");

        StepResult resultCredit = step.execute(ctxCredit, mockDao);

        assertTrue(resultCredit.isSuccess());
        assertEquals("INT_INCOME", ctxCredit.getAdditionalDataValue("internal_type"));
    }

    @Test
    public void testEffectiveDateFiltering() {
        // Rule with future effective date should be excluded from matching
        FormRow futureRule = TestDataFactory.f14RuleRow("R1", "CPT0143", "bank",
                "payment_description", "contains", "wire",
                "WIRE_TRANSFER", 1);
        futureRule.setProperty("effectiveDate", "2099-01-01"); // future date

        FormRowSet rules = TestDataFactory.rowSet(futureRule);
        when(mockDao.find(isNull(), eq("cp_txn_mapping"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rules);

        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");
        ctx.setPaymentDescription("Wire transfer payment");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        // Rule should be filtered out due to future effective date → UNMATCHED
        assertEquals(FrameworkConstants.INTERNAL_TYPE_UNMATCHED,
                ctx.getAdditionalDataValue("internal_type"));
    }

    @Test
    public void testInOperator() {
        // Rule with "in" operator: field value should match one of comma-separated values
        FormRowSet rules = TestDataFactory.rowSet(
                TestDataFactory.f14RuleRow("R1", "CPT0143", "bank",
                        "d_c", "in", "C,D",
                        "PAYMENT", 1)
        );
        when(mockDao.find(isNull(), eq("cp_txn_mapping"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rules);

        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");
        ctx.setDebitCredit("C");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("PAYMENT", ctx.getAdditionalDataValue("internal_type"));
    }

    @Test
    public void testActiveStatusFiltering() {
        // Inactive rule should be excluded from matching
        FormRow inactiveRule = TestDataFactory.f14RuleRow("R1", "CPT0143", "bank",
                "payment_description", "contains", "wire",
                "WIRE_TRANSFER", 1);
        inactiveRule.setProperty("status", "Inactive");

        FormRowSet rules = TestDataFactory.rowSet(inactiveRule);
        when(mockDao.find(isNull(), eq("cp_txn_mapping"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rules);

        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCounterparty(ctx, "CPT0143", "Bank");
        ctx.setPaymentDescription("Wire transfer payment");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals(FrameworkConstants.INTERNAL_TYPE_UNMATCHED,
                ctx.getAdditionalDataValue("internal_type"));
    }

    @Test
    public void testShouldExecuteSkipsOnError() {
        DataContext ctx = TestDataFactory.bankContext();
        assertTrue(step.shouldExecute(ctx));

        ctx.setErrorMessage("previous error");
        assertFalse(step.shouldExecute(ctx));
    }

    // =========================================================================
    // §9b Idempotency guard tests
    // =========================================================================

    @Test
    public void testIdempotency_skipsClassified() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withF14(ctx, "INT_INCOME", "RULE-001");
        assertFalse("Should skip when type already classified", step.shouldExecute(ctx));
    }

    @Test
    public void testIdempotency_reEvaluatesUnmatched() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withF14(ctx, "UNMATCHED", "RULE-001");
        assertTrue("Should re-evaluate when type is UNMATCHED", step.shouldExecute(ctx));
    }
}
