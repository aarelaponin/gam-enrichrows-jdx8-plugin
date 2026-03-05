package com.fiscaladmin.gam.enrichrows.framework;

import com.fiscaladmin.gam.enrichrows.helpers.TestDataFactory;
import org.joget.apps.form.dao.FormDataDao;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class DataPipelineTest {

    private FormDataDao mockDao;
    private DataPipeline pipeline;

    @Before
    public void setUp() {
        mockDao = Mockito.mock(FormDataDao.class);
        pipeline = new DataPipeline(mockDao);
    }

    @Test
    public void testEmptyPipeline() {
        assertEquals(0, pipeline.getStepCount());
        assertTrue(pipeline.getSteps().isEmpty());
    }

    @Test
    public void testAddSteps() {
        DataStep step1 = createSuccessStep("Step1");
        DataStep step2 = createSuccessStep("Step2");

        pipeline.addStep(step1).addStep(step2);

        assertEquals(2, pipeline.getStepCount());
        assertEquals("Step1", pipeline.getSteps().get(0).getStepName());
        assertEquals("Step2", pipeline.getSteps().get(1).getStepName());
    }

    @Test
    public void testStepOrdering() {
        DataStep step1 = createSuccessStep("First");
        DataStep step2 = createSuccessStep("Second");
        DataStep step3 = createSuccessStep("Third");

        pipeline.addStep(step1).addStep(step2).addStep(step3);

        List<DataStep> steps = pipeline.getSteps();
        assertEquals("First", steps.get(0).getStepName());
        assertEquals("Second", steps.get(1).getStepName());
        assertEquals("Third", steps.get(2).getStepName());
    }

    @Test
    public void testSuccessfulExecution() {
        pipeline.addStep(createSuccessStep("Step1"));
        pipeline.addStep(createSuccessStep("Step2"));

        DataContext ctx = TestDataFactory.bankContext();
        PipelineResult result = pipeline.execute(ctx);

        assertTrue(result.isSuccess());
        assertNull(result.getErrorMessage());
        assertEquals(2, result.getStepResults().size());
    }

    @Test
    public void testStopOnErrorTrue() {
        pipeline.setStopOnError(true);
        pipeline.addStep(createSuccessStep("Step1"));
        pipeline.addStep(createFailureStep("Step2"));
        pipeline.addStep(createSuccessStep("Step3"));

        DataContext ctx = TestDataFactory.bankContext();
        PipelineResult result = pipeline.execute(ctx);

        assertFalse(result.isSuccess());
        // Step3 should NOT have executed
        assertEquals(2, result.getStepResults().size());
        assertNull(result.getStepResults().get("Step3"));
    }

    @Test
    public void testStopOnErrorFalse() {
        pipeline.setStopOnError(false);
        pipeline.addStep(createSuccessStep("Step1"));
        pipeline.addStep(createFailureStep("Step2"));
        pipeline.addStep(createSuccessStep("Step3"));

        DataContext ctx = TestDataFactory.bankContext();
        PipelineResult result = pipeline.execute(ctx);

        // All steps should have executed
        assertEquals(3, result.getStepResults().size());
        assertTrue(result.getStepResults().get("Step1").isSuccess());
        assertFalse(result.getStepResults().get("Step2").isSuccess());
        assertTrue(result.getStepResults().get("Step3").isSuccess());
    }

    @Test
    public void testBatchExecution() {
        pipeline.addStep(createSuccessStep("Step1"));

        DataContext ctx1 = TestDataFactory.bankContext("TX1", "ST1");
        DataContext ctx2 = TestDataFactory.secuContext("TX2", "ST2");

        BatchPipelineResult batchResult = pipeline.executeBatch(Arrays.asList(ctx1, ctx2));

        assertEquals(2, batchResult.getTotalTransactions());
        assertEquals(2, batchResult.getSuccessCount());
        assertEquals(0, batchResult.getFailureCount());
        assertEquals(2, batchResult.getResults().size());
    }

    @Test
    public void testBatchWithMixedResults() {
        pipeline.setStopOnError(true);
        // Step that fails only for secu
        pipeline.addStep(new AbstractDataStep() {
            @Override
            protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
                if ("secu".equals(context.getSourceType())) {
                    return new StepResult(false, "Secu not supported");
                }
                return new StepResult(true, "OK");
            }

            @Override
            public String getStepName() {
                return "ConditionalStep";
            }
        });

        DataContext ctx1 = TestDataFactory.bankContext("TX1", "ST1");
        DataContext ctx2 = TestDataFactory.secuContext("TX2", "ST2");

        BatchPipelineResult batchResult = pipeline.executeBatch(Arrays.asList(ctx1, ctx2));

        assertEquals(2, batchResult.getTotalTransactions());
        assertEquals(1, batchResult.getSuccessCount());
        assertEquals(1, batchResult.getFailureCount());
    }

    @Test
    public void testGetStepsReturnsDefensiveCopy() {
        pipeline.addStep(createSuccessStep("Step1"));
        List<DataStep> steps = pipeline.getSteps();
        steps.clear();

        // Original should be unaffected
        assertEquals(1, pipeline.getStepCount());
    }

    @Test
    public void testFluentInterface() {
        DataPipeline result = pipeline
                .addStep(createSuccessStep("A"))
                .addStep(createSuccessStep("B"))
                .setStopOnError(false);

        assertSame(pipeline, result);
        assertEquals(2, pipeline.getStepCount());
    }

    @Test
    public void testBatchContextSetOnSteps() {
        // Track batch context values seen by the step
        int[] capturedIndex = {-1};
        int[] capturedTotal = {-1};
        boolean[] capturedIsLast = {false};

        pipeline.addStep(new AbstractDataStep() {
            @Override
            protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
                capturedIndex[0] = getBatchIndex();
                capturedTotal[0] = getBatchTotal();
                capturedIsLast[0] = isLastInBatch();
                return new StepResult(true, "OK");
            }

            @Override
            public String getStepName() {
                return "ContextCapture";
            }
        });

        DataContext ctx1 = TestDataFactory.bankContext("TX1", "ST1");
        DataContext ctx2 = TestDataFactory.bankContext("TX2", "ST1");
        pipeline.executeBatch(Arrays.asList(ctx1, ctx2));

        // After batch, the step should have been called last with index=1, total=2
        assertEquals(1, capturedIndex[0]);
        assertEquals(2, capturedTotal[0]);
        assertTrue(capturedIsLast[0]);
    }

    @Test
    public void testBatchContextFirstItemNotLast() {
        boolean[] capturedIsLastFirstItem = {true};

        pipeline.addStep(new AbstractDataStep() {
            private boolean firstCall = true;

            @Override
            protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
                if (firstCall) {
                    capturedIsLastFirstItem[0] = isLastInBatch();
                    firstCall = false;
                }
                return new StepResult(true, "OK");
            }

            @Override
            public String getStepName() {
                return "FirstItemCapture";
            }
        });

        DataContext ctx1 = TestDataFactory.bankContext("TX1", "ST1");
        DataContext ctx2 = TestDataFactory.bankContext("TX2", "ST1");
        pipeline.executeBatch(Arrays.asList(ctx1, ctx2));

        assertFalse(capturedIsLastFirstItem[0]);
    }

    // ---- Helper methods ----

    private DataStep createSuccessStep(String name) {
        return new AbstractDataStep() {
            @Override
            protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
                return new StepResult(true, name + " succeeded");
            }

            @Override
            public String getStepName() {
                return name;
            }
        };
    }

    private DataStep createFailureStep(String name) {
        return new AbstractDataStep() {
            @Override
            protected StepResult performStep(DataContext context, FormDataDao formDataDao) {
                return new StepResult(false, name + " failed");
            }

            @Override
            public String getStepName() {
                return name;
            }
        };
    }
}
