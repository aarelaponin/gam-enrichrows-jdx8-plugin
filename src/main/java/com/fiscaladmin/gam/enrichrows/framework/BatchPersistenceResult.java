package com.fiscaladmin.gam.enrichrows.framework;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of a batch data persistence operation
 */
public class BatchPersistenceResult {
    private List<PersistenceResult> results;
    private int totalRecords;
    private int successCount;
    private int failureCount;
    private Date startTime;
    private Date endTime;
    private String targetStorage;
    
    // Statement tracking
    private Map<String, StatementStatus> statementStatuses;
    private int totalStatements;
    private int statementsProcessed;
    private int statementsWithErrors;
    
    public BatchPersistenceResult() {
        this.results = new ArrayList<>();
        this.statementStatuses = new HashMap<>();
    }
    
    public void addResult(PersistenceResult result) {
        results.add(result);
        if (result.isSuccess()) {
            successCount++;
        } else {
            failureCount++;
        }
        totalRecords++;
    }
    
    public boolean isFullySuccessful() {
        return failureCount == 0 && totalRecords > 0;
    }
    
    public double getSuccessRate() {
        if (totalRecords == 0) return 0.0;
        return (double) successCount / totalRecords;
    }
    
    // Getters and setters
    public List<PersistenceResult> getResults() { 
        return results; 
    }
    
    public void setResults(List<PersistenceResult> results) { 
        this.results = results; 
    }
    
    public int getTotalRecords() { 
        return totalRecords; 
    }
    
    public void setTotalRecords(int totalRecords) { 
        this.totalRecords = totalRecords; 
    }
    
    public int getSuccessCount() { 
        return successCount; 
    }
    
    public void setSuccessCount(int successCount) { 
        this.successCount = successCount; 
    }
    
    public int getFailureCount() { 
        return failureCount; 
    }
    
    public void setFailureCount(int failureCount) { 
        this.failureCount = failureCount; 
    }
    
    public Date getStartTime() { 
        return startTime; 
    }
    
    public void setStartTime(Date startTime) { 
        this.startTime = startTime; 
    }
    
    public Date getEndTime() { 
        return endTime; 
    }
    
    public void setEndTime(Date endTime) { 
        this.endTime = endTime; 
    }
    
    public String getTargetStorage() { 
        return targetStorage; 
    }
    
    public void setTargetStorage(String targetStorage) { 
        this.targetStorage = targetStorage; 
    }
    
    // Statement tracking methods
    public void addStatementStatus(String statementId, StatementStatus status) {
        statementStatuses.put(statementId, status);
        totalStatements++;
        if (status.isProcessed()) {
            statementsProcessed++;
        }
        if (status.hasErrors()) {
            statementsWithErrors++;
        }
    }
    
    public void updateStatementStatus(String statementId, String status, int successCount, int failureCount) {
        StatementStatus statementStatus = statementStatuses.get(statementId);
        if (statementStatus == null) {
            statementStatus = new StatementStatus(statementId);
            statementStatuses.put(statementId, statementStatus);
            totalStatements++;
        }
        statementStatus.setStatus(status);
        statementStatus.setSuccessCount(successCount);
        statementStatus.setFailureCount(failureCount);
        statementStatus.setProcessedDate(new Date());
        
        if (statementStatus.isProcessed()) {
            statementsProcessed++;
        }
        if (statementStatus.hasErrors()) {
            statementsWithErrors++;
        }
    }
    
    public Map<String, StatementStatus> getStatementStatuses() {
        return statementStatuses;
    }
    
    public int getTotalStatements() {
        return totalStatements;
    }
    
    public int getStatementsProcessed() {
        return statementsProcessed;
    }
    
    public int getStatementsWithErrors() {
        return statementsWithErrors;
    }
    
    /**
     * Inner class to track statement processing status
     */
    public static class StatementStatus {
        private String statementId;
        private String status;
        private int totalTransactions;
        private int successCount;
        private int failureCount;
        private Date processedDate;
        
        public StatementStatus(String statementId) {
            this.statementId = statementId;
            this.status = "pending";
        }
        
        public boolean isProcessed() {
            return "processed".equals(status);
        }
        
        public boolean hasErrors() {
            return failureCount > 0;
        }
        
        // Getters and setters
        public String getStatementId() { return statementId; }
        public void setStatementId(String statementId) { this.statementId = statementId; }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public int getTotalTransactions() { return totalTransactions; }
        public void setTotalTransactions(int totalTransactions) { this.totalTransactions = totalTransactions; }
        
        public int getSuccessCount() { return successCount; }
        public void setSuccessCount(int successCount) { this.successCount = successCount; }
        
        public int getFailureCount() { return failureCount; }
        public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
        
        public Date getProcessedDate() { return processedDate; }
        public void setProcessedDate(Date processedDate) { this.processedDate = processedDate; }
    }
}