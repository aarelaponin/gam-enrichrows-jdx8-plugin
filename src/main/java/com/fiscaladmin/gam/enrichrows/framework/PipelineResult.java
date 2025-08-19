package com.fiscaladmin.gam.enrichrows.framework;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

public class PipelineResult {
    private String transactionId;
    private boolean success;
    private String errorMessage;
    private Date startTime;
    private Date endTime;
    private Map<String, StepResult> stepResults = new LinkedHashMap<>();

    public void addStepResult(String stepName, StepResult result) {
        stepResults.put(stepName, result);
    }

    // Getters and setters
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }

    public Date getEndTime() { return endTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }

    public Map<String, StepResult> getStepResults() { return stepResults; }
}
