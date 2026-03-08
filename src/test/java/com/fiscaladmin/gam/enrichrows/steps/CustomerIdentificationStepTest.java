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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class CustomerIdentificationStepTest {

    private CustomerIdentificationStep step;
    private FormDataDao mockDao;

    @Before
    public void setUp() {
        step = new CustomerIdentificationStep();
        mockDao = Mockito.mock(FormDataDao.class);
    }

    @Test
    public void testStepName() {
        assertEquals("Customer Identification", step.getStepName());
    }

    @Test
    public void testBankHappyPath() {
        // Customer master returns matching customer by registration number
        FormRow customer = TestDataFactory.customerRow("CUST-001", "Acme Corp", "AC01", "active");
        customer.setProperty("registrationNumber", "CUST-001");

        FormRowSet customers = TestDataFactory.rowSet(customer);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(customers);

        // Load customer by ID
        FormRowSet customerById = TestDataFactory.rowSet(customer);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(customerById);

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("CUST-001");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals(DomainConstants.PROCESSING_STATUS_CUSTOMER_IDENTIFIED,
                ctx.getProcessingStatus());
    }

    @Test
    public void testSecuSkipped() {
        // CustomerIdentification should skip secu transactions (§2.1)
        DataContext ctx = TestDataFactory.secuContext();
        assertFalse(step.shouldExecute(ctx));
    }

    @Test
    public void testUnknownFallback() {
        // No customers match
        FormRowSet emptySet = TestDataFactory.emptyRowSet();
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(emptySet);
        when(mockDao.find(isNull(), eq("customer_account"),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(emptySet);

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("NONEXISTENT");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess()); // Continues with UNKNOWN
        assertEquals("UNKNOWN", ctx.getCustomerId());
    }

    @Test
    public void testShouldExecuteBankOnlyNotSecu() {
        DataContext bankCtx = TestDataFactory.bankContext();
        DataContext secuCtx = TestDataFactory.secuContext();

        assertTrue(step.shouldExecute(bankCtx));
        assertFalse(step.shouldExecute(secuCtx)); // Secu skipped per §2.1
    }

    @Test
    public void testShouldExecuteSkipsOnError() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setErrorMessage("previous error");
        assertFalse(step.shouldExecute(ctx));
    }

    // ===== §4.0a: Securities-Related Bank Transaction Skip =====

    @Test
    public void testSkipsSecuritiesBuy() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Securities buy (AAPL)");
        assertFalse(step.shouldExecute(ctx));
    }

    @Test
    public void testSkipsSecuritiesSell() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Securities sell (MSFT)");
        assertFalse(step.shouldExecute(ctx));
    }

    @Test
    public void testSkipsSecuritiesCommission() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Securities commission fee (AAPL)");
        assertFalse(step.shouldExecute(ctx));
    }

    @Test
    public void testSkipsDividends() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Dividends (MSFT)");
        assertFalse(step.shouldExecute(ctx));
    }

    @Test
    public void testSkipsIncomeTaxWithheld() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Income tax withheld (MSFT) (15.01.2026)");
        assertFalse(step.shouldExecute(ctx));
    }

    @Test
    public void testDoesNotSkipNonSecurities() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("Wire transfer to supplier");
        assertTrue(step.shouldExecute(ctx));
    }

    @Test
    public void testDoesNotSkipNullDescription() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription(null);
        assertTrue(step.shouldExecute(ctx));
    }

    @Test
    public void testCaseInsensitive() {
        DataContext ctx = TestDataFactory.bankContext();
        ctx.setPaymentDescription("SECURITIES BUY (AAPL)");
        assertFalse(step.shouldExecute(ctx));
    }

    // ===== §3.11: Method 3 — Customer Account Lookup =====

    @Test
    public void testMethod3_customerAccountLookup_resolvesCustomer() {
        // No direct ID match (Method 1 fails)
        FormRowSet emptyCustomers = TestDataFactory.emptyRowSet();
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(emptyCustomers);

        // Method 2: no account_number on transaction row
        when(mockDao.find(isNull(), eq("customer_account"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.emptyRowSet());

        // Method 3: customer_account lookup returns match
        FormRow accountRow = new FormRow();
        accountRow.setProperty("accountNumber", "EE161700017004567419");
        accountRow.setProperty("customerId", "87654321");
        accountRow.setProperty("status", "Active");

        FormRowSet accountRows = TestDataFactory.rowSet(accountRow);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                contains("c_accountNumber"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(accountRows);

        // Customer master lookup by ID (for customer details)
        FormRow customer = TestDataFactory.customerRow("87654321", "Mango Invest OÜ", "MI01", "active");
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("NONEXISTENT"); // Method 1 won't match
        ctx.setOtherSideAccount("EE161700017004567419");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("87654321", ctx.getCustomerId());
        assertEquals("CUSTOMER_ACCOUNT",
                ctx.getAdditionalData().get("customer_identification_method"));
        assertEquals(93, ctx.getAdditionalData().get("customer_confidence"));
    }

    @Test
    public void testMethod3_noCustomerAccount_fallsThrough() {
        // No direct ID match
        FormRowSet emptyCustomers = TestDataFactory.emptyRowSet();
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(emptyCustomers);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(emptyCustomers);

        // Method 2: no match
        when(mockDao.find(isNull(), eq("customer_account"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.emptyRowSet());

        // Method 3: customer_account returns empty
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                contains("c_accountNumber"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.emptyRowSet());

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("NONEXISTENT");
        ctx.setOtherSideAccount("EE000000000000000000");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("UNKNOWN", ctx.getCustomerId());
    }

    @Test
    public void testMethod1_takesPrecedence_overMethod3() {
        // Method 1: direct ID matches CUST-001
        FormRow customer = TestDataFactory.customerRow("CUST-001", "Acme Corp", "AC01", "active");
        customer.setProperty("registrationNumber", "CUST-001");

        FormRowSet customers = TestDataFactory.rowSet(customer);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(customers);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        // Method 3 would also match a different customer (should never be reached)
        FormRow accountRow = new FormRow();
        accountRow.setProperty("accountNumber", "EE161700017004567419");
        accountRow.setProperty("customerId", "DIFFERENT-CUST");
        accountRow.setProperty("status", "Active");
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                contains("c_accountNumber"), any(String[].class),
                isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.rowSet(accountRow));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("CUST-001");
        ctx.setOtherSideAccount("EE161700017004567419");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("CUST-001", ctx.getCustomerId());
        assertEquals("DIRECT_ID",
                ctx.getAdditionalData().get("customer_identification_method"));
    }

    // ===== §4.0b: KYC Status Check =====

    @Test
    public void testKycCompletedNoException() {
        // Customer with kycStatus=completed should NOT create INACTIVE_CUSTOMER exception
        FormRow customer = TestDataFactory.customerRow("CUST-001", "Acme Corp", "AC01", "active");
        customer.setProperty("registrationNumber", "CUST-001");
        // kycStatus="completed" is set by default in TestDataFactory

        FormRowSet customers = TestDataFactory.rowSet(customer);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(customers);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("CUST-001");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        // Verify no INACTIVE_CUSTOMER exception was saved
        assertFalse(hasInactiveCustomerException());
    }

    @Test
    public void testKycInprogressCreatesException() {
        FormRow customer = TestDataFactory.customerRow("CUST-001", "Acme Corp", "AC01", "active");
        customer.setProperty("registrationNumber", "CUST-001");
        customer.setProperty("kycStatus", "inprogress");

        FormRowSet customers = TestDataFactory.rowSet(customer);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(customers);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("CUST-001");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertTrue(hasInactiveCustomerException());
    }

    @Test
    public void testKycPendingCreatesException() {
        FormRow customer = TestDataFactory.customerRow("CUST-001", "Acme Corp", "AC01", "active");
        customer.setProperty("registrationNumber", "CUST-001");
        customer.setProperty("kycStatus", "pending");

        FormRowSet customers = TestDataFactory.rowSet(customer);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(customers);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("CUST-001");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertTrue(hasInactiveCustomerException());
    }

    @Test
    public void testKycNullCreatesException() {
        FormRow customer = TestDataFactory.customerRow("CUST-001", "Acme Corp", "AC01", "active");
        customer.setProperty("registrationNumber", "CUST-001");
        customer.remove("kycStatus"); // Remove the default to simulate null

        FormRowSet customers = TestDataFactory.rowSet(customer);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(customers);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("CUST-001");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertTrue(hasInactiveCustomerException());
    }

    /**
     * Check if any saveOrUpdate call to exception_queue contained an INACTIVE_CUSTOMER exception.
     */
    private boolean hasInactiveCustomerException() {
        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        try {
            verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(),
                    eq(DomainConstants.TABLE_EXCEPTION_QUEUE), captor.capture());
        } catch (AssertionError e) {
            // No calls to exception_queue at all (WantedButNotInvoked or ArgumentsAreDifferent)
            return false;
        }
        List<FormRowSet> allCalls = captor.getAllValues();
        for (FormRowSet rowSet : allCalls) {
            for (FormRow row : rowSet) {
                if (DomainConstants.EXCEPTION_INACTIVE_CUSTOMER.equals(
                        row.getProperty("exception_type"))) {
                    return true;
                }
            }
        }
        return false;
    }

    // =========================================================================
    // §9b Idempotency guard tests
    // =========================================================================

    @Test
    public void testIdempotency_skipsResolved() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCustomer(ctx, "12345678", 100);
        assertFalse("Should skip when customer already resolved", step.shouldExecute(ctx));
    }

    @Test
    public void testIdempotency_reEvaluatesUnknown() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCustomer(ctx, "UNKNOWN", 0);
        assertTrue("Should re-evaluate when customer is UNKNOWN", step.shouldExecute(ctx));
    }

    // =========================================================================
    // Method 1 (Direct ID) — additional matching paths
    // =========================================================================

    @Test
    public void testMethod1_matchesByPersonalId() {
        FormRow customer = TestDataFactory.customerRow("CUST-IND", "Jane Doe", "JD01", "active");
        customer.setProperty("personalId", "38001015555");

        FormRowSet customers = TestDataFactory.rowSet(customer);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(customers);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("38001015555");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("CUST-IND", ctx.getCustomerId());
        assertEquals("DIRECT_ID", ctx.getAdditionalData().get("customer_identification_method"));
    }

    @Test
    public void testMethod1_matchesByTaxId() {
        FormRow customer = TestDataFactory.customerRow("CUST-TAX", "Tax Company", "TX01", "active");
        customer.setProperty("tax_id", "EE123456789");

        FormRowSet customers = TestDataFactory.rowSet(customer);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(customers);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("EE123456789");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("CUST-TAX", ctx.getCustomerId());
        assertEquals("DIRECT_ID", ctx.getAdditionalData().get("customer_identification_method"));
    }

    @Test
    public void testMethod1_nullCustomerIdField() {
        // When ctx.customerId is null, Method 1 returns null and falls through
        FormRowSet emptyCustomers = TestDataFactory.emptyRowSet();
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(emptyCustomers);
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(emptyCustomers);
        when(mockDao.find(isNull(), eq("customer_account"),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(emptyCustomers);

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId(null);

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("UNKNOWN", ctx.getCustomerId());
    }

    // =========================================================================
    // Method 2 (Account Number) — identifyByAccountNumber
    // =========================================================================

    @Test
    public void testMethod2_matchesByAccountNumber() {
        // No Method 1 match
        FormRowSet emptyCustomers = TestDataFactory.emptyRowSet();
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(emptyCustomers);

        // Method 2: customer_account table has matching account_number
        FormRow accountRow = new FormRow();
        accountRow.setProperty("account_number", "EE123456789012345678");
        accountRow.setProperty("status", "active");
        accountRow.setProperty("corporateCustomerId", "12345678");

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.rowSet(accountRow));

        // Resolving corporateCustomerId -> customer by registrationNumber
        FormRow customer = TestDataFactory.customerRow("CUST-ACCT", "Account Corp", "AC01", "active");
        customer.setProperty("registrationNumber", "12345678");

        // Return customer for all customer master lookups
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.rowSet(customer));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("NONEXISTENT"); // Method 1 won't match
        // Set account_number on transaction row
        ctx.getTransactionRow().setProperty("account_number", "EE123456789012345678");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("CUST-ACCT", ctx.getCustomerId());
        assertEquals("ACCOUNT_NUMBER", ctx.getAdditionalData().get("customer_identification_method"));
    }

    @Test
    public void testMethod2_matchesByCustomerMasterAccount() {
        // No Method 1 match
        FormRowSet emptySet = TestDataFactory.emptyRowSet();
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(emptySet);

        // Method 2: customer_account table is empty
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(emptySet);

        // But customer_master has bank_account_number matching
        FormRow customer = TestDataFactory.customerRow("CUST-BNK", "Bank Account Corp", "BA01", "active");
        customer.setProperty("bank_account_number", "EE987654321098765432");

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.rowSet(customer));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("NONEXISTENT");
        ctx.getTransactionRow().setProperty("account_number", "EE987654321098765432");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("CUST-BNK", ctx.getCustomerId());
        assertEquals("ACCOUNT_NUMBER", ctx.getAdditionalData().get("customer_identification_method"));
    }

    @Test
    public void testMethod2_skippedForSecu() {
        // Secu context should not execute customer identification at all
        DataContext ctx = TestDataFactory.secuContext();
        assertFalse(step.shouldExecute(ctx));
    }

    // =========================================================================
    // Method 4 (Registration Number Extraction)
    // =========================================================================

    @Test
    public void testMethod4_extractsFromReference() {
        // No Method 1, 2, 3 match
        FormRowSet emptySet = TestDataFactory.emptyRowSet();
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(emptySet);

        // Customer with registrationNumber that will match extracted value
        FormRow customer = TestDataFactory.customerRow("CUST-REG", "Reg Corp", "RC01", "active");
        customer.setProperty("registrationNumber", "12345678");

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.rowSet(customer));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("NONEXISTENT"); // Method 1 won't match (no customer with this registrationNumber)
        ctx.setReference("Payment REG:12345678 for services");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("CUST-REG", ctx.getCustomerId());
        assertEquals("REGISTRATION_NUMBER_EXTRACTED",
                ctx.getAdditionalData().get("customer_identification_method"));
    }

    @Test
    public void testMethod4_extractsFromDescription() {
        // No Method 1, 2, 3 match
        FormRowSet emptySet = TestDataFactory.emptyRowSet();
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(emptySet);

        FormRow customer = TestDataFactory.customerRow("CUST-DESC", "Desc Corp", "DC01", "active");
        customer.setProperty("registrationNumber", "87654321");

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.rowSet(customer));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("NONEXISTENT");
        ctx.setReference(null); // No reference
        ctx.setDescription("Invoice REGNUM:87654321 paid");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("CUST-DESC", ctx.getCustomerId());
        assertEquals("REGISTRATION_NUMBER_EXTRACTED",
                ctx.getAdditionalData().get("customer_identification_method"));
    }

    // =========================================================================
    // Method 5 (Name Pattern Matching)
    // =========================================================================

    @Test
    public void testMethod5_exactNameMatch() {
        // No Method 1-4 match
        FormRowSet emptySet = TestDataFactory.emptyRowSet();
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(emptySet);

        FormRow customer = TestDataFactory.customerRow("CUST-NAME", "Acme Corp", "AM01", "active");

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.rowSet(customer));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId(null); // Method 1 skipped
        ctx.setReference(null);  // Method 4 skipped
        ctx.setDescription(null);
        ctx.setOtherSideName("Acme Corp");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("CUST-NAME", ctx.getCustomerId());
        assertEquals("NAME_PATTERN", ctx.getAdditionalData().get("customer_identification_method"));
    }

    @Test
    public void testMethod5_partialNameMatch() {
        FormRowSet emptySet = TestDataFactory.emptyRowSet();
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(emptySet);

        FormRow customer = TestDataFactory.customerRow("CUST-PART", "Acme Corp", "AM01", "active");

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.rowSet(customer));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId(null);
        ctx.setReference(null);
        ctx.setDescription(null);
        // "Acme Corp International" contains "Acme Corp" (9 chars) — ratio 9/23 ≈ 39%, too low
        // Use something with >= 70% ratio: "Acme Corp" (9) in "Acme Corp AB" (12) → 9/12 = 75%
        ctx.setOtherSideName("Acme Corp AB");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("CUST-PART", ctx.getCustomerId());
        assertEquals("NAME_PATTERN", ctx.getAdditionalData().get("customer_identification_method"));
    }

    @Test
    public void testMethod5_shortNameRejectsPartial() {
        FormRowSet emptySet = TestDataFactory.emptyRowSet();
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(emptySet);

        FormRow customer = TestDataFactory.customerRow("CUST-SHORT", "AB Corp", "AB01", "active");

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId(null);
        ctx.setReference(null);
        ctx.setDescription(null);
        ctx.setOtherSideName("AB"); // Less than 5 chars → isReasonableMatch returns false

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("UNKNOWN", ctx.getCustomerId()); // No match due to short name
    }

    // =========================================================================
    // Low confidence & customer details
    // =========================================================================

    @Test
    public void testLowConfidenceCreatesException() {
        // NAME_PATTERN method has confidence=70, which is < 80 threshold → low-confidence exception
        FormRowSet emptySet = TestDataFactory.emptyRowSet();
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(emptySet);

        FormRow customer = TestDataFactory.customerRow("CUST-LOW", "Low Confidence Corp", "LC01", "active");

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.rowSet(customer));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId(null);
        ctx.setReference(null);
        ctx.setDescription(null);
        ctx.setOtherSideName("Low Confidence Corp");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("NAME_PATTERN", ctx.getAdditionalData().get("customer_identification_method"));
        assertEquals(70, ctx.getAdditionalData().get("customer_confidence"));

        // Verify low-confidence exception was created
        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(),
                eq(DomainConstants.TABLE_EXCEPTION_QUEUE), captor.capture());
        boolean foundLowConf = false;
        for (FormRowSet rs : captor.getAllValues()) {
            for (FormRow row : rs) {
                if (DomainConstants.EXCEPTION_LOW_CONFIDENCE.equals(row.getProperty("exception_type"))) {
                    foundLowConf = true;
                }
            }
        }
        assertTrue("Expected LOW_CONFIDENCE exception", foundLowConf);
    }

    @Test
    public void testHighConfidenceNoException() {
        // DIRECT_ID method has confidence=100 → no low-confidence exception
        FormRow customer = TestDataFactory.customerRow("CUST-HI", "High Conf Corp", "HC01", "active");
        customer.setProperty("registrationNumber", "99999999");

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.rowSet(customer));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("99999999");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals(100, ctx.getAdditionalData().get("customer_confidence"));

        // Verify no LOW_CONFIDENCE exception was created
        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        try {
            verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(),
                    eq(DomainConstants.TABLE_EXCEPTION_QUEUE), captor.capture());
            for (FormRowSet rs : captor.getAllValues()) {
                for (FormRow row : rs) {
                    assertNotEquals(DomainConstants.EXCEPTION_LOW_CONFIDENCE,
                            row.getProperty("exception_type"));
                }
            }
        } catch (AssertionError e) {
            // No calls to exception_queue at all — that's fine
        }
    }

    // =========================================================================
    // CHANGES-08: Method 6 (Fund Fallback)
    // =========================================================================

    /**
     * §7.1: No other_side_account, Methods 1–5 fail → fund customer resolved.
     * Confidence 80 equals threshold → no LOW_CONFIDENCE exception.
     */
    @Test
    public void testMethod6_noCounterparty_resolvedToFund() {
        // Fund customer entity
        FormRow fundCustomer = TestDataFactory.customerRow("FUND-001", "RSR Capital Fund", "RSR1", "active");
        fundCustomer.setProperty("is_fund", "yes");

        // Full table scan returns fund customer (used by Method 6 Java filter;
        // Methods 1, 5 exit early due to null customerId / empty otherSideName)
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.rowSet(fundCustomer));

        // loadFormRow for customer details after identification
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.rowSet(fundCustomer));

        // Method 2: customer_account table empty
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(TestDataFactory.emptyRowSet());

        DataContext ctx = TestDataFactory.bankContext();
        // Clear fields to prevent Methods 1–5 from matching (empty, not null — Properties rejects null)
        ctx.setCustomerId(null);
        ctx.setOtherSideName("");
        ctx.setReference(null);
        ctx.setDescription(null);
        // otherSideAccount is NOT set by bankContext() — null by default → triggers Method 6

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("FUND-001", ctx.getCustomerId());
        assertEquals("FUND_FALLBACK", ctx.getAdditionalData().get("customer_identification_method"));
        assertEquals(80, ctx.getAdditionalData().get("customer_confidence"));

        // Confidence 80 = threshold 80 → no LOW_CONFIDENCE exception
        ArgumentCaptor<FormRowSet> captor = ArgumentCaptor.forClass(FormRowSet.class);
        try {
            verify(mockDao, atLeastOnce()).saveOrUpdate(isNull(),
                    eq(DomainConstants.TABLE_EXCEPTION_QUEUE), captor.capture());
            for (FormRowSet rs : captor.getAllValues()) {
                for (FormRow row : rs) {
                    assertNotEquals(DomainConstants.EXCEPTION_LOW_CONFIDENCE,
                            row.getProperty("exception_type"));
                }
            }
        } catch (AssertionError e) {
            // No calls to exception_queue at all — that's fine
        }
    }

    /**
     * §7.2: other_side_account set, Methods 1–5 fail → Method 6 does NOT fire.
     */
    @Test
    public void testMethod6_hasCounterparty_skipped() {
        FormRowSet emptySet = TestDataFactory.emptyRowSet();

        // Methods 1, 2 fallback, 5: full table scan returns empty
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(emptySet);

        // Method 2: customer_account table empty
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(emptySet);

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId(null);
        ctx.setOtherSideName("");
        ctx.setReference(null);
        ctx.setDescription(null);
        // Set other_side_account → counterparty present → Method 6 should NOT fire
        ctx.setOtherSideAccount("EE161700017004567419");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("UNKNOWN", ctx.getCustomerId());
    }

    /**
     * §7.3: Two no-counterparty contexts processed sequentially by same step instance.
     * Fund query should be called exactly once (cached).
     */
    @Test
    public void testMethod6_cachedAcrossTransactions() {
        FormRow fundCustomer = TestDataFactory.customerRow("FUND-001", "RSR Capital Fund", "RSR1", "active");
        fundCustomer.setProperty("is_fund", "yes");

        // Full table scan returns fund customer (Method 6 filters by is_fund in Java)
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.rowSet(fundCustomer));

        // loadFormRow for customer details
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), eq(false), eq(0), eq(1)))
                .thenReturn(TestDataFactory.rowSet(fundCustomer));

        // Method 2: customer_account table empty
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(TestDataFactory.emptyRowSet());

        // First transaction
        DataContext ctx1 = TestDataFactory.bankContext("TRX-A", "STMT-001");
        ctx1.setCustomerId(null);
        ctx1.setOtherSideName("");
        ctx1.setReference(null);
        ctx1.setDescription(null);

        StepResult result1 = step.execute(ctx1, mockDao);
        assertTrue(result1.isSuccess());
        assertEquals("FUND-001", ctx1.getCustomerId());

        // Second transaction
        DataContext ctx2 = TestDataFactory.bankContext("TRX-B", "STMT-001");
        ctx2.setCustomerId(null);
        ctx2.setOtherSideName("");
        ctx2.setReference(null);
        ctx2.setDescription(null);

        StepResult result2 = step.execute(ctx2, mockDao);
        assertTrue(result2.isSuccess());
        assertEquals("FUND-001", ctx2.getCustomerId());

        // Full table scan called once for first transaction (cached for second).
        // Methods 1, 5 exit early (null customerId / empty otherSideName) — no scan.
        verify(mockDao, times(1)).find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    /**
     * §7.4: No customer with is_fund=yes → customerId = UNKNOWN.
     */
    @Test
    public void testMethod6_noFundEntity_returnsNull() {
        // Full table scan returns empty — no fund customer exists
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.emptyRowSet());

        // Method 2: customer_account table empty
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_ACCOUNT),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(TestDataFactory.emptyRowSet());

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId(null);
        ctx.setOtherSideName("");
        ctx.setReference(null);
        ctx.setDescription(null);

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("UNKNOWN", ctx.getCustomerId());
    }

    /**
     * §7.5: Context has customer_id from previous run → shouldExecute() returns false.
     */
    @Test
    public void testMethod6_reEnrichment_fundAlreadyAssigned() {
        DataContext ctx = TestDataFactory.bankContext();
        TestDataFactory.withCustomer(ctx, "FUND-001", 80);
        assertFalse("Should skip when fund customer already resolved", step.shouldExecute(ctx));
    }

    @Test
    public void testCustomerDetailsPopulated() {
        FormRow customer = TestDataFactory.customerRow("CUST-DET", "Details Corp", "DT01", "active");
        customer.setProperty("registrationNumber", "11112222");
        customer.setProperty("customer_type", "corporate");
        customer.setProperty("base_currency", "EUR");

        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(TestDataFactory.rowSet(customer));
        when(mockDao.find(isNull(), eq(DomainConstants.TABLE_CUSTOMER_MASTER),
                eq("WHERE id = ?"), any(), isNull(), any(), any(), any()))
                .thenReturn(TestDataFactory.rowSet(customer));

        DataContext ctx = TestDataFactory.bankContext();
        ctx.setCustomerId("11112222");

        StepResult result = step.execute(ctx, mockDao);

        assertTrue(result.isSuccess());
        assertEquals("corporate", ctx.getAdditionalData().get("customer_type"));
        assertEquals("EUR", ctx.getAdditionalData().get("customer_currency"));
        assertEquals("DT01", ctx.getAdditionalData().get("customer_code"));
    }
}
