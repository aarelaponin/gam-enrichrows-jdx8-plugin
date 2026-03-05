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
}
