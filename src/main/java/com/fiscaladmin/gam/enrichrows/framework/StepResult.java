package com.fiscaladmin.gam.enrichrows.framework;

import java.util.Map;

public class StepResult {
    private boolean success;
    private String message;
    private Map<String, Object> outputData;

    public StepResult(boolean success) {
        this.success = success;
    }

    public StepResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Map<String, Object> getOutputData() { return outputData; }
    public void setOutputData(Map<String, Object> outputData) { this.outputData = outputData; }
}