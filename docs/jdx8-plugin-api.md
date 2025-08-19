# Joget DX8 Plugin API - Complete Reference Guide

## Table of Contents

1. [Core Services API](#core-services-api)
   - [AppUtil](#apputil)
   - [FormUtil](#formutil)
   - [AppService](#appservice)
   - [FormService](#formservice)
2. [Workflow Management API](#workflow-management-api)
   - [WorkflowManager](#workflowmanager)
   - [WorkflowUserManager](#workflowusermanager)
   - [WorkflowAssignment](#workflowassignment)
3. [Data Management API](#data-management-api)
   - [FormDataDao](#formdatadao)
   - [DataListService](#datalistservice)
   - [FileUtil](#fileutil)
4. [User & Directory API](#user--directory-api)
   - [DirectoryManager](#directorymanager)
   - [UserSecurity](#usersecurity)
5. [Utility APIs](#utility-apis)
   - [SecurityUtil](#securityutil)
   - [StringUtil](#stringutil)
   - [LogUtil](#logutil)
   - [ResourceBundleUtil](#resourcebundleutil)
   - [TimeZoneUtil](#timezoneutil)
6. [UI and Rendering API](#ui-and-rendering-api)
   - [UserviewService](#userviewservice)
   - [FormPdfUtil](#formpdfutil)
7. [Advanced APIs](#advanced-apis)
   - [PropertyUtil](#propertyutil)
   - [PluginManager](#pluginmanager)
   - [SetupManager](#setupmanager)
8. [Integration Patterns](#integration-patterns)
9. [API Best Practices](#api-best-practices)

---

## Core Services API

### AppUtil

**Package**: `org.joget.apps.app.service.AppUtil`

AppUtil is the primary utility class providing access to application context, services, and common operations.

#### Key Methods

##### Application Context

```java
// Get Spring application context
ApplicationContext getApplicationContext()

// Get specific bean from context
Object getBean(String beanName)

// Example usage
ApplicationContext ctx = AppUtil.getApplicationContext();
FormService formService = (FormService) ctx.getBean("formService");
WorkflowManager workflowManager = (WorkflowManager) ctx.getBean("workflowManager");
```

##### Current Application

```java
// Get current application definition
AppDefinition getCurrentAppDefinition()

// Set current application context
void setCurrentAppDefinition(AppDefinition appDef)

// Get current workflow assignment
WorkflowAssignment getCurrentAssignment()

// Example usage
AppDefinition appDef = AppUtil.getCurrentAppDefinition();
if (appDef != null) {
    String appId = appDef.getId();
    String appVersion = appDef.getVersion().toString();
    String appName = appDef.getName();
}
```

##### Hash Variable Processing

```java
// Process hash variables in text
String processHashVariable(String content, WorkflowAssignment assignment, 
                          String escapeFormat, Map<String, String> replaceMap)

// Process with app definition
String processHashVariable(String content, WorkflowAssignment assignment, 
                          String escapeFormat, Map<String, String> replaceMap, 
                          AppDefinition appDef)

// Example usage
String template = "Hello #{currentUser.firstName}, task #{assignment.activityName} is ready";
String processed = AppUtil.processHashVariable(template, assignment, null, null);
// Result: "Hello John, task Approval is ready"
```

##### Email Operations

```java
// Send email
void sendEmail(String toEmail, String toName, String subject, String htmlContent)

// Send email with attachments
boolean sendEmail(String toEmail, String toName, String subject, 
                 String htmlContent, String[] attachmentPaths)

// Send email with full parameters
boolean sendEmail(String toEmail, String toName, String subject, 
                 String htmlContent, boolean isHtml, String fromEmail, 
                 String fromName, String ccEmail, String bccEmail, 
                 String[] attachmentPaths, Map headers, 
                 WorkflowAssignment assignment, AppDefinition appDef)

// Example usage
boolean sent = AppUtil.sendEmail(
    "user@example.com",
    "John Doe",
    "Task Notification",
    "<h1>You have a new task</h1><p>Please review the approval request.</p>",
    true,
    "system@example.com",
    "Workflow System",
    null,
    null,
    new String[]{"/path/to/document.pdf"},
    null,
    assignment,
    appDef
);
```

##### File Operations

```java
// Store file to local repository
String storeFile(FileItem fileItem, String formDefId, String tableName)

// Get file from local repository
File getFile(String fileName, String tableName, String primaryKey)

// Delete file
void deleteFile(String fileName, String tableName, String primaryKey)

// Example usage
FileItem uploadedFile = getUploadedFileItem();
String storedPath = AppUtil.storeFile(uploadedFile, "employeeForm", "app_fd_employee");
```

##### Plugin Resources

```java
// Read plugin resource file
String readPluginResource(String pluginClassName, String resourceUrl)

// Read with app definition context
String readPluginResource(String pluginClassName, String resourceUrl, 
                         Object[] arguments, boolean removeNewLines, 
                         String translationPath)

// Example usage
String jsonConfig = AppUtil.readPluginResource(
    getClass().getName(),
    "/properties/plugin.json",
    null,
    true,
    null
);
```

##### Request Context

```java
// Get request context path
String getRequestContextPath()

// Get current request parameters
Map<String, String> getRequestParameters()

// Check if in admin context
boolean isAdmin()

// Example usage
String contextPath = AppUtil.getRequestContextPath();
String fullUrl = contextPath + "/web/json/plugin/list";
```

---

### FormUtil

**Package**: `org.joget.apps.form.service.FormUtil`

FormUtil provides comprehensive form processing capabilities.

#### Key Methods

##### Element Operations

```java
// Create form element dynamically
Element createElement(String className, Map<String, Object> properties)

// Find element in form by ID
Element findElement(String elementId, Form form, FormData formData)

// Find all elements of specific type
Collection<Element> findElements(Form form, Class<? extends Element> elementClass)

// Get element value
String getElementPropertyValue(Element element, FormData formData)

// Example usage
Element textField = FormUtil.createElement(
    "org.joget.apps.form.lib.TextField",
    properties
);

Element emailField = FormUtil.findElement("email", form, formData);
String emailValue = FormUtil.getElementPropertyValue(emailField, formData);
```

##### Form Data Processing

```java
// Execute load binders
FormData executeLoadBinders(Element element, FormData formData)

// Execute store binders
FormData executeStoreBinders(Element element, FormData formData)

// Execute validators
FormData executeValidators(Element element, FormData formData)

// Execute options binders
FormData executeOptionsBinders(Element element, FormData formData)

// Example usage
public FormData processForm(Form form, String recordId) {
    FormData formData = new FormData();
    formData.setPrimaryKeyValue(recordId);
    
    // Load existing data
    formData = FormUtil.executeLoadBinders(form, formData);
    
    // Load dropdown options
    formData = FormUtil.executeOptionsBinders(form, formData);
    
    // Validate on submission
    formData = FormUtil.executeValidators(form, formData);
    
    // Save if valid
    if (formData.getFormErrors().isEmpty()) {
        formData = FormUtil.executeStoreBinders(form, formData);
    }
    
    return formData;
}
```

##### Form JSON Operations

```java
// Generate element from JSON
Element parseElementFromJson(String json)

// Generate JSON from element
JSONObject generateElementJson(Element element)

// Load form from JSON
Form parseFormFromJson(String json, FormData formData)

// Example usage
String formJson = "{\"className\":\"org.joget.apps.form.model.Form\",\"properties\":{...}}";
Form form = FormUtil.parseFormFromJson(formJson, new FormData());
```

##### Form Rendering

```java
// Generate HTML for element
String generateElementHtml(Element element, FormData formData, 
                          String templatePath, Map dataModel)

// Generate form HTML
String generateFormHtml(Form form, FormData formData)

// Generate element metadata for builder
String generateElementMetaData(Element element)

// Example usage
Map<String, Object> dataModel = new HashMap<>();
dataModel.put("element", element);
dataModel.put("formData", formData);
String html = FormUtil.generateElementHtml(element, formData, 
    "/templates/textField.ftl", dataModel);
```

##### Validation and Security

```java
// Validate field value
boolean validateField(String fieldId, String value, String regex)

// Escape HTML
String escapeHtml(String content)

// Check for SQL injection
boolean containsSqlInjection(String value)

// Example usage
if (FormUtil.validateField("email", emailValue, 
    "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")) {
    // Valid email format
}

String safeContent = FormUtil.escapeHtml(userInput);
```

---

### AppService

**Package**: `org.joget.apps.app.service.AppService`

AppService manages application definitions and deployment.

#### Key Methods

##### Application Management

```java
// Get app definition
AppDefinition getAppDefinition(String appId, String version)

// Get published app
AppDefinition getPublishedApp(String appId)

// Create new app
AppDefinition createNewAppDefinition(String appId, String appName)

// Deploy app from package
AppDefinition deployPackage(String appId, String version, 
                           byte[] packageData, boolean createNew)

// Example usage
AppService appService = (AppService) AppUtil.getApplicationContext()
    .getBean("appService");

AppDefinition app = appService.getPublishedApp("crm");
if (app == null) {
    app = appService.createNewAppDefinition("crm", "Customer Relationship Management");
}
```

##### Form Operations

```java
// View form with data
Form viewDataForm(String appId, String version, String formDefId, 
                 String tableName, String columnName, String id, 
                 String value, FormData formData, 
                 WorkflowAssignment assignment, Boolean readOnly)

// Load form data
FormRowSet loadFormData(String appId, String version, String formDefId, 
                       String primaryKey)

// Store form data
FormRowSet storeFormData(String appId, String version, String formDefId, 
                        FormRowSet rows, String id)

// Example usage
Form form = appService.viewDataForm(
    appDef.getId(),
    appDef.getVersion().toString(),
    "customerForm",
    null, null, null, null,
    formData,
    assignment,
    false
);

FormRowSet data = appService.loadFormData(appId, version, 
    "customerForm", customerId);
```

##### Workflow Integration

```java
// Complete assignment with form
FormData completeAssignmentForm(String appId, String version, 
                               String activityId, FormData formData, 
                               Map<String, String> workflowVariables)

// Complete with form object
FormData completeAssignmentForm(Form form, WorkflowAssignment assignment, 
                               FormData formData, 
                               Map<String, String> workflowVariables)

// Example usage
Map<String, String> variables = new HashMap<>();
variables.put("status", "approved");
variables.put("approver", getCurrentUsername());

FormData result = appService.completeAssignmentForm(
    appId, version, activityId, formData, variables
);
```

---

### FormService

**Package**: `org.joget.apps.form.service.FormService`

FormService provides high-level form operations.

#### Key Methods

##### Form Submission

```java
// Submit form with validation
FormData submitForm(Form form, FormData formData, boolean ignoreValidation)

// Execute form actions
FormData executeFormActions(Form form, FormData formData)

// Process form submission
FormData processFormSubmission(HttpServletRequest request, Form form)

// Example usage
FormService formService = (FormService) AppUtil.getApplicationContext()
    .getBean("formService");

FormData submittedData = formService.submitForm(form, formData, false);

if (!submittedData.getFormErrors().isEmpty()) {
    // Handle validation errors
    Map<String, String> errors = submittedData.getFormErrors();
    for (String field : errors.keySet()) {
        LogUtil.error("Validation", field + ": " + errors.get(field));
    }
}
```

##### Form Preview and Generation

```java
// Preview element (for form builder)
String previewElement(String json)

// Preview with metadata
String previewElement(String json, boolean includeMetaData)

// Generate form HTML
String generateElementHtml(Form form, FormData formData)

// Example usage
String elementJson = "{\"className\":\"org.joget.apps.form.lib.TextField\",...}";
String preview = formService.previewElement(elementJson, true);
```

---

## Workflow Management API

### WorkflowManager

**Package**: `org.joget.workflow.model.service.WorkflowManager`

WorkflowManager provides comprehensive workflow operations.

#### Key Methods

##### Process Management

```java
// Start process
String processStart(String processDefId)

// Start with variables
String processStart(String processDefId, Map<String, String> variables)

// Start with specific user
String processStart(String processDefId, String processId, 
                   Map<String, String> variables, String username, 
                   String startActivityId, boolean abortIfRunning)

// Abort process
boolean processAbort(String processId)

// Complete activity
void activityComplete(String activityId)

// Example usage
WorkflowManager workflowManager = (WorkflowManager) AppUtil
    .getApplicationContext().getBean("workflowManager");

Map<String, String> variables = new HashMap<>();
variables.put("applicant", "john.doe");
variables.put("amount", "5000");
variables.put("department", "IT");

String processId = workflowManager.processStart(
    "leave_application:1:latest",
    variables
);
```

##### Assignment Operations

```java
// Get assignment
WorkflowAssignment getAssignment(String activityId)

// Get assignments for user
Collection<WorkflowAssignment> getAssignmentList(String username)

// Get pending assignments
Collection<WorkflowAssignment> getAssignmentList(String packageId, 
    String processDefId, String processId, String activityDefId, 
    String username, String state, String sort, Boolean desc, 
    Integer start, Integer rows)

// Accept/reject assignment
void assignmentAccept(String activityId)
void assignmentReject(String activityId)
void assignmentWithdraw(String activityId)

// Example usage
WorkflowAssignment assignment = workflowManager.getAssignment(activityId);

if (assignment != null && !assignment.isAccepted()) {
    workflowManager.assignmentAccept(activityId);
}

// Get user's pending tasks
Collection<WorkflowAssignment> tasks = workflowManager.getAssignmentList(
    getCurrentUsername()
);
```

##### Process Variables

```java
// Get process variable
String getProcessVariable(String processId, String variableName)

// Set process variable
void setProcessVariable(String processId, String variableName, String value)

// Get activity variable
String getActivityVariable(String activityId, String variableName)

// Set activity variable
void activityVariable(String activityId, String variableName, String value)

// Example usage
String status = workflowManager.getProcessVariable(processId, "status");

workflowManager.setProcessVariable(processId, "approvalStatus", "approved");
workflowManager.setProcessVariable(processId, "approvedBy", getCurrentUsername());
workflowManager.setProcessVariable(processId, "approvalDate", new Date().toString());
```

##### Process Monitoring

```java
// Get running processes
Collection<WorkflowProcess> getRunningProcessList(String packageId, 
    String processDefId, String processName, String version, 
    String sort, Boolean desc, Integer start, Integer rows)

// Get process history
Collection<WorkflowActivity> getActivityList(String processId, 
    Integer start, Integer rows, String sort, Boolean desc)

// Check if process is running
boolean isProcessActive(String processId)

// Example usage
Collection<WorkflowProcess> runningProcesses = workflowManager
    .getRunningProcessList("crm", null, null, null, 
        "startedTime", true, 0, 100);

for (WorkflowProcess process : runningProcesses) {
    String id = process.getId();
    String name = process.getName();
    Date startTime = process.getStartedTime();
    String requesterId = process.getRequesterId();
}
```

##### Participants and Performers

```java
// Get process participants
Collection<String> getProcessParticipants(String processId)

// Get activity participants
Collection<String> getActivityParticipantList(String activityId)

// Re-evaluate assignments
void reevaluateAssignmentsForActivity(String activityId)

// Assign user to activity
void assignmentAssignToUser(String activityId, String username)

// Example usage
Collection<String> participants = workflowManager
    .getActivityParticipantList(activityId);

if (!participants.contains("john.doe")) {
    workflowManager.assignmentAssignToUser(activityId, "john.doe");
}
```

---

### WorkflowUserManager

**Package**: `org.joget.workflow.util.WorkflowUserManager`

Manages workflow user context and thread-local user data.

#### Key Methods

```java
// Get current thread user
String getCurrentThreadUser()

// Set current thread user
void setCurrentThreadUser(String username)

// Clear current thread user
void clearCurrentThreadUser()

// Check if system user
boolean isSystemUser()

// Set system user mode
void setSystemThreadUser(boolean isSystemUser)

// Store thread-local data
void setCurrentThreadUserData(String key, Object value)

// Get thread-local data
Object getCurrentThreadUserData(String key)

// Clear thread-local data
void clearCurrentThreadUserData(String key)

// Example usage
WorkflowUserManager workflowUserManager = (WorkflowUserManager) AppUtil
    .getApplicationContext().getBean("workflowUserManager");

// Execute as specific user
String originalUser = workflowUserManager.getCurrentThreadUser();
try {
    workflowUserManager.setCurrentThreadUser("admin");
    // Perform operations as admin
    processAdminTasks();
} finally {
    // Always restore original user
    if (originalUser != null) {
        workflowUserManager.setCurrentThreadUser(originalUser);
    } else {
        workflowUserManager.clearCurrentThreadUser();
    }
}

// Store process context
workflowUserManager.setCurrentThreadUserData("processId", processId);
workflowUserManager.setCurrentThreadUserData("startTime", new Date());
```

---

## Data Management API

### FormDataDao

**Package**: `org.joget.apps.form.dao.FormDataDao`

Direct database operations for form data.

#### Key Methods

```java
// Load single record
FormRow load(String tableName, String primaryKey)

// Load with specific columns
FormRow load(String tableName, String primaryKey, String[] columnNames)

// Load multiple records
FormRowSet find(String tableName, String condition, Object[] params, 
               String sort, Boolean desc, Integer start, Integer rows)

// Save or update
void save(String tableName, FormRow row)
void saveOrUpdate(String tableName, FormRow row)

// Delete record
void delete(String tableName, String primaryKey)

// Example usage
FormDataDao formDataDao = (FormDataDao) AppUtil.getApplicationContext()
    .getBean("formDataDao");

// Load customer record
FormRow customer = formDataDao.load("app_fd_customer", customerId);
if (customer != null) {
    String name = customer.getProperty("name");
    String email = customer.getProperty("email");
}

// Search customers
String condition = "WHERE c_status = ? AND c_country = ?";
Object[] params = {"active", "USA"};
FormRowSet customers = formDataDao.find("app_fd_customer", 
    condition, params, "c_name", false, 0, 100);

// Update customer
customer.setProperty("lastModified", new Date().toString());
customer.setProperty("modifiedBy", getCurrentUsername());
formDataDao.saveOrUpdate("app_fd_customer", customer);
```

---

### DataListService

**Package**: `org.joget.apps.datalist.service.DataListService`

Manages data lists and their operations.

#### Key Methods

```java
// Get data list from JSON
DataList fromJson(String json)

// Get data list columns
DataListColumn[] getColumns(DataList dataList)

// Get data list binder
DataListBinder getBinder(DataList dataList)

// Get data list actions
DataListAction[] getActions(DataList dataList)

// Export data list
void export(DataList dataList, String type, 
           HttpServletResponse response)

// Example usage
DataListService dataListService = (DataListService) AppUtil
    .getApplicationContext().getBean("dataListService");

String dataListJson = loadDataListDefinition();
DataList dataList = dataListService.fromJson(dataListJson);

// Get configured columns
DataListColumn[] columns = dataListService.getColumns(dataList);
for (DataListColumn column : columns) {
    String name = column.getName();
    String label = column.getLabel();
}

// Export to Excel
dataListService.export(dataList, "excel", response);
```

---

### FileUtil

**Package**: `org.joget.commons.util.FileUtil`

File management utilities.

#### Key Methods

```java
// Store uploaded file
String storeFile(MultipartFile file, String tableName, String primaryKey)

// Get file
File getFile(String fileName, String tableName, String primaryKey)

// Delete file
void deleteFile(String fileName, String tableName, String primaryKey)

// Get file URL
String getFileUrl(String fileName, String tableName, String primaryKey)

// Check file type
boolean isImage(String fileName)
boolean isPdf(String fileName)
boolean isVideo(String fileName)

// Example usage
// Store uploaded file
MultipartFile uploadedFile = getUploadedFile();
String storedPath = FileUtil.storeFile(uploadedFile, "app_fd_document", 
    documentId);

// Get file URL for display
String fileUrl = FileUtil.getFileUrl(fileName, "app_fd_document", 
    documentId);

// Delete file
FileUtil.deleteFile(fileName, "app_fd_document", documentId);
```

---

## User & Directory API

### DirectoryManager

**Package**: `org.joget.directory.model.service.DirectoryManager`

Manages users, groups, and organizations.

#### Key Methods

##### User Operations

```java
// Get user by username
User getUserByUsername(String username)

// Get user by ID
User getUserById(String userId)

// Get users
Collection<User> getUsers(String filterString, String organizationId, 
                         String departmentId, String gradeId, 
                         String groupId, String roleId, String active, 
                         String sort, Boolean desc, Integer start, 
                         Integer rows)

// Save user
boolean saveUser(User user)

// Delete user
boolean deleteUser(String username)

// Example usage
DirectoryManager directoryManager = (DirectoryManager) AppUtil
    .getApplicationContext().getBean("directoryManager");

User user = directoryManager.getUserByUsername("john.doe");
if (user != null) {
    user.setEmail("john.doe@newdomain.com");
    user.setActive(User.ACTIVE);
    directoryManager.saveUser(user);
}

// Create new user
User newUser = new User();
newUser.setId("jane.doe");
newUser.setUsername("jane.doe");
newUser.setFirstName("Jane");
newUser.setLastName("Doe");
newUser.setEmail("jane.doe@example.com");
newUser.setActive(User.ACTIVE);
newUser.setPassword(SecurityUtil.encrypt("password123"));
directoryManager.saveUser(newUser);
```

##### Group Operations

```java
// Get group
Group getGroupByName(String groupName)

// Get groups for user
Collection<Group> getGroupByUsername(String username)

// Get users in group
Collection<User> getUserByGroupId(String groupId)

// Add user to group
boolean addUserToGroup(String username, String groupId)

// Remove user from group
boolean removeUserFromGroup(String username, String groupId)

// Example usage
Group managers = directoryManager.getGroupByName("managers");

// Add user to group
directoryManager.addUserToGroup("john.doe", managers.getId());

// Get all users in group
Collection<User> managersUsers = directoryManager
    .getUserByGroupId(managers.getId());

// Check if user is in group
boolean isManager = directoryManager.isUserInGroup("john.doe", 
    managers.getId());
```

##### Role Operations

```java
// Get role
Role getRoleByName(String roleName)

// Get user roles
Collection<Role> getUserRoles(String username)

// Assign role to user
boolean assignUserToRole(String username, String roleId)

// Remove role from user
boolean unassignUserFromRole(String username, String roleId)

// Example usage
Role adminRole = directoryManager.getRoleByName("ROLE_ADMIN");

// Assign role
directoryManager.assignUserToRole("john.doe", adminRole.getId());

// Check user roles
Collection<Role> userRoles = directoryManager.getUserRoles("john.doe");
boolean isAdmin = userRoles.stream()
    .anyMatch(role -> "ROLE_ADMIN".equals(role.getId()));
```

##### Authentication

```java
// Authenticate user
boolean authenticate(String username, String password)

// Example usage
boolean isValid = directoryManager.authenticate("john.doe", "password123");

if (isValid) {
    // Set up user session
    User user = directoryManager.getUserByUsername("john.doe");
    // Create session...
}
```

---

## Utility APIs

### SecurityUtil

**Package**: `org.joget.commons.util.SecurityUtil`

Security utilities for encryption and protection.

#### Key Methods

```java
// Encrypt/decrypt data
String encrypt(String content)
String decrypt(String content)

// MD5 hashing
String md5(String content)
String md5Base16(String content)

// Generate random string
String generateRandomString(int length)

// Validate against XSS
boolean hasXss(String content)

// SQL injection check
boolean hasSqlInjection(String content)

// CSRF token management
String getCsrfTokenName()
String getCsrfTokenValue(HttpServletRequest request)
boolean validateCsrfToken(HttpServletRequest request)

// Example usage
// Encrypt sensitive data
String encrypted = SecurityUtil.encrypt("sensitive-api-key");
String decrypted = SecurityUtil.decrypt(encrypted);

// Generate secure token
String token = SecurityUtil.generateRandomString(32);

// Validate user input
if (SecurityUtil.hasXss(userInput)) {
    throw new SecurityException("XSS detected in input");
}

// CSRF protection
String csrfToken = SecurityUtil.getCsrfTokenValue(request);
if (!SecurityUtil.validateCsrfToken(request)) {
    throw new SecurityException("Invalid CSRF token");
}
```

---

### StringUtil

**Package**: `org.joget.commons.util.StringUtil`

String manipulation and encoding utilities.

#### Key Methods

```java
// Escaping methods
String escapeHtml(String content, String type)
String escapeJavaScript(String content)
String escapeXml(String content)
String escapeUrl(String content)
String escapeSql(String content)

// Type constants for escaping
String TYPE_HTML = "html"
String TYPE_JAVASCRIPT = "javascript"
String TYPE_XML = "xml"
String TYPE_URL = "url"
String TYPE_SQL = "sql"
String TYPE_JAVA = "java"
String TYPE_JSON = "json"

// String manipulation
String stripHtml(String content)
String stripAllHtmlTag(String content)
String replaceString(String original, String pattern, String replacement)

// Encoding
String encodeBase64(String content)
String decodeBase64(String content)

// Example usage
// Escape for different contexts
String htmlSafe = StringUtil.escapeHtml(userInput, StringUtil.TYPE_HTML);
String jsSafe = StringUtil.escapeJavaScript(userInput);
String sqlSafe = StringUtil.escapeSql(userInput);

// Remove HTML tags
String plainText = StringUtil.stripHtml(htmlContent);

// Base64 encoding
String encoded = StringUtil.encodeBase64("sensitive data");
String decoded = StringUtil.decodeBase64(encoded);
```

---

### LogUtil

**Package**: `org.joget.commons.util.LogUtil`

Centralized logging system.

#### Key Methods

```java
// Log levels
void debug(String className, String message)
void info(String className, String message)
void warn(String className, String message)
void error(String className, Throwable exception, String message)

// Check log level
boolean isDebugEnabled(String className)

// Bulk logging
void log(String className, int level, String message)

// Example usage
public class MyPlugin extends DefaultApplicationPlugin {
    
    private static final String CLASS_NAME = MyPlugin.class.getName();
    
    @Override
    public Object execute(Map properties) {
        LogUtil.debug(CLASS_NAME, "Starting plugin execution");
        LogUtil.debug(CLASS_NAME, "Properties: " + properties);
        
        try {
            LogUtil.info(CLASS_NAME, "Processing request");
            
            // Business logic
            String result = processData();
            
            LogUtil.info(CLASS_NAME, "Successfully processed: " + result);
            return result;
            
        } catch (ValidationException e) {
            LogUtil.warn(CLASS_NAME, "Validation failed: " + e.getMessage());
            return "Validation error";
            
        } catch (Exception e) {
            LogUtil.error(CLASS_NAME, e, "Fatal error in plugin");
            return "Error occurred";
        }
    }
}
```

---

### ResourceBundleUtil

**Package**: `org.joget.commons.util.ResourceBundleUtil`

Internationalization and localization support.

#### Key Methods

```java
// Get message
String getMessage(String key)
String getMessage(String key, Object[] params)

// Get message with locale
String getMessage(String key, String locale)
String getMessage(String key, String locale, Object[] params)

// Get available locales
String[] getLocaleList()

// Get current locale
String getCurrentLocale()

// Example usage
// Simple message
String label = ResourceBundleUtil.getMessage("form.button.submit");

// Message with parameters
String message = ResourceBundleUtil.getMessage(
    "form.validation.required",
    new Object[]{"Email Address"}
);
// Result: "Email Address is required"

// Specific locale
String spanishLabel = ResourceBundleUtil.getMessage(
    "form.button.submit", "es"
);

// Get user's locale
String userLocale = ResourceBundleUtil.getCurrentLocale();
```

---

### TimeZoneUtil

**Package**: `org.joget.commons.util.TimeZoneUtil`

Time zone handling utilities.

#### Key Methods

```java
// Get server time zone
String getServerTimeZone()

// Get user time zone
String getUserTimeZone()

// Convert between time zones
Date convertToTimeZone(Date date, String fromTimeZone, String toTimeZone)

// Format date with time zone
String formatDate(Date date, String format, String timeZone)

// Parse date with time zone
Date parseDate(String dateString, String format, String timeZone)

// Example usage
// Get current time zones
String serverTz = TimeZoneUtil.getServerTimeZone();
String userTz = TimeZoneUtil.getUserTimeZone();

// Convert server time to user time
Date serverTime = new Date();
Date userTime = TimeZoneUtil.convertToTimeZone(
    serverTime, serverTz, userTz
);

// Format date for display
String formatted = TimeZoneUtil.formatDate(
    userTime, 
    "yyyy-MM-dd HH:mm:ss",
    userTz
);

// Parse user input
Date parsed = TimeZoneUtil.parseDate(
    "2024-01-15 14:30:00",
    "yyyy-MM-dd HH:mm:ss",
    userTz
);
```

---

## UI and Rendering API

### UserviewService

**Package**: `org.joget.apps.userview.service.UserviewService`

Manages user interfaces and themes.

#### Key Methods

```java
// Parse userview from JSON
Userview parseFromJson(String json, AppDefinition appDef, 
                       Map<String, Object> requestParameters, 
                       Map<String, Object> modelMap)

// Get userview themes
Collection<UserviewTheme> getThemes()

// Get userview menus
Collection<UserviewMenu> getMenuTypes()

// Process userview
String processView(Userview userview, String menuId, 
                  HttpServletRequest request, 
                  HttpServletResponse response)

// Example usage
UserviewService userviewService = (UserviewService) AppUtil
    .getApplicationContext().getBean("userviewService");

String userviewJson = loadUserviewDefinition();
Userview userview = userviewService.parseFromJson(
    userviewJson, appDef, null, null
);

// Process and render
String html = userviewService.processView(
    userview, "home", request, response
);
```

---

### FormPdfUtil

**Package**: `org.joget.apps.form.lib.FormPdfUtil`

PDF generation from forms.

#### Key Methods

```java
// Generate PDF from form
byte[] generatePdf(String formDefId, String primaryKey, 
                   AppDefinition appDef, WorkflowAssignment assignment, 
                   Boolean hideEmpty)

// Generate with custom CSS
byte[] generatePdf(String formDefId, String primaryKey, 
                   AppDefinition appDef, WorkflowAssignment assignment, 
                   Boolean hideEmpty, String customCss)

// Save PDF to file
File savePdfToFile(String formDefId, String primaryKey, 
                   AppDefinition appDef, WorkflowAssignment assignment, 
                   String fileName)

// Example usage
// Generate PDF
byte[] pdfData = FormPdfUtil.generatePdf(
    "customerForm",
    customerId,
    appDef,
    null,
    true  // Hide empty fields
);

// Save to file
File pdfFile = FormPdfUtil.savePdfToFile(
    "customerForm",
    customerId,
    appDef,
    null,
    "customer_" + customerId + ".pdf"
);

// Send as response
response.setContentType("application/pdf");
response.setHeader("Content-Disposition", 
    "attachment; filename=\"form.pdf\"");
response.getOutputStream().write(pdfData);
```

---

## Advanced APIs

### PropertyUtil

**Package**: `org.joget.plugin.property.service.PropertyUtil`

Plugin property management.

#### Key Methods

```java
// Parse property from JSON
Map<String, Object> getProperties(String json)

// Get property value
String getPropertyString(Map<String, Object> properties, String key)

// Process hash variables in properties
Map<String, Object> getHashVariableSupportedMap(Map<String, Object> properties)

// Validate properties
boolean validateProperties(Map<String, Object> properties, 
                          String[] requiredKeys)

// Example usage
// Parse plugin properties
String propertyJson = getPropertyOptions();
Map<String, Object> properties = PropertyUtil.getProperties(propertyJson);

// Get with hash variable support
Map<String, Object> processedProps = PropertyUtil
    .getHashVariableSupportedMap(properties);

// Get specific property
String apiUrl = PropertyUtil.getPropertyString(processedProps, "apiUrl");

// Validate required properties
String[] required = {"apiUrl", "apiKey"};
if (!PropertyUtil.validateProperties(properties, required)) {
    throw new IllegalArgumentException("Missing required properties");
}
```

---

### PluginManager

**Package**: `org.joget.plugin.base.PluginManager`

Plugin lifecycle management.

#### Key Methods

```java
// Get plugin
Plugin getPlugin(String className)

// List plugins by interface
Collection<Plugin> list(Class clazz)

// Install plugin
void install(File file)

// Uninstall plugin
void uninstall(String className)

// Refresh plugins
void refresh()

// Execute plugin
Object execute(String className, Map properties)

// Test plugin
boolean testPlugin(String className, String location, 
                  Map properties, boolean override)

// Example usage
PluginManager pluginManager = (PluginManager) AppUtil
    .getApplicationContext().getBean("pluginManager");

// Get specific plugin
Plugin emailTool = pluginManager.getPlugin(
    "org.joget.apps.app.lib.EmailTool"
);

// List all process tools
Collection<Plugin> processTools = pluginManager.list(
    DefaultApplicationPlugin.class
);

// Execute plugin
Map<String, Object> props = new HashMap<>();
props.put("toEmail", "user@example.com");
props.put("subject", "Test");
Object result = pluginManager.execute(
    "org.joget.apps.app.lib.EmailTool", props
);

// Refresh all plugins
pluginManager.refresh();
```

---

### SetupManager

**Package**: `org.joget.commons.util.SetupManager`

System configuration management.

#### Key Methods

```java
// Get setting value
String getSettingValue(String key)

// Save setting
void saveSettingValue(String key, String value)

// Get system settings
Map<String, String> getSystemSettings()

// Get setup properties
Properties getProperties()

// Database operations
DataSource getDataSource()
Connection getDatabaseConnection()

// Example usage
SetupManager setupManager = (SetupManager) AppUtil
    .getApplicationContext().getBean("setupManager");

// Get system settings
String smtpHost = setupManager.getSettingValue("smtpHost");
String smtpPort = setupManager.getSettingValue("smtpPort");

// Save settings
setupManager.saveSettingValue("customSetting", "value123");

// Get database connection
try (Connection conn = setupManager.getDatabaseConnection()) {
    // Perform database operations
    PreparedStatement stmt = conn.prepareStatement(
        "SELECT * FROM app_fd_customer WHERE c_status = ?"
    );
    stmt.setString(1, "active");
    ResultSet rs = stmt.executeQuery();
}
```

---

## Integration Patterns

### Pattern 1: Complete Form Processing

```java
public class FormProcessingPattern extends DefaultApplicationPlugin {
    
    @Override
    public Object execute(Map properties) {
        try {
            // Get services
            AppService appService = (AppService) AppUtil.getApplicationContext()
                .getBean("appService");
            FormService formService = (FormService) AppUtil.getApplicationContext()
                .getBean("formService");
            WorkflowManager workflowManager = (WorkflowManager) AppUtil
                .getApplicationContext().getBean("workflowManager");
            
            // Get context
            WorkflowAssignment assignment = (WorkflowAssignment) 
                properties.get("workflowAssignment");
            String formDefId = getPropertyString("formDefId");
            
            // Load form
            Form form = appService.viewDataForm(
                AppUtil.getCurrentAppDefinition().getId(),
                AppUtil.getCurrentAppDefinition().getVersion().toString(),
                formDefId, null, null, null, null, null, assignment, false
            );
            
            // Create form data
            FormData formData = new FormData();
            formData.setProcessId(assignment.getProcessId());
            formData.setActivityId(assignment.getActivityId());
            
            // Load existing data
            formData = FormUtil.executeLoadBinders(form, formData);
            
            // Process business logic
            processBusinessRules(form, formData);
            
            // Validate
            formData = FormUtil.executeValidators(form, formData);
            
            if (formData.getFormErrors().isEmpty()) {
                // Save data
                formData = FormUtil.executeStoreBinders(form, formData);
                
                // Update workflow variables
                Map<String, String> variables = new HashMap<>();
                variables.put("status", "processed");
                workflowManager.activityVariables(
                    assignment.getActivityId(), variables
                );
                
                // Complete activity
                workflowManager.activityComplete(assignment.getActivityId());
                
                return "Form processed successfully";
            } else {
                return "Validation errors: " + formData.getFormErrors();
            }
            
        } catch (Exception e) {
            LogUtil.error(getClass().getName(), e, "Error processing form");
            return "Error: " + e.getMessage();
        }
    }
}
```

### Pattern 2: User Management Integration

```java
public class UserManagementPattern {
    
    public void setupProjectTeam(String projectId, String[] memberUsernames) {
        DirectoryManager directoryManager = (DirectoryManager) AppUtil
            .getApplicationContext().getBean("directoryManager");
        WorkflowUserManager workflowUserManager = (WorkflowUserManager) AppUtil
            .getApplicationContext().getBean("workflowUserManager");
        
        try {
            // Create project group
            Group projectGroup = new Group();
            projectGroup.setId("project_" + projectId);
            projectGroup.setName("Project " + projectId + " Team");
            projectGroup.setDescription("Team members for project " + projectId);
            directoryManager.addGroup(projectGroup);
            
            // Add members to group
            for (String username : memberUsernames) {
                User user = directoryManager.getUserByUsername(username);
                if (user != null) {
                    directoryManager.addUserToGroup(username, projectGroup.getId());
                    
                    // Set workflow context
                    workflowUserManager.setCurrentThreadUser(username);
                    workflowUserManager.setCurrentThreadUserData(
                        "projectId", projectId
                    );
                }
            }
            
            // Create project role
            Role projectRole = new Role();
            projectRole.setId("ROLE_PROJECT_" + projectId);
            projectRole.setName("Project " + projectId + " Member");
            directoryManager.addRole(projectRole);
            
            // Assign role to group members
            for (String username : memberUsernames) {
                directoryManager.assignUserToRole(username, projectRole.getId());
            }
            
        } catch (Exception e) {
            LogUtil.error(getClass().getName(), e, "Error setting up project team");
        }
    }
}
```

### Pattern 3: Email Notification with Template

```java
public class EmailNotificationPattern {
    
    public void sendTemplatedEmail(String recipientUsername, 
                                  String templateId, 
                                  Map<String, String> data) {
        try {
            DirectoryManager directoryManager = (DirectoryManager) AppUtil
                .getApplicationContext().getBean("directoryManager");
            
            // Get user details
            User recipient = directoryManager.getUserByUsername(recipientUsername);
            if (recipient == null) {
                LogUtil.warn(getClass().getName(), 
                    "User not found: " + recipientUsername);
                return;
            }
            
            // Load email template
            String template = loadEmailTemplate(templateId);
            
            // Process hash variables
            WorkflowAssignment assignment = AppUtil.getCurrentAssignment();
            String subject = AppUtil.processHashVariable(
                data.get("subject"), assignment, null, null
            );
            
            // Build email content with data
            String content = template;
            for (Map.Entry<String, String> entry : data.entrySet()) {
                content = content.replace("${" + entry.getKey() + "}", 
                    entry.getValue());
            }
            
            // Process remaining hash variables
            content = AppUtil.processHashVariable(
                content, assignment, null, null
            );
            
            // Send email
            boolean sent = AppUtil.sendEmail(
                recipient.getEmail(),
                recipient.getFirstName() + " " + recipient.getLastName(),
                subject,
                content,
                true,  // HTML email
                null,  // From email (use system default)
                null,  // From name
                null,  // CC
                null,  // BCC
                null,  // Attachments
                null,  // Headers
                assignment,
                AppUtil.getCurrentAppDefinition()
            );
            
            if (sent) {
                LogUtil.info(getClass().getName(), 
                    "Email sent to " + recipient.getEmail());
            } else {
                LogUtil.error(getClass().getName(), null, 
                    "Failed to send email to " + recipient.getEmail());
            }
            
        } catch (Exception e) {
            LogUtil.error(getClass().getName(), e, "Error sending email");
        }
    }
}
```

### Pattern 4: Data Export and Reporting

```java
public class DataExportPattern {
    
    public void exportFormDataToExcel(String formDefId, 
                                     HttpServletResponse response) {
        try {
            FormDataDao formDataDao = (FormDataDao) AppUtil.getApplicationContext()
                .getBean("formDataDao");
            
            // Build table name
            String tableName = "app_fd_" + formDefId.replace("_form", "");
            
            // Load all records
            FormRowSet rows = formDataDao.find(tableName, null, null, 
                null, false, null, null);
            
            // Create Excel workbook
            Workbook workbook = new XSSFWorkbook();
            Sheet sheet = workbook.createSheet("Data Export");
            
            // Create header row
            if (!rows.isEmpty()) {
                FormRow firstRow = rows.get(0);
                Row headerRow = sheet.createRow(0);
                int col = 0;
                
                for (String key : firstRow.keySet()) {
                    Cell cell = headerRow.createCell(col++);
                    cell.setCellValue(key);
                    cell.setCellStyle(createHeaderStyle(workbook));
                }
                
                // Create data rows
                int rowNum = 1;
                for (FormRow row : rows) {
                    Row dataRow = sheet.createRow(rowNum++);
                    col = 0;
                    
                    for (String key : row.keySet()) {
                        Cell cell = dataRow.createCell(col++);
                        Object value = row.get(key);
                        
                        if (value != null) {
                            cell.setCellValue(value.toString());
                        }
                    }
                }
                
                // Auto-size columns
                for (int i = 0; i < col; i++) {
                    sheet.autoSizeColumn(i);
                }
            }
            
            // Send response
            response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            );
            response.setHeader("Content-Disposition", 
                "attachment; filename=\"" + formDefId + "_export.xlsx\"");
            
            workbook.write(response.getOutputStream());
            workbook.close();
            
        } catch (Exception e) {
            LogUtil.error(getClass().getName(), e, "Error exporting data");
        }
    }
}
```

---

## API Best Practices

### 1. Service Access

Always get services through AppUtil:

```java
// Good - Get through application context
FormService formService = (FormService) AppUtil.getApplicationContext()
    .getBean("formService");

// Bad - Direct instantiation
FormService formService = new FormServiceImpl(); // Don't do this
```

### 2. Error Handling

Always handle exceptions gracefully:

```java
public Object execute(Map properties) {
    try {
        // Your logic
        return processData();
    } catch (ValidationException e) {
        LogUtil.warn(getClass().getName(), "Validation: " + e.getMessage());
        return "Validation failed";
    } catch (Exception e) {
        LogUtil.error(getClass().getName(), e, "Unexpected error");
        return "An error occurred";
    }
}
```

### 3. Resource Cleanup

Always clean up resources:

```java
Connection conn = null;
PreparedStatement stmt = null;
ResultSet rs = null;

try {
    conn = setupManager.getDatabaseConnection();
    stmt = conn.prepareStatement(sql);
    rs = stmt.executeQuery();
    // Process results
} finally {
    try { if (rs != null) rs.close(); } catch (Exception e) {}
    try { if (stmt != null) stmt.close(); } catch (Exception e) {}
    try { if (conn != null) conn.close(); } catch (Exception e) {}
}
```

### 4. User Context Management

Always restore original user context:

```java
String originalUser = workflowUserManager.getCurrentThreadUser();
try {
    workflowUserManager.setCurrentThreadUser("admin");
    // Perform admin operations
} finally {
    if (originalUser != null) {
        workflowUserManager.setCurrentThreadUser(originalUser);
    } else {
        workflowUserManager.clearCurrentThreadUser();
    }
}
```

### 5. Security Validation

Always validate and sanitize inputs:

```java
// Validate input
if (SecurityUtil.hasXss(userInput)) {
    throw new SecurityException("Invalid input detected");
}

// Escape for context
String htmlSafe = StringUtil.escapeHtml(userInput, StringUtil.TYPE_HTML);
String sqlSafe = StringUtil.escapeSql(userInput);
```

### 6. Logging

Use appropriate log levels:

```java
LogUtil.debug(CLASS_NAME, "Detailed debug information");
LogUtil.info(CLASS_NAME, "Important business event");
LogUtil.warn(CLASS_NAME, "Potential issue");
LogUtil.error(CLASS_NAME, exception, "Error occurred");
```

### 7. Hash Variables

Always process hash variables for dynamic content:

```java
String template = getPropertyString("template");
String processed = AppUtil.processHashVariable(
    template, 
    assignment, 
    StringUtil.TYPE_HTML,  // Escape for HTML
    null
);
```

### 8. Transaction Management

Be aware of transaction boundaries:

```java
@Transactional
public void performDatabaseOperations() {
    // All database operations here are in same transaction
    formDataDao.save(table1, row1);
    formDataDao.save(table2, row2);
    // If any fails, all are rolled back
}
```

---

## Conclusion

This comprehensive API reference covers the essential methods and patterns for Joget DX8 plugin development. Key takeaways:

1. **Use AppUtil** as your gateway to all services
2. **Handle errors gracefully** with proper logging
3. **Manage resources carefully** to prevent leaks
4. **Validate all inputs** for security
5. **Process hash variables** for dynamic content
6. **Follow patterns** for common scenarios
7. **Test thoroughly** with different data and contexts

The Joget API is designed to make complex operations simple while maintaining enterprise-grade reliability and security. Always refer to the latest Joget documentation for updates and additional features.