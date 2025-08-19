# Joget DX8 Plugin Types - Complete Developer's Guide

## Table of Contents

1. [Overview](#overview)
2. [Plugin Type Categories](#plugin-type-categories)
3. [Process/Workflow Plugins](#processworkflow-plugins)
   - [Process Tool Plugin](#process-tool-plugin)
   - [Decision Plugin](#decision-plugin)
   - [Deadline Plugin](#deadline-plugin)
4. [Form Plugins](#form-plugins)
   - [Form Element Plugin](#form-element-plugin)
   - [Form Validator Plugin](#form-validator-plugin)
   - [Form Load Binder Plugin](#form-load-binder-plugin)
   - [Form Store Binder Plugin](#form-store-binder-plugin)
   - [Form Options Binder Plugin](#form-options-binder-plugin)
   - [Form Multi Row Load Binder](#form-multi-row-load-binder)
   - [Form Multi Row Store Binder](#form-multi-row-store-binder)
5. [DataList Plugins](#datalist-plugins)
   - [DataList Binder Plugin](#datalist-binder-plugin)
   - [DataList Action Plugin](#datalist-action-plugin)
   - [DataList Formatter Plugin](#datalist-formatter-plugin)
   - [DataList Filter Type Plugin](#datalist-filter-type-plugin)
6. [Userview Plugins](#userview-plugins)
   - [Userview Menu Plugin](#userview-menu-plugin)
   - [Userview Theme Plugin](#userview-theme-plugin)
   - [Userview Permission Plugin](#userview-permission-plugin)
7. [System Plugins](#system-plugins)
   - [Hash Variable Plugin](#hash-variable-plugin)
   - [Audit Trail Plugin](#audit-trail-plugin)
   - [Directory Manager Plugin](#directory-manager-plugin)
   - [Participant Plugin](#participant-plugin)
8. [Web Service Plugins](#web-service-plugins)
   - [Web Service Plugin](#web-service-plugin)
   - [WebSocket Plugin](#websocket-plugin)
9. [API Plugins](#api-plugins)
10. [Permission Plugins](#permission-plugins)
11. [Mobile Plugins](#mobile-plugins)
12. [Plugin Development Patterns](#plugin-development-patterns)
13. [Choosing the Right Plugin Type](#choosing-the-right-plugin-type)

---

## Overview

Joget DX8 provides an extensive plugin architecture with over 20 different plugin types, each designed for specific extension points in the platform. This guide provides comprehensive coverage of all plugin types with practical examples and implementation patterns.

### Key Concepts

- **Plugin Types**: Different categories of plugins for different platform extension points
- **Interfaces**: Each plugin type implements specific interfaces defining its contract
- **Base Classes**: Abstract classes providing common functionality for each type
- **Property System**: Configuration framework for all plugin types

---

## Plugin Type Categories

Joget plugins are organized into these major categories:

| Category | Purpose | Common Plugin Types |
|----------|---------|-------------------|
| **Process/Workflow** | Extend workflow capabilities | Process Tool, Decision, Deadline |
| **Form** | Enhance form functionality | Form Element, Validator, Binders |
| **DataList** | Customize data display | DataList Binder, Action, Formatter |
| **Userview** | Build user interfaces | Userview Menu, Theme, Permission |
| **System** | Core system extensions | Hash Variable, Audit Trail, Directory |
| **Web Service** | External integration | Web Service, WebSocket |
| **API** | REST API extensions | API Plugin |
| **Permission** | Access control | Permission Plugin |

---

## Process/Workflow Plugins

### Process Tool Plugin

**Purpose**: Execute custom business logic within workflow activities

**Base Class**: `DefaultApplicationPlugin`

**Interface**: `ApplicationPlugin`

**When to Use**:
- Sending notifications
- Calling external APIs
- Database operations
- File processing
- System integration

**Implementation Example**:

```java
package com.company.plugin;

import org.joget.apps.app.service.AppUtil;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.util.WorkflowUtil;
import java.util.Map;

public class CustomerNotificationTool extends DefaultApplicationPlugin {
    
    @Override
    public String getName() {
        return "CustomerNotificationTool";
    }
    
    @Override
    public String getVersion() {
        return "1.0.0";
    }
    
    @Override
    public String getLabel() {
        return "Customer Notification Tool";
    }
    
    @Override
    public String getDescription() {
        return "Sends customized notifications to customers";
    }
    
    @Override
    public String getClassName() {
        return getClass().getName();
    }
    
    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(
            getClass().getName(),
            "/properties/customerNotificationTool.json",
            null, true, null
        );
    }
    
    @Override
    public Object execute(Map properties) {
        WorkflowAssignment assignment = 
            (WorkflowAssignment) properties.get("workflowAssignment");
        
        if (assignment != null) {
            String processId = assignment.getProcessId();
            String activityId = assignment.getActivityId();
            
            // Get configured properties
            String emailTemplate = getPropertyString("emailTemplate");
            String recipientField = getPropertyString("recipientField");
            
            // Get workflow variables
            String recipient = WorkflowUtil.getProcessVariable(
                processId, recipientField
            );
            
            // Send notification
            try {
                sendCustomerEmail(recipient, emailTemplate, processId);
                return "Email sent successfully to " + recipient;
            } catch (Exception e) {
                LogUtil.error(getClassName(), e, "Failed to send email");
                return "Error: " + e.getMessage();
            }
        }
        
        return "No workflow assignment found";
    }
    
    private void sendCustomerEmail(String recipient, String template, String processId) {
        // Email sending logic here
        AppUtil.sendEmail(
            recipient,
            null,
            "Customer Notification",
            processEmailTemplate(template, processId),
            null
        );
    }
}
```

**Property Configuration (customerNotificationTool.json)**:

```json
[{
    "title": "Email Configuration",
    "properties": [{
        "name": "emailTemplate",
        "label": "Email Template",
        "type": "codeeditor",
        "mode": "html",
        "required": "true",
        "description": "HTML template for the email"
    }, {
        "name": "recipientField",
        "label": "Recipient Variable",
        "type": "textfield",
        "value": "customerEmail",
        "description": "Workflow variable containing recipient email"
    }]
}]
```

### Decision Plugin

**Purpose**: Make routing decisions in workflow based on custom logic

**Base Class**: `DefaultApplicationPlugin`

**When to Use**:
- Complex approval logic
- Dynamic routing based on data
- External system decisions
- Business rule evaluation

**Implementation Example**:

```java
public class ApprovalDecisionPlugin extends DefaultApplicationPlugin {
    
    @Override
    public Object execute(Map properties) {
        WorkflowAssignment assignment = 
            (WorkflowAssignment) properties.get("workflowAssignment");
        
        // Get threshold from configuration
        double threshold = Double.parseDouble(
            getPropertyString("approvalThreshold")
        );
        
        // Get amount from workflow variable
        String amountStr = WorkflowUtil.getProcessVariable(
            assignment.getProcessId(), "requestAmount"
        );
        double amount = Double.parseDouble(amountStr);
        
        // Make decision
        if (amount > threshold) {
            return "requiresApproval";  // Route name
        } else {
            return "autoApproved";       // Route name
        }
    }
}
```

### Deadline Plugin

**Purpose**: Handle SLA and deadline events in workflows

**Base Class**: `DefaultApplicationPlugin`

**When to Use**:
- Escalation procedures
- Reminder notifications
- Timeout handling
- SLA monitoring

---

## Form Plugins

### Form Element Plugin

**Purpose**: Create custom form fields with specialized functionality

**Base Class**: `Element`

**Interfaces**: `FormBuilderPalette`, `FormBuilderEditable`

**When to Use**:
- Custom input controls
- Specialized data displays
- Complex field interactions
- Third-party widget integration

**Implementation Example**:

```java
package com.company.plugin;

import org.joget.apps.form.model.Element;
import org.joget.apps.form.model.FormBuilderPalette;
import org.joget.apps.form.model.FormBuilderEditable;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.apps.form.service.FormUtil;

public class RatingField extends Element 
    implements FormBuilderPalette, FormBuilderEditable {
    
    @Override
    public String getName() {
        return "RatingField";
    }
    
    @Override
    public String getVersion() {
        return "1.0.0";
    }
    
    @Override
    public String getLabel() {
        return "Star Rating";
    }
    
    @Override
    public String getClassName() {
        return getClass().getName();
    }
    
    @Override
    public String getIcon() {
        return "/plugin/ratingfield/images/icon.png";
    }
    
    @Override
    public String getFormBuilderCategory() {
        return FormBuilderPalette.CATEGORY_CUSTOM;
    }
    
    @Override
    public String renderTemplate(FormData formData, Map dataModel) {
        String template = "ratingField.ftl";
        
        // Prepare data model
        dataModel.put("elementId", getPropertyString("id"));
        dataModel.put("elementValue", getValue(formData));
        dataModel.put("maxRating", getPropertyString("maxRating"));
        dataModel.put("readonly", "true".equals(getPropertyString("readonly")));
        
        String html = FormUtil.generateElementHtml(
            this, formData, template, dataModel
        );
        return html;
    }
    
    @Override
    public FormData formatDataForValidation(FormData formData) {
        String id = getPropertyString("id");
        String value = FormUtil.getRequestParameter(this, formData, id);
        
        if (value != null && !value.isEmpty()) {
            // Validate rating is within range
            int rating = Integer.parseInt(value);
            int maxRating = Integer.parseInt(
                getPropertyString("maxRating", "5")
            );
            
            if (rating < 0 || rating > maxRating) {
                formData.addFormError(id, "Invalid rating value");
            }
        }
        
        return formData;
    }
    
    @Override
    public FormRowSet formatData(FormData formData) {
        FormRowSet rowSet = null;
        String id = getPropertyString("id");
        
        if (id != null) {
            String value = FormUtil.getRequestParameter(this, formData, id);
            
            if (value != null && !value.isEmpty()) {
                FormRow row = new FormRow();
                row.setProperty(id, value);
                rowSet = new FormRowSet();
                rowSet.add(row);
            }
        }
        
        return rowSet;
    }
    
    @Override
    public String getFormBuilderTemplate() {
        return "<label class='label'>Star Rating</label>";
    }
}
```

**Template (ratingField.ftl)**:

```html
<div class="form-cell rating-field">
    <label class="label">${element.properties.label!}</label>
    <div class="rating-container" data-max="${maxRating!}">
        <#list 1..maxRating?number as i>
            <span class="star ${(elementValue?number >= i)?then('filled', '')}" 
                  data-rating="${i}"
                  <#if !readonly>onclick="setRating(${i})"</#if>>
                ★
            </span>
        </#list>
        <input type="hidden" id="${elementId}" 
               name="${elementId}" 
               value="${elementValue!}"/>
    </div>
    <span class="form-clear"></span>
</div>

<script>
function setRating(rating) {
    document.getElementById('${elementId}').value = rating;
    // Update star display
    var stars = document.querySelectorAll('.rating-container .star');
    stars.forEach(function(star, index) {
        star.classList.toggle('filled', index < rating);
    });
}
</script>

<style>
.star {
    font-size: 24px;
    color: #ddd;
    cursor: pointer;
}
.star.filled {
    color: gold;
}
.star:hover {
    color: orange;
}
</style>
```

### Form Validator Plugin

**Purpose**: Implement custom validation logic for form fields

**Base Class**: `DefaultFormValidator`

**Interface**: `FormValidator`

**When to Use**:
- Complex validation rules
- Cross-field validation
- External system validation
- Business rule enforcement

**Implementation Example**:

```java
public class BusinessRuleValidator extends DefaultFormValidator {
    
    @Override
    public String getName() {
        return "BusinessRuleValidator";
    }
    
    @Override
    public String getVersion() {
        return "1.0.0";
    }
    
    @Override
    public String getLabel() {
        return "Business Rule Validator";
    }
    
    @Override
    public boolean validate(Element element, FormData formData, String[] values) {
        boolean valid = true;
        String fieldId = FormUtil.getElementParameterName(element);
        
        // Get configuration
        String ruleType = getPropertyString("ruleType");
        
        if ("creditLimit".equals(ruleType)) {
            // Validate against credit limit
            double amount = Double.parseDouble(values[0]);
            String customerId = formData.getRequestParameter("customerId");
            
            double creditLimit = getCreditLimit(customerId);
            
            if (amount > creditLimit) {
                String message = getPropertyString("errorMessage");
                message = message.replace("{limit}", String.valueOf(creditLimit));
                formData.addFormError(fieldId, message);
                valid = false;
            }
        }
        
        return valid;
    }
    
    private double getCreditLimit(String customerId) {
        // Query database for customer credit limit
        // This is simplified - use proper DAO in production
        return 10000.00; 
    }
}
```

### Form Load Binder Plugin

**Purpose**: Load data into forms from custom data sources

**Base Class**: `DefaultFormBinder`

**Interface**: `FormLoadBinder`

**When to Use**:
- Loading from external APIs
- Complex data transformations
- Multi-source data aggregation
- Custom data formats

**Implementation Example**:

```java
public class RestApiLoadBinder extends DefaultFormBinder 
    implements FormLoadBinder {
    
    @Override
    public FormRowSet load(Element element, String primaryKey, FormData formData) {
        FormRowSet rowSet = new FormRowSet();
        
        try {
            // Get configuration
            String apiUrl = getPropertyString("apiUrl");
            String apiKey = getPropertyString("apiKey");
            
            // Call API
            String url = apiUrl + "/" + primaryKey;
            HttpGet request = new HttpGet(url);
            request.setHeader("Authorization", "Bearer " + apiKey);
            
            CloseableHttpClient client = HttpClients.createDefault();
            CloseableHttpResponse response = client.execute(request);
            
            String json = EntityUtils.toString(response.getEntity());
            JSONObject data = new JSONObject(json);
            
            // Convert to FormRow
            FormRow row = new FormRow();
            Iterator<String> keys = data.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                row.setProperty(key, data.getString(key));
            }
            
            rowSet.add(row);
            
        } catch (Exception e) {
            LogUtil.error(getClassName(), e, "Failed to load from API");
        }
        
        return rowSet;
    }
}
```

### Form Store Binder Plugin

**Purpose**: Save form data to custom destinations

**Base Class**: `DefaultFormBinder`

**Interface**: `FormStoreBinder`

**When to Use**:
- Saving to external systems
- Custom data persistence
- Multi-destination storage
- Data synchronization

**Implementation Example**:

```java
public class RestApiStoreBinder extends DefaultFormBinder 
    implements FormStoreBinder {
    
    @Override
    public FormRowSet store(Element element, FormRowSet rows, FormData formData) {
        
        try {
            // Get configuration
            String apiUrl = getPropertyString("apiUrl");
            String apiKey = getPropertyString("apiKey");
            
            for (FormRow row : rows) {
                // Convert FormRow to JSON
                JSONObject json = new JSONObject();
                for (String key : row.keySet()) {
                    json.put(key, row.getProperty(key));
                }
                
                // Send to API
                HttpPost request = new HttpPost(apiUrl);
                request.setHeader("Authorization", "Bearer " + apiKey);
                request.setHeader("Content-Type", "application/json");
                request.setEntity(new StringEntity(json.toString()));
                
                CloseableHttpClient client = HttpClients.createDefault();
                CloseableHttpResponse response = client.execute(request);
                
                if (response.getStatusLine().getStatusCode() == 200) {
                    // Update row with response (e.g., generated ID)
                    String responseJson = EntityUtils.toString(response.getEntity());
                    JSONObject responseData = new JSONObject(responseJson);
                    
                    if (responseData.has("id")) {
                        row.setProperty("id", responseData.getString("id"));
                    }
                }
            }
            
        } catch (Exception e) {
            LogUtil.error(getClassName(), e, "Failed to store to API");
        }
        
        return rows;
    }
}
```

### Form Options Binder Plugin

**Purpose**: Provide dynamic options for selection fields

**Base Class**: `DefaultFormBinder`

**Interface**: `FormLoadOptionsBinder`

**When to Use**:
- Dynamic dropdown options
- Cascading selects
- External data sources
- Filtered options

**Implementation Example**:

```java
public class DynamicOptionsBinder extends DefaultFormBinder 
    implements FormLoadOptionsBinder {
    
    @Override
    public FormRowSet load(Element element, String primaryKey, FormData formData) {
        FormRowSet options = new FormRowSet();
        
        // Get filter value from another field
        String filterField = getPropertyString("filterField");
        String filterValue = formData.getRequestParameter(filterField);
        
        // Load options based on filter
        if (filterValue != null && !filterValue.isEmpty()) {
            List<Map<String, String>> optionList = loadFilteredOptions(filterValue);
            
            for (Map<String, String> option : optionList) {
                FormRow row = new FormRow();
                row.setProperty(FormUtil.PROPERTY_VALUE, option.get("value"));
                row.setProperty(FormUtil.PROPERTY_LABEL, option.get("label"));
                options.add(row);
            }
        }
        
        return options;
    }
    
    private List<Map<String, String>> loadFilteredOptions(String filter) {
        List<Map<String, String>> options = new ArrayList<>();
        
        // Load from database or API based on filter
        // This is simplified example
        if ("category1".equals(filter)) {
            options.add(createOption("opt1", "Option 1"));
            options.add(createOption("opt2", "Option 2"));
        } else if ("category2".equals(filter)) {
            options.add(createOption("opt3", "Option 3"));
            options.add(createOption("opt4", "Option 4"));
        }
        
        return options;
    }
    
    private Map<String, String> createOption(String value, String label) {
        Map<String, String> option = new HashMap<>();
        option.put("value", value);
        option.put("label", label);
        return option;
    }
}
```

---

## DataList Plugins

### DataList Binder Plugin

**Purpose**: Provide data source for data lists

**Base Class**: `DefaultDataListBinder`

**Interface**: `DataListBinder`

**When to Use**:
- Custom data sources
- Complex queries
- External system integration
- Data aggregation

**Implementation Example**:

```java
public class ElasticsearchDataListBinder extends DefaultDataListBinder {
    
    @Override
    public DataListCollection getData(DataList dataList, Map properties, 
                                     DataListFilterQueryObject[] filterQueryObjects, 
                                     String sort, Boolean desc, 
                                     Integer start, Integer rows) {
        
        DataListCollection collection = new DataListCollection();
        
        try {
            // Get configuration
            String indexName = getPropertyString("indexName");
            String documentType = getPropertyString("documentType");
            
            // Build Elasticsearch query
            SearchRequest searchRequest = new SearchRequest(indexName);
            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            
            // Add filters
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
            for (DataListFilterQueryObject filter : filterQueryObjects) {
                if (filter.getOperator().equals("=")) {
                    boolQuery.must(QueryBuilders.termQuery(
                        filter.getField(), filter.getValue()
                    ));
                }
            }
            
            searchSourceBuilder.query(boolQuery);
            searchSourceBuilder.from(start);
            searchSourceBuilder.size(rows);
            
            // Add sorting
            if (sort != null && !sort.isEmpty()) {
                searchSourceBuilder.sort(sort, 
                    desc ? SortOrder.DESC : SortOrder.ASC);
            }
            
            searchRequest.source(searchSourceBuilder);
            
            // Execute search
            SearchResponse searchResponse = getElasticsearchClient()
                .search(searchRequest, RequestOptions.DEFAULT);
            
            // Convert results
            for (SearchHit hit : searchResponse.getHits()) {
                Map<String, Object> source = hit.getSourceAsMap();
                collection.add(source);
            }
            
            // Set total count
            collection.setTotal((int) searchResponse.getHits().getTotalHits().value);
            
        } catch (Exception e) {
            LogUtil.error(getClassName(), e, "Failed to search Elasticsearch");
        }
        
        return collection;
    }
    
    @Override
    public int getDataTotalRowCount(DataList dataList, Map properties, 
                                   DataListFilterQueryObject[] filterQueryObjects) {
        // Return total count for pagination
        DataListCollection data = getData(dataList, properties, 
            filterQueryObjects, null, false, 0, 1);
        return data.getTotal();
    }
}
```

### DataList Action Plugin

**Purpose**: Add custom actions to data list rows

**Base Class**: `DefaultDataListAction`

**Interface**: `DataListAction`

**When to Use**:
- Custom row operations
- Bulk actions
- External system triggers
- Complex workflows

**Implementation Example**:

```java
public class ExportToExcelAction extends DefaultDataListAction {
    
    @Override
    public String getLinkLabel() {
        return getPropertyString("label");
    }
    
    @Override
    public String getHref() {
        return getPropertyString("href");
    }
    
    @Override
    public String executeAction(DataList dataList, String[] rowKeys) {
        try {
            // Create Excel workbook
            XSSFWorkbook workbook = new XSSFWorkbook();
            XSSFSheet sheet = workbook.createSheet("Data Export");
            
            // Add headers
            DataListColumn[] columns = dataList.getColumns();
            XSSFRow headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.length; i++) {
                headerRow.createCell(i).setCellValue(columns[i].getLabel());
            }
            
            // Add data rows
            int rowNum = 1;
            for (String rowKey : rowKeys) {
                XSSFRow row = sheet.createRow(rowNum++);
                
                // Get row data
                Map<String, Object> rowData = getRowData(dataList, rowKey);
                
                int cellNum = 0;
                for (DataListColumn column : columns) {
                    String value = String.valueOf(
                        rowData.get(column.getName())
                    );
                    row.createCell(cellNum++).setCellValue(value);
                }
            }
            
            // Save to temporary file
            String fileName = "export_" + System.currentTimeMillis() + ".xlsx";
            File tempFile = new File(System.getProperty("java.io.tmpdir"), fileName);
            FileOutputStream outputStream = new FileOutputStream(tempFile);
            workbook.write(outputStream);
            workbook.close();
            
            // Return download URL
            return "/web/json/app/downloadFile?file=" + tempFile.getAbsolutePath();
            
        } catch (Exception e) {
            LogUtil.error(getClassName(), e, "Failed to export to Excel");
            return null;
        }
    }
}
```

### DataList Formatter Plugin

**Purpose**: Format display of data list column values

**Base Class**: `DefaultDataListColumnFormatter`

**Interface**: `DataListColumnFormatter`

**When to Use**:
- Custom data formatting
- HTML rendering
- Status indicators
- Data transformations

**Implementation Example**:

```java
public class StatusIndicatorFormatter extends DefaultDataListColumnFormatter {
    
    @Override
    public String format(DataList dataList, DataListColumn column, 
                        Object row, Object value) {
        
        String status = (value != null) ? value.toString() : "";
        String color = getColorForStatus(status);
        String icon = getIconForStatus(status);
        
        // Return formatted HTML
        return String.format(
            "<span class='status-indicator' style='color: %s;'>" +
            "<i class='%s'></i> %s</span>",
            color, icon, status
        );
    }
    
    private String getColorForStatus(String status) {
        switch (status.toLowerCase()) {
            case "active":
            case "approved":
                return "green";
            case "pending":
                return "orange";
            case "rejected":
            case "inactive":
                return "red";
            default:
                return "gray";
        }
    }
    
    private String getIconForStatus(String status) {
        switch (status.toLowerCase()) {
            case "active":
            case "approved":
                return "fas fa-check-circle";
            case "pending":
                return "fas fa-clock";
            case "rejected":
            case "inactive":
                return "fas fa-times-circle";
            default:
                return "fas fa-question-circle";
        }
    }
}
```

---

## Userview Plugins

### Userview Menu Plugin

**Purpose**: Create custom menu items and pages in userviews

**Base Class**: `UserviewMenu`

**When to Use**:
- Custom pages
- External content integration
- Dynamic dashboards
- Special UI components

**Implementation Example**:

```java
public class DashboardMenu extends UserviewMenu {
    
    @Override
    public String getLabel() {
        return getPropertyString("label");
    }
    
    @Override
    public String getClassName() {
        return getClass().getName();
    }
    
    @Override
    public String getIcon() {
        return "<i class='fas fa-dashboard'></i>";
    }
    
    @Override
    public String getRenderPage() {
        Map model = new HashMap();
        model.put("request", getRequestParameters());
        model.put("userview", getUserview());
        
        // Load dashboard data
        model.put("stats", loadDashboardStatistics());
        model.put("charts", generateCharts());
        model.put("activities", getRecentActivities());
        
        // Render template
        String template = getPropertyString("template");
        if (template == null || template.isEmpty()) {
            template = getDefaultTemplate();
        }
        
        PluginManager pluginManager = (PluginManager) AppUtil
            .getApplicationContext().getBean("pluginManager");
        
        return pluginManager.getPluginFreeMarkerTemplate(
            model, getClassName(), "/templates/dashboard.ftl", null
        );
    }
    
    private Map<String, Object> loadDashboardStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        // Load various statistics
        stats.put("totalUsers", getUserCount());
        stats.put("activeProcesses", getActiveProcessCount());
        stats.put("completedTasks", getCompletedTaskCount());
        stats.put("pendingApprovals", getPendingApprovalCount());
        
        return stats;
    }
    
    private String getDefaultTemplate() {
        return "<div class='dashboard'>" +
               "  <h2>Dashboard</h2>" +
               "  <div class='stats-grid'>" +
               "    <#list stats?keys as key>" +
               "      <div class='stat-card'>" +
               "        <h3>${key}</h3>" +
               "        <p>${stats[key]}</p>" +
               "      </div>" +
               "    </#list>" +
               "  </div>" +
               "</div>";
    }
}
```

### Userview Theme Plugin

**Purpose**: Create custom themes for userviews

**Base Class**: `DefaultUserviewTheme`

**Interface**: `UserviewTheme`

**When to Use**:
- Custom branding
- Responsive designs
- Special layouts
- Advanced UI features

---

## System Plugins

### Hash Variable Plugin

**Purpose**: Create custom hash variables for dynamic content

**Base Class**: `DefaultHashVariablePlugin`

**Interface**: `HashVariablePlugin`

**When to Use**:
- Dynamic content generation
- System information access
- Calculations
- External data integration

**Implementation Example**:

```java
public class SystemInfoHashVariable extends DefaultHashVariablePlugin {
    
    @Override
    public String getName() {
        return "System Information Hash Variable";
    }
    
    @Override
    public String getPrefix() {
        return "system";
    }
    
    @Override
    public String getVersion() {
        return "1.0.0";
    }
    
    @Override
    public String getLabel() {
        return "System Information";
    }
    
    @Override
    public String getClassName() {
        return getClass().getName();
    }
    
    @Override
    public String processHashVariable(String variableKey) {
        // Parse variable key
        // Format: #system.TYPE.PROPERTY#
        String[] parts = variableKey.split("\\.");
        
        if (parts.length < 1) {
            return "";
        }
        
        String type = parts[0];
        String property = (parts.length > 1) ? parts[1] : "";
        
        switch (type) {
            case "memory":
                return getMemoryInfo(property);
            case "cpu":
                return getCpuInfo(property);
            case "disk":
                return getDiskInfo(property);
            case "time":
                return getTimeInfo(property);
            case "server":
                return getServerInfo(property);
            default:
                return "";
        }
    }
    
    private String getMemoryInfo(String property) {
        Runtime runtime = Runtime.getRuntime();
        
        switch (property) {
            case "total":
                return formatBytes(runtime.totalMemory());
            case "free":
                return formatBytes(runtime.freeMemory());
            case "used":
                return formatBytes(runtime.totalMemory() - runtime.freeMemory());
            case "max":
                return formatBytes(runtime.maxMemory());
            default:
                return "";
        }
    }
    
    private String getTimeInfo(String property) {
        switch (property) {
            case "current":
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new Date());
            case "timestamp":
                return String.valueOf(System.currentTimeMillis());
            case "timezone":
                return TimeZone.getDefault().getID();
            default:
                return "";
        }
    }
    
    @Override
    public Collection<String> availableSyntax() {
        Collection<String> syntax = new ArrayList<>();
        syntax.add("#system.memory.total#");
        syntax.add("#system.memory.free#");
        syntax.add("#system.memory.used#");
        syntax.add("#system.time.current#");
        syntax.add("#system.time.timestamp#");
        syntax.add("#system.server.name#");
        return syntax;
    }
}
```

### Audit Trail Plugin

**Purpose**: Custom audit logging and monitoring

**Base Class**: `DefaultAuditTrailPlugin`

**Interface**: `AuditTrailPlugin`

**When to Use**:
- Compliance logging
- Security monitoring
- Activity tracking
- Custom audit requirements

**Implementation Example**:

```java
public class ComplianceAuditPlugin extends DefaultAuditTrailPlugin {
    
    @Override
    public Object execute(Map properties) {
        AuditTrail auditTrail = (AuditTrail) properties.get("auditTrail");
        
        if (auditTrail != null) {
            // Log to external compliance system
            logToComplianceSystem(auditTrail);
            
            // Check for suspicious patterns
            if (isSuspiciousActivity(auditTrail)) {
                sendSecurityAlert(auditTrail);
            }
            
            // Archive for long-term storage
            archiveAuditRecord(auditTrail);
        }
        
        return null;
    }
    
    private void logToComplianceSystem(AuditTrail auditTrail) {
        // Send to external SIEM or compliance system
        String message = String.format(
            "Action: %s, User: %s, Time: %s, Data: %s",
            auditTrail.getAction(),
            auditTrail.getUsername(),
            auditTrail.getTimestamp(),
            auditTrail.getData()
        );
        
        // Send to external system
        sendToSIEM(message);
    }
    
    private boolean isSuspiciousActivity(AuditTrail auditTrail) {
        // Check for patterns like:
        // - Multiple failed logins
        // - Unusual access times
        // - Sensitive data access
        
        String action = auditTrail.getAction();
        String username = auditTrail.getUsername();
        
        if ("LOGIN_FAILED".equals(action)) {
            int failedAttempts = getRecentFailedLogins(username);
            return failedAttempts > 3;
        }
        
        return false;
    }
}
```

---

## Web Service Plugins

### Web Service Plugin

**Purpose**: Create custom REST endpoints

**Interface**: `PluginWebSupport`

**When to Use**:
- External API integration
- Custom REST services
- Webhook endpoints
- Third-party callbacks

**Implementation Example**:

```java
public class CustomApiPlugin extends DefaultApplicationPlugin 
    implements PluginWebSupport {
    
    @Override
    public void webService(HttpServletRequest request, 
                          HttpServletResponse response) 
                          throws ServletException, IOException {
        
        String method = request.getMethod();
        String pathInfo = request.getPathInfo();
        
        try {
            if ("GET".equals(method)) {
                handleGet(request, response);
            } else if ("POST".equals(method)) {
                handlePost(request, response);
            } else if ("PUT".equals(method)) {
                handlePut(request, response);
            } else if ("DELETE".equals(method)) {
                handleDelete(request, response);
            } else {
                response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
        } catch (Exception e) {
            LogUtil.error(getClassName(), e, "API error");
            sendJsonError(response, e.getMessage());
        }
    }
    
    private void handleGet(HttpServletRequest request, 
                          HttpServletResponse response) 
                          throws IOException {
        
        String id = request.getParameter("id");
        
        if (id != null && !id.isEmpty()) {
            // Get single record
            Map<String, Object> record = getRecord(id);
            sendJsonResponse(response, record);
        } else {
            // Get list
            List<Map<String, Object>> records = getRecords();
            sendJsonResponse(response, records);
        }
    }
    
    private void handlePost(HttpServletRequest request, 
                           HttpServletResponse response) 
                           throws IOException {
        
        // Read JSON body
        String body = IOUtils.toString(request.getReader());
        JSONObject json = new JSONObject(body);
        
        // Process data
        String id = createRecord(json);
        
        // Return created record
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("status", "created");
        
        response.setStatus(HttpServletResponse.SC_CREATED);
        sendJsonResponse(response, result);
    }
    
    private void sendJsonResponse(HttpServletResponse response, 
                                 Object data) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        JSONObject json = new JSONObject(data);
        response.getWriter().write(json.toString());
    }
}
```

### WebSocket Plugin

**Purpose**: Real-time bidirectional communication

**Interface**: `PluginWebSocket`

**When to Use**:
- Real-time notifications
- Live updates
- Chat functionality
- Collaborative features

**Implementation Example**:

```java
public class RealtimeNotificationPlugin extends DefaultApplicationPlugin 
    implements PluginWebSocket {
    
    private static final Map<String, Session> sessions = 
        new ConcurrentHashMap<>();
    
    @Override
    public void onOpen(Session session) {
        String userId = getUserId(session);
        sessions.put(userId, session);
        
        LogUtil.info(getClassName(), "WebSocket opened for user: " + userId);
        
        // Send welcome message
        try {
            session.getBasicRemote().sendText(
                "{\"type\":\"connected\",\"message\":\"Welcome!\"}"
            );
        } catch (IOException e) {
            LogUtil.error(getClassName(), e, "Failed to send welcome");
        }
    }
    
    @Override
    public void onMessage(String message, Session session) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.getString("type");
            
            switch (type) {
                case "subscribe":
                    handleSubscribe(json, session);
                    break;
                case "unsubscribe":
                    handleUnsubscribe(json, session);
                    break;
                case "ping":
                    session.getBasicRemote().sendText("{\"type\":\"pong\"}");
                    break;
                default:
                    handleCustomMessage(json, session);
            }
            
        } catch (Exception e) {
            LogUtil.error(getClassName(), e, "Failed to process message");
        }
    }
    
    @Override
    public void onClose(Session session) {
        String userId = getUserId(session);
        sessions.remove(userId);
        
        LogUtil.info(getClassName(), "WebSocket closed for user: " + userId);
    }
    
    @Override
    public void onError(Session session, Throwable throwable) {
        LogUtil.error(getClassName(), throwable, "WebSocket error");
    }
    
    // Broadcast notification to all connected users
    public static void broadcastNotification(String message) {
        JSONObject notification = new JSONObject();
        notification.put("type", "notification");
        notification.put("message", message);
        notification.put("timestamp", System.currentTimeMillis());
        
        String json = notification.toString();
        
        for (Session session : sessions.values()) {
            if (session.isOpen()) {
                try {
                    session.getBasicRemote().sendText(json);
                } catch (IOException e) {
                    LogUtil.error("RealtimeNotificationPlugin", e, 
                        "Failed to send notification");
                }
            }
        }
    }
}
```

---

## API Plugins

**Purpose**: Extend Joget's REST API with custom endpoints

**Base Class**: Varies based on implementation

**When to Use**:
- Custom API operations
- External system integration
- Mobile app backends
- Microservice architecture

---

## Permission Plugins

**Purpose**: Implement custom access control logic

**Base Class**: `DefaultUserviewPermission` or `DefaultFormPermission`

**Interface**: `UserviewPermission` or `FormPermission`

**When to Use**:
- Role-based access control
- Dynamic permissions
- External authorization
- Complex security rules

**Implementation Example**:

```java
public class DepartmentPermission extends DefaultUserviewPermission {
    
    @Override
    public boolean isAuthorized() {
        User currentUser = getCurrentUser();
        
        if (currentUser == null) {
            return false;
        }
        
        // Get allowed departments from configuration
        String allowedDepts = getPropertyString("departments");
        String[] departments = allowedDepts.split(",");
        
        // Check user's department
        String userDept = getUserDepartment(currentUser.getUsername());
        
        for (String dept : departments) {
            if (dept.trim().equals(userDept)) {
                return true;
            }
        }
        
        return false;
    }
    
    private String getUserDepartment(String username) {
        // Get user's department from directory or database
        DirectoryManager directoryManager = (DirectoryManager) AppUtil
            .getApplicationContext().getBean("directoryManager");
        
        User user = directoryManager.getUserByUsername(username);
        if (user != null) {
            // Assuming department is stored as an extended attribute
            return user.getPropertyString("department");
        }
        
        return null;
    }
}
```

---

## Mobile Plugins

**Purpose**: Mobile-specific functionality and UI

**When to Use**:
- Mobile app integration
- Push notifications
- Device features
- Mobile-optimized UI

---

## Plugin Development Patterns

### Pattern 1: Configuration-Driven Plugin

```java
public class ConfigurablePlugin extends DefaultApplicationPlugin {
    
    @Override
    public Object execute(Map properties) {
        // Load configuration
        Map<String, String> config = loadConfiguration();
        
        // Execute based on configuration
        String mode = config.get("mode");
        
        switch (mode) {
            case "sync":
                return executeSync(config);
            case "async":
                return executeAsync(config);
            case "batch":
                return executeBatch(config);
            default:
                return "Unknown mode: " + mode;
        }
    }
    
    private Map<String, String> loadConfiguration() {
        Map<String, String> config = new HashMap<>();
        
        // Load from properties
        for (String key : getProperties().keySet()) {
            config.put(key, getPropertyString(key));
        }
        
        // Load from environment variables
        Map<String, String> env = System.getenv();
        for (String key : env.keySet()) {
            if (key.startsWith("PLUGIN_")) {
                config.put(key.substring(7), env.get(key));
            }
        }
        
        return config;
    }
}
```

### Pattern 2: Event-Driven Plugin

```java
public class EventDrivenPlugin extends DefaultApplicationPlugin {
    
    private static final EventBus eventBus = new EventBus();
    
    @Override
    public Object execute(Map properties) {
        // Register event handlers
        eventBus.register(new EventHandler());
        
        // Trigger event
        String eventType = getPropertyString("eventType");
        Event event = createEvent(eventType, properties);
        
        eventBus.post(event);
        
        return "Event triggered: " + eventType;
    }
    
    class EventHandler {
        @Subscribe
        public void handleFormSubmitted(FormSubmittedEvent event) {
            // Handle form submission
            processFormSubmission(event.getFormData());
        }
        
        @Subscribe
        public void handleWorkflowCompleted(WorkflowCompletedEvent event) {
            // Handle workflow completion
            processWorkflowCompletion(event.getProcessId());
        }
    }
}
```

### Pattern 3: Async Processing Plugin

```java
public class AsyncPlugin extends DefaultApplicationPlugin {
    
    private static final ExecutorService executor = 
        Executors.newFixedThreadPool(10);
    
    @Override
    public Object execute(Map properties) {
        String taskId = UUID.randomUUID().toString();
        
        // Submit async task
        CompletableFuture<String> future = CompletableFuture
            .supplyAsync(() -> {
                return performLongRunningTask(properties);
            }, executor)
            .thenApply(result -> {
                // Post-processing
                return processResult(result);
            })
            .exceptionally(error -> {
                LogUtil.error(getClassName(), error, "Async task failed");
                return "Error: " + error.getMessage();
            });
        
        // Store future for later retrieval
        storeTask(taskId, future);
        
        return "Task submitted: " + taskId;
    }
    
    private String performLongRunningTask(Map properties) {
        // Simulate long-running operation
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        return "Task completed";
    }
}
```

---

## Choosing the Right Plugin Type

### Decision Matrix

| Need | Recommended Plugin Type | Alternative Options |
|------|------------------------|-------------------|
| Execute business logic in workflow | Process Tool | Decision Plugin |
| Custom form field | Form Element | Custom HTML |
| Validate form data | Form Validator | Process Tool validation |
| Load data into form | Form Load Binder | Process Tool |
| Save form data | Form Store Binder | Process Tool |
| Display data in list | DataList Binder | Custom SQL |
| Format list column | DataList Formatter | CSS styling |
| Add list action | DataList Action | JavaScript |
| Custom page/menu | Userview Menu | External URL |
| Dynamic content | Hash Variable | Form Binder |
| REST API | Web Service Plugin | API Plugin |
| Real-time updates | WebSocket Plugin | Polling |
| Access control | Permission Plugin | Workflow routing |
| Audit logging | Audit Trail Plugin | Process Tool |

### Plugin Selection Flowchart

```
Start
  ↓
Is it workflow-related?
  ├─ Yes → Process Tool or Decision Plugin
  └─ No ↓
      Is it form-related?
        ├─ Yes → Form Element/Validator/Binder
        └─ No ↓
            Is it data display?
              ├─ Yes → DataList Plugin
              └─ No ↓
                  Is it UI/UX?
                    ├─ Yes → Userview Plugin
                    └─ No ↓
                        Is it system-wide?
                          ├─ Yes → Hash Variable/Audit
                          └─ No → Web Service/API
```

---

## Best Practices

### 1. Plugin Design

- **Single Responsibility**: Each plugin should do one thing well
- **Configuration over Code**: Make plugins configurable
- **Error Handling**: Always handle exceptions gracefully
- **Logging**: Use appropriate log levels
- **Documentation**: Document configuration options

### 2. Performance

- **Caching**: Cache expensive operations
- **Lazy Loading**: Load data only when needed
- **Connection Pooling**: Reuse database connections
- **Async Processing**: Use async for long operations
- **Resource Cleanup**: Always clean up resources

### 3. Security

- **Input Validation**: Validate all inputs
- **SQL Injection**: Use parameterized queries
- **XSS Prevention**: Escape output
- **Authentication**: Verify user permissions
- **Encryption**: Protect sensitive data

### 4. Testing

```java
public class PluginTest {
    
    @Test
    public void testPluginExecution() {
        // Create plugin instance
        MyPlugin plugin = new MyPlugin();
        
        // Set properties
        Map<String, Object> props = new HashMap<>();
        props.put("config1", "value1");
        plugin.setProperties(props);
        
        // Execute
        Object result = plugin.execute(props);
        
        // Assert
        assertNotNull(result);
        assertEquals("Expected result", result);
    }
    
    @Test
    public void testErrorHandling() {
        MyPlugin plugin = new MyPlugin();
        
        // Test with invalid input
        Map<String, Object> props = new HashMap<>();
        props.put("invalidConfig", "badValue");
        
        Object result = plugin.execute(props);
        
        // Should handle error gracefully
        assertTrue(result.toString().contains("Error"));
    }
}
```

---

## Conclusion

This comprehensive guide covers all major plugin types in Joget DX8. Each plugin type serves a specific purpose in extending the platform's capabilities. Key takeaways:

1. **Choose the Right Type**: Select plugin type based on your specific needs
2. **Follow Patterns**: Use established patterns for common scenarios
3. **Consider Performance**: Design for scalability and efficiency
4. **Ensure Security**: Implement proper security measures
5. **Test Thoroughly**: Test all scenarios including error cases

Remember that plugins are powerful tools for extending Joget, but with power comes responsibility. Always consider the impact on system performance, security, and maintainability when developing plugins.

For specific implementation details, refer to:
- Joget Knowledge Base: https://dev.joget.org/community/display/DX8/
- GitHub Examples: https://github.com/jogetworkflow/jw-community
- API Documentation: Your IntelliJ IDE with proper Joget dependencies