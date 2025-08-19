# Joget DX8 Plugin Best Practices and Troubleshooting Guide

## Table of Contents

1. [Development Best Practices](#development-best-practices)
2. [Code Quality Standards](#code-quality-standards)
3. [Security Best Practices](#security-best-practices)
4. [Performance Optimization](#performance-optimization)
5. [Error Handling and Logging](#error-handling-and-logging)
6. [Configuration Management](#configuration-management)
7. [Testing Strategies](#testing-strategies)
8. [Common Issues and Solutions](#common-issues-and-solutions)
9. [Debugging Techniques](#debugging-techniques)
10. [Deployment Best Practices](#deployment-best-practices)
11. [Maintenance and Monitoring](#maintenance-and-monitoring)
12. [Anti-Patterns to Avoid](#anti-patterns-to-avoid)
13. [Advanced Troubleshooting](#advanced-troubleshooting)
14. [Performance Troubleshooting](#performance-troubleshooting)
15. [Production Readiness Checklist](#production-readiness-checklist)

---

## Development Best Practices

### 1. The Joget-ic Way: Configuration Over Code

#### ❌ Wrong Approach
```java
public Object execute(Map properties) {
    // Hardcoded values - inflexible and unmaintainable
    sendEmail("admin@company.com", "Alert", "Something happened");
    connectToDatabase("localhost", "3306", "mydb");
    return null;
}
```

#### ✅ Correct Approach
```java
public Object execute(Map properties) {
    // Use configurable properties with hash variable support
    String recipient = getPropertyString("recipient");  // Can be #{currentUser.email}
    String subject = getPropertyString("subject");      // Can include #{form.field1}
    String message = getPropertyString("message");      // Supports workflow variables
    
    // Process hash variables
    WorkflowAssignment assignment = (WorkflowAssignment) properties.get("workflowAssignment");
    recipient = AppUtil.processHashVariable(recipient, assignment, null, null);
    subject = AppUtil.processHashVariable(subject, assignment, null, null);
    message = AppUtil.processHashVariable(message, assignment, null, null);
    
    sendEmail(recipient, subject, message);
    return "Email sent successfully";
}

@Override
public String getPropertyOptions() {
    return AppUtil.readPluginResource(
        getClass().getName(),
        "/properties/emailPlugin.json",
        null, true, null
    );
}
```

### 2. Plugin Structure Best Practices

#### Recommended Package Structure
```
com.company.joget.plugin/
├── YourPlugin.java              # Main plugin class
├── Activator.java              # OSGi bundle activator
├── util/                       # Utility classes
│   ├── ValidationUtil.java
│   └── DataProcessor.java
├── model/                      # Data models
│   ├── RequestModel.java
│   └── ResponseModel.java
├── service/                    # Service classes
│   ├── ApiService.java
│   └── DatabaseService.java
└── exception/                  # Custom exceptions
    ├── PluginException.java
    └── ValidationException.java
```

### 3. Service Layer Pattern

```java
public class AdvancedPlugin extends DefaultApplicationPlugin {
    
    private ApiService apiService;
    private DatabaseService dbService;
    
    @Override
    public Object execute(Map properties) {
        try {
            // Initialize services
            initializeServices();
            
            // Delegate to service layer
            RequestModel request = buildRequest(properties);
            ResponseModel response = apiService.processRequest(request);
            dbService.saveResponse(response);
            
            return "Success: " + response.getId();
            
        } catch (Exception e) {
            return handleError(e);
        }
    }
    
    private void initializeServices() {
        if (apiService == null) {
            apiService = new ApiService(getPropertyString("apiUrl"),
                                      getPropertyString("apiKey"));
        }
        if (dbService == null) {
            dbService = new DatabaseService();
        }
    }
}
```

### 4. Singleton Pattern for Expensive Resources

```java
public class ConnectionManager {
    private static ConnectionManager instance;
    private HikariDataSource dataSource;
    
    private ConnectionManager() {
        initializeDataSource();
    }
    
    public static synchronized ConnectionManager getInstance() {
        if (instance == null) {
            instance = new ConnectionManager();
        }
        return instance;
    }
    
    private void initializeDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(getPropertyString("jdbcUrl"));
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        
        dataSource = new HikariDataSource(config);
    }
    
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
}
```

---

## Code Quality Standards

### 1. Method Complexity

#### ❌ Bad: Complex Monolithic Method
```java
public Object execute(Map properties) {
    // 200 lines of code doing everything
    // Hard to test, maintain, and debug
}
```

#### ✅ Good: Single Responsibility Methods
```java
public Object execute(Map properties) {
    try {
        ValidationResult validation = validateInput(properties);
        if (!validation.isValid()) {
            return validation.getErrorMessage();
        }
        
        ProcessedData data = processBusinessLogic(validation.getData());
        String result = persistData(data);
        notifyStakeholders(data);
        
        return result;
        
    } catch (Exception e) {
        return handleException(e);
    }
}

private ValidationResult validateInput(Map properties) {
    // Focused validation logic
}

private ProcessedData processBusinessLogic(InputData data) {
    // Core business logic
}

private String persistData(ProcessedData data) {
    // Data persistence
}

private void notifyStakeholders(ProcessedData data) {
    // Notification logic
}
```

### 2. Naming Conventions

```java
// Classes - PascalCase
public class CustomerNotificationPlugin { }

// Methods - camelCase with verb
public void sendNotification() { }
public boolean isValid() { }
public String getCustomerName() { }

// Constants - UPPER_SNAKE_CASE
public static final int MAX_RETRY_COUNT = 3;
public static final String DEFAULT_TIMEOUT = "30000";

// Variables - camelCase
private String customerEmail;
private int retryCount;

// Boolean variables - use is/has prefix
private boolean isActive;
private boolean hasPermission;
```

### 3. Documentation Standards

```java
/**
 * Processes customer orders and sends notifications.
 * 
 * This plugin integrates with the order management system to:
 * - Validate order data against business rules
 * - Process payments through payment gateway
 * - Send confirmation emails to customers
 * - Update inventory levels
 * 
 * Configuration:
 * - apiUrl: Payment gateway API endpoint
 * - apiKey: Authentication key for payment gateway
 * - emailTemplate: Template ID for confirmation emails
 * 
 * @author John Developer
 * @version 2.1.0
 * @since 2024-01-01
 */
public class OrderProcessingPlugin extends DefaultApplicationPlugin {
    
    /**
     * Validates order data against business rules.
     * 
     * @param order The order to validate
     * @return ValidationResult containing validation status and any errors
     * @throws ValidationException if critical validation failure occurs
     */
    private ValidationResult validateOrder(Order order) throws ValidationException {
        // Implementation
    }
}
```

---

## Security Best Practices

### 1. Input Validation and Sanitization

```java
public class SecurePlugin extends DefaultApplicationPlugin {
    
    @Override
    public Object execute(Map properties) {
        try {
            // Validate all inputs
            String userInput = getPropertyString("userInput");
            
            // Check for SQL injection
            if (SecurityUtil.hasSqlInjection(userInput)) {
                LogUtil.warn(getClass().getName(), 
                    "SQL injection attempt detected: " + userInput);
                return "Invalid input detected";
            }
            
            // Check for XSS
            if (SecurityUtil.hasXss(userInput)) {
                LogUtil.warn(getClass().getName(), 
                    "XSS attempt detected: " + userInput);
                return "Invalid input detected";
            }
            
            // Sanitize for specific context
            String htmlSafe = StringUtil.escapeHtml(userInput, StringUtil.TYPE_HTML);
            String sqlSafe = StringUtil.escapeSql(userInput);
            String jsSafe = StringUtil.escapeJavaScript(userInput);
            
            // Process with sanitized data
            return processSafeData(htmlSafe, sqlSafe, jsSafe);
            
        } catch (Exception e) {
            LogUtil.error(getClass().getName(), e, "Security validation failed");
            return "Processing failed";
        }
    }
}
```

### 2. SQL Injection Prevention

```java
public class DatabasePlugin extends DefaultApplicationPlugin {
    
    // ❌ NEVER do this
    private String executeUnsafeQuery(String userId) {
        String sql = "SELECT * FROM users WHERE id = " + userId;
        // Direct concatenation - vulnerable to SQL injection
    }
    
    // ✅ Always use parameterized queries
    private User executeSecureQuery(String userId) {
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        
        try {
            conn = getConnection();
            String sql = "SELECT * FROM app_fd_users WHERE c_user_id = ?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, userId);  // Safe parameter binding
            
            rs = stmt.executeQuery();
            if (rs.next()) {
                return mapResultToUser(rs);
            }
            
        } catch (SQLException e) {
            LogUtil.error(getClass().getName(), e, "Database query failed");
        } finally {
            closeResources(rs, stmt, conn);
        }
        
        return null;
    }
    
    // For dynamic queries, use whitelist validation
    private List<User> executeDynamicQuery(String sortColumn, String sortOrder) {
        // Validate against whitelist
        Set<String> allowedColumns = Set.of("name", "email", "created_date");
        Set<String> allowedOrders = Set.of("ASC", "DESC");
        
        if (!allowedColumns.contains(sortColumn) || 
            !allowedOrders.contains(sortOrder.toUpperCase())) {
            throw new IllegalArgumentException("Invalid sort parameters");
        }
        
        // Now safe to use in query
        String sql = String.format(
            "SELECT * FROM app_fd_users ORDER BY %s %s",
            sortColumn, sortOrder
        );
        
        return executeQuery(sql);
    }
}
```

### 3. Sensitive Data Protection

```java
public class SecureDataPlugin extends DefaultApplicationPlugin {
    
    @Override
    public String getPropertyOptions() {
        return "[{" +
            "\"title\":\"Security Configuration\"," +
            "\"properties\":[{" +
                "\"name\":\"apiKey\"," +
                "\"label\":\"API Key\"," +
                "\"type\":\"password\"," +  // Use password type for sensitive data
                "\"required\":\"true\"" +
            "},{" +
                "\"name\":\"encryptData\"," +
                "\"label\":\"Encrypt Sensitive Data\"," +
                "\"type\":\"checkbox\"," +
                "\"value\":\"true\"" +
            "}]" +
        "}]";
    }
    
    @Override
    public Object execute(Map properties) {
        String apiKey = getPropertyString("apiKey");
        
        // Never log sensitive data
        LogUtil.info(getClass().getName(), 
            "Processing with API key: " + maskApiKey(apiKey));
        
        // Encrypt sensitive data before storage
        if ("true".equals(getPropertyString("encryptData"))) {
            String encrypted = SecurityUtil.encrypt(sensitiveData);
            storeEncryptedData(encrypted);
        }
        
        return "Processed securely";
    }
    
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + 
               apiKey.substring(apiKey.length() - 4);
    }
}
```

### 4. CSRF Protection

```java
public class FormSubmissionPlugin extends DefaultApplicationPlugin {
    
    public Object processFormSubmission(HttpServletRequest request, 
                                       FormData formData) {
        // Verify CSRF token
        if (!SecurityUtil.validateCsrfToken(request)) {
            LogUtil.warn(getClass().getName(), 
                "CSRF token validation failed from: " + request.getRemoteAddr());
            return "Invalid request token";
        }
        
        // Process form safely
        return processValidatedForm(formData);
    }
    
    public String generateFormWithToken(HttpServletRequest request) {
        String token = SecurityUtil.getCsrfTokenValue(request);
        String tokenName = SecurityUtil.getCsrfTokenName();
        
        return "<form method='post'>" +
               "<input type='hidden' name='" + tokenName + "' value='" + token + "'/>" +
               "<!-- Form fields here -->" +
               "</form>";
    }
}
```

---

## Performance Optimization

### 1. Caching Strategies

```java
public class CachedDataPlugin extends DefaultApplicationPlugin {
    
    // Thread-safe cache with TTL
    private static final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL = 5 * 60 * 1000; // 5 minutes
    
    @Override
    public Object execute(Map properties) {
        String cacheKey = generateCacheKey(properties);
        
        // Check cache first
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            LogUtil.debug(getClass().getName(), "Cache hit for: " + cacheKey);
            return cached.getValue();
        }
        
        // Cache miss - load data
        Object result = loadExpensiveData(properties);
        
        // Store in cache
        cache.put(cacheKey, new CacheEntry(result));
        
        // Clean expired entries periodically
        cleanExpiredEntries();
        
        return result;
    }
    
    private static class CacheEntry {
        private final Object value;
        private final long timestamp;
        
        CacheEntry(Object value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL;
        }
        
        Object getValue() {
            return value;
        }
    }
    
    private void cleanExpiredEntries() {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
}
```

### 2. Connection Pooling

```java
public class DatabasePoolPlugin extends DefaultApplicationPlugin {
    
    private static HikariDataSource dataSource;
    
    static {
        initializeDataSource();
    }
    
    private static void initializeDataSource() {
        HikariConfig config = new HikariConfig();
        
        // Connection settings
        config.setJdbcUrl("jdbc:mysql://localhost:3306/jwdb");
        config.setUsername("joget");
        config.setPassword("joget");
        
        // Pool settings
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setIdleTimeout(600000);
        config.setConnectionTimeout(30000);
        config.setMaxLifetime(1800000);
        
        // Performance settings
        config.setAutoCommit(false);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        
        dataSource = new HikariDataSource(config);
    }
    
    protected Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    @Override
    public Object execute(Map properties) {
        Connection conn = null;
        PreparedStatement stmt = null;
        
        try {
            conn = getConnection();
            conn.setAutoCommit(false);
            
            // Perform database operations
            stmt = conn.prepareStatement("INSERT INTO ...");
            stmt.executeUpdate();
            
            conn.commit();
            return "Success";
            
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    LogUtil.error(getClass().getName(), ex, "Rollback failed");
                }
            }
            return "Database operation failed";
            
        } finally {
            closeQuietly(stmt);
            closeQuietly(conn);
        }
    }
}
```

### 3. Batch Processing

```java
public class BatchProcessingPlugin extends DefaultApplicationPlugin {
    
    private static final int BATCH_SIZE = 1000;
    
    @Override
    public Object execute(Map properties) {
        List<Record> records = loadLargeDataset();
        int totalProcessed = 0;
        
        // Process in batches
        for (int i = 0; i < records.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, records.size());
            List<Record> batch = records.subList(i, end);
            
            try {
                processBatch(batch);
                totalProcessed += batch.size();
                
                // Log progress
                LogUtil.info(getClass().getName(), 
                    String.format("Processed %d/%d records", 
                        totalProcessed, records.size()));
                
            } catch (Exception e) {
                LogUtil.error(getClass().getName(), e, 
                    "Failed to process batch starting at index " + i);
                // Decide whether to continue or abort
            }
        }
        
        return "Processed " + totalProcessed + " records";
    }
    
    private void processBatch(List<Record> batch) throws SQLException {
        Connection conn = null;
        PreparedStatement stmt = null;
        
        try {
            conn = getConnection();
            conn.setAutoCommit(false);
            
            String sql = "INSERT INTO app_fd_records (c_id, c_data) VALUES (?, ?)";
            stmt = conn.prepareStatement(sql);
            
            for (Record record : batch) {
                stmt.setString(1, record.getId());
                stmt.setString(2, record.getData());
                stmt.addBatch();
            }
            
            stmt.executeBatch();
            conn.commit();
            
        } catch (SQLException e) {
            if (conn != null) conn.rollback();
            throw e;
        } finally {
            closeQuietly(stmt);
            closeQuietly(conn);
        }
    }
}
```

### 4. Lazy Loading Pattern

```java
public class LazyLoadingPlugin extends DefaultApplicationPlugin {
    
    private volatile ExpensiveResource resource;
    
    private ExpensiveResource getResource() {
        if (resource == null) {
            synchronized (this) {
                if (resource == null) {
                    resource = initializeExpensiveResource();
                }
            }
        }
        return resource;
    }
    
    @Override
    public Object execute(Map properties) {
        // Only initialize when actually needed
        if (shouldUseResource(properties)) {
            ExpensiveResource res = getResource();
            return res.process(properties);
        }
        
        return "Resource not needed";
    }
}
```

---

## Error Handling and Logging

### 1. Comprehensive Error Handling

```java
public class RobustPlugin extends DefaultApplicationPlugin {
    
    @Override
    public Object execute(Map properties) {
        String operation = getPropertyString("operation");
        
        try {
            return performOperation(operation);
            
        } catch (ValidationException e) {
            // Expected business exceptions
            LogUtil.warn(getClass().getName(), 
                "Validation failed for operation " + operation + ": " + e.getMessage());
            return "Validation error: " + e.getMessage();
            
        } catch (DataAccessException e) {
            // Database exceptions
            LogUtil.error(getClass().getName(), e, 
                "Database error in operation " + operation);
            return "Database error occurred. Please contact administrator.";
            
        } catch (ApiException e) {
            // External API exceptions
            LogUtil.error(getClass().getName(), e, 
                "API call failed for operation " + operation);
            
            // Retry logic
            if (e.isRetryable()) {
                return retryOperation(operation, e);
            }
            return "External service unavailable. Please try again later.";
            
        } catch (Exception e) {
            // Unexpected exceptions
            LogUtil.error(getClass().getName(), e, 
                "Unexpected error in operation " + operation);
            
            // Send alert for critical errors
            sendAlertToAdmin(e, operation);
            
            return "An unexpected error occurred. Support has been notified.";
            
        } finally {
            // Cleanup resources
            cleanupResources();
        }
    }
    
    private String retryOperation(String operation, ApiException e) {
        int maxRetries = 3;
        int retryDelay = 1000; // ms
        
        for (int i = 1; i <= maxRetries; i++) {
            try {
                Thread.sleep(retryDelay * i); // Exponential backoff
                return performOperation(operation);
                
            } catch (Exception retryException) {
                LogUtil.warn(getClass().getName(), 
                    "Retry " + i + " failed: " + retryException.getMessage());
            }
        }
        
        return "Operation failed after " + maxRetries + " retries";
    }
}
```

### 2. Structured Logging

```java
public class StructuredLoggingPlugin extends DefaultApplicationPlugin {
    
    private static final String CLASS_NAME = StructuredLoggingPlugin.class.getName();
    
    @Override
    public Object execute(Map properties) {
        String requestId = UUID.randomUUID().toString();
        String userId = getCurrentUserId();
        
        // Log entry point
        logInfo("PLUGIN_START", 
            "requestId", requestId,
            "userId", userId,
            "properties", properties.toString());
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Log important checkpoints
            logDebug("VALIDATION_START", "requestId", requestId);
            ValidationResult validation = validate(properties);
            logDebug("VALIDATION_END", 
                "requestId", requestId,
                "valid", validation.isValid());
            
            if (!validation.isValid()) {
                logWarn("VALIDATION_FAILED",
                    "requestId", requestId,
                    "errors", validation.getErrors());
                return validation.getErrorMessage();
            }
            
            // Process business logic
            logDebug("PROCESSING_START", "requestId", requestId);
            String result = processBusinessLogic(properties);
            logDebug("PROCESSING_END", 
                "requestId", requestId,
                "result", result);
            
            return result;
            
        } catch (Exception e) {
            logError("PLUGIN_ERROR",
                "requestId", requestId,
                "error", e.getMessage(),
                "stackTrace", getStackTrace(e));
            return "Error occurred";
            
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            logInfo("PLUGIN_END",
                "requestId", requestId,
                "duration", duration,
                "userId", userId);
        }
    }
    
    private void logInfo(String event, Object... params) {
        LogUtil.info(CLASS_NAME, formatLog(event, params));
    }
    
    private void logDebug(String event, Object... params) {
        if (LogUtil.isDebugEnabled(CLASS_NAME)) {
            LogUtil.debug(CLASS_NAME, formatLog(event, params));
        }
    }
    
    private void logWarn(String event, Object... params) {
        LogUtil.warn(CLASS_NAME, formatLog(event, params));
    }
    
    private void logError(String event, Object... params) {
        LogUtil.error(CLASS_NAME, null, formatLog(event, params));
    }
    
    private String formatLog(String event, Object... params) {
        StringBuilder sb = new StringBuilder();
        sb.append("event=").append(event);
        
        for (int i = 0; i < params.length; i += 2) {
            if (i + 1 < params.length) {
                sb.append(", ").append(params[i]).append("=").append(params[i + 1]);
            }
        }
        
        return sb.toString();
    }
}
```

---

## Configuration Management

### 1. Property Configuration Best Practices

```json
[{
    "title": "Basic Configuration",
    "properties": [{
        "name": "mode",
        "label": "Processing Mode",
        "type": "selectbox",
        "value": "standard",
        "required": "true",
        "options": [{
            "value": "standard",
            "label": "Standard Processing"
        }, {
            "value": "advanced",
            "label": "Advanced Processing"
        }]
    }, {
        "name": "apiEndpoint",
        "label": "API Endpoint",
        "type": "textfield",
        "value": "https://api.example.com",
        "regex_validation": "^https?://.*",
        "validation_message": "Please enter a valid URL"
    }]
}, {
    "title": "Advanced Settings",
    "properties": [{
        "name": "enableCaching",
        "label": "Enable Caching",
        "type": "checkbox",
        "value": "true",
        "control_field": "mode",
        "control_value": "advanced",
        "control_use_regex": "false"
    }, {
        "name": "cacheTimeout",
        "label": "Cache Timeout (seconds)",
        "type": "textfield",
        "value": "300",
        "regex_validation": "^[0-9]+$",
        "validation_message": "Please enter a number",
        "control_field": "enableCaching",
        "control_value": "true"
    }]
}]
```

### 2. Environment-Specific Configuration

```java
public class EnvironmentAwarePlugin extends DefaultApplicationPlugin {
    
    @Override
    public Object execute(Map properties) {
        // Determine environment
        String environment = determineEnvironment();
        
        // Load environment-specific configuration
        Properties envConfig = loadEnvironmentConfig(environment);
        
        // Override with environment-specific values
        String apiUrl = envConfig.getProperty("api.url", 
            getPropertyString("apiUrl"));
        String apiKey = envConfig.getProperty("api.key",
            getPropertyString("apiKey"));
        
        // Use configuration
        return processWithConfig(apiUrl, apiKey);
    }
    
    private String determineEnvironment() {
        // Check system property
        String env = System.getProperty("joget.environment");
        if (env != null) return env;
        
        // Check environment variable
        env = System.getenv("JOGET_ENV");
        if (env != null) return env;
        
        // Default to production
        return "production";
    }
    
    private Properties loadEnvironmentConfig(String environment) {
        Properties props = new Properties();
        String configFile = "/config/" + environment + ".properties";
        
        try (InputStream is = getClass().getResourceAsStream(configFile)) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            LogUtil.warn(getClass().getName(), 
                "Could not load config for environment: " + environment);
        }
        
        return props;
    }
}
```

---

## Testing Strategies

### 1. Unit Testing

```java
import org.junit.jupiter.api.*;
import org.mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginUnitTest {
    
    @Mock
    private WorkflowManager workflowManager;
    
    @Mock
    private DirectoryManager directoryManager;
    
    @InjectMocks
    private MyPlugin plugin;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }
    
    @Test
    @DisplayName("Should process valid input successfully")
    void testProcessValidInput() {
        // Given
        Map<String, Object> properties = new HashMap<>();
        properties.put("inputData", "test data");
        
        // When
        Object result = plugin.execute(properties);
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result.toString()).contains("Success");
    }
    
    @Test
    @DisplayName("Should handle null properties gracefully")
    void testNullProperties() {
        // When
        Object result = plugin.execute(null);
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result.toString()).contains("Error");
    }
    
    @Test
    @DisplayName("Should validate required fields")
    void testRequiredFieldValidation() {
        // Given
        Map<String, Object> properties = new HashMap<>();
        // Missing required field
        
        // When
        Object result = plugin.execute(properties);
        
        // Then
        assertThat(result.toString()).contains("required");
    }
}
```

### 2. Integration Testing

```java
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PluginIntegrationTest {
    
    private static EmbeddedDatabase database;
    private MyPlugin plugin;
    
    @BeforeAll
    static void setUpDatabase() {
        database = new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .addScript("schema.sql")
            .addScript("test-data.sql")
            .build();
    }
    
    @BeforeEach
    void setUp() {
        plugin = new MyPlugin();
        plugin.setDataSource(database);
    }
    
    @Test
    void testDatabaseIntegration() {
        // Given
        Map<String, Object> properties = new HashMap<>();
        properties.put("userId", "test-user");
        
        // When
        Object result = plugin.execute(properties);
        
        // Then
        assertThat(result).isNotNull();
        
        // Verify database state
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM processed_records WHERE user_id = ?",
            Integer.class,
            "test-user"
        );
        assertThat(count).isEqualTo(1);
    }
}
```

---

## Common Issues and Solutions

### Issue 1: Plugin Not Loading

**Symptoms:**
- Plugin JAR is in `/app_plugins` directory
- Plugin doesn't appear in Joget
- No errors in logs

**Diagnosis Steps:**
```bash
# 1. Check file permissions
ls -la /path/to/joget/wflow/app_plugins/

# 2. Verify JAR integrity
jar -tf my-plugin.jar | head

# 3. Check MANIFEST.MF
jar -xf my-plugin.jar META-INF/MANIFEST.MF
cat META-INF/MANIFEST.MF

# 4. Look for OSGi errors
grep -i "bundle\|osgi\|activator" /path/to/joget/logs/joget.log
```

**Solutions:**

1. **Missing Bundle-Activator**
```xml
<plugin>
    <groupId>org.apache.felix</groupId>
    <artifactId>maven-bundle-plugin</artifactId>
    <configuration>
        <instructions>
            <Bundle-Activator>
                com.company.plugin.Activator
            </Bundle-Activator>
        </instructions>
    </configuration>
</plugin>
```

2. **Incorrect Package Structure**
```java
// Activator must be in correct package
package com.company.plugin;

public class Activator implements BundleActivator {
    @Override
    public void start(BundleContext context) {
        // Register plugins
        context.registerService(
            MyPlugin.class.getName(),
            new MyPlugin(),
            null
        );
    }
}
```

### Issue 2: ClassNotFoundException

**Symptoms:**
- Plugin loads but throws ClassNotFoundException
- Works in IDE but not in Joget

**Solutions:**

1. **Fix Import-Package**
```xml
<Import-Package>
    !com.company.plugin.*,
    org.joget.*,
    javax.servlet.*,
    org.apache.commons.lang3.*;resolution:=optional,
    *;resolution:=optional
</Import-Package>
```

2. **Embed Dependencies**
```xml
<Embed-Dependency>
    commons-io,
    commons-lang3,
    json;scope=compile
</Embed-Dependency>
```

### Issue 3: Properties Not Saving

**Symptoms:**
- Configuration changes don't persist
- Properties return null or empty

**Debug Code:**
```java
@Override
public void setProperties(Map<String, Object> properties) {
    LogUtil.info(getClass().getName(), 
        "Setting properties: " + properties);
    
    super.setProperties(properties);
    
    // Verify properties are set
    LogUtil.info(getClass().getName(), 
        "Properties after setting: " + getProperties());
    
    // Check individual properties
    for (String key : properties.keySet()) {
        String value = getPropertyString(key);
        LogUtil.info(getClass().getName(), 
            "Property " + key + " = " + value);
    }
}
```

### Issue 4: Hash Variables Not Resolving

**Symptoms:**
- `#{variable}` appears as literal text
- Hash variables not being processed

**Solution:**
```java
// Ensure hash variables are processed
String template = getPropertyString("template");

// Check if it contains hash variables
if (template.contains("#{") || template.contains("#")) {
    WorkflowAssignment assignment = (WorkflowAssignment) 
        properties.get("workflowAssignment");
    
    // Process hash variables
    template = AppUtil.processHashVariable(
        template, 
        assignment, 
        StringUtil.TYPE_HTML,
        null,
        AppUtil.getCurrentAppDefinition()
    );
}
```

---

## Debugging Techniques

### 1. Remote Debugging

**Setup Joget for Remote Debugging:**
```bash
# Edit setenv.sh or joget-start.sh
export JAVA_OPTS="$JAVA_OPTS -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=*:5005"
```

**IntelliJ Configuration:**
1. Run → Edit Configurations → Add New → Remote JVM Debug
2. Host: localhost
3. Port: 5005
4. Use module classpath: your-plugin

### 2. Diagnostic Plugin

```java
public class DiagnosticPlugin extends DefaultApplicationPlugin {
    
    @Override
    public Object execute(Map properties) {
        StringBuilder diagnosis = new StringBuilder();
        
        diagnosis.append("=== Plugin Diagnostics ===\n\n");
        
        // Environment Information
        diagnosis.append("Environment:\n");
        diagnosis.append("- Java Version: ").append(System.getProperty("java.version")).append("\n");
        diagnosis.append("- OS: ").append(System.getProperty("os.name")).append("\n");
        diagnosis.append("- User: ").append(System.getProperty("user.name")).append("\n\n");
        
        // Plugin Information
        diagnosis.append("Plugin:\n");
        diagnosis.append("- Name: ").append(getName()).append("\n");
        diagnosis.append("- Version: ").append(getVersion()).append("\n");
        diagnosis.append("- Class: ").append(getClass().getName()).append("\n\n");
        
        // Properties
        diagnosis.append("Properties:\n");
        if (properties != null) {
            for (Object key : properties.keySet()) {
                Object value = properties.get(key);
                diagnosis.append("- ").append(key).append(": ");
                
                if (value instanceof WorkflowAssignment) {
                    WorkflowAssignment wa = (WorkflowAssignment) value;
                    diagnosis.append("WorkflowAssignment[")
                            .append("processId=").append(wa.getProcessId())
                            .append(", activityId=").append(wa.getActivityId())
                            .append("]");
                } else {
                    diagnosis.append(value);
                }
                diagnosis.append("\n");
            }
        }
        
        // Available Services
        diagnosis.append("\nAvailable Services:\n");
        ApplicationContext ctx = AppUtil.getApplicationContext();
        String[] beanNames = ctx.getBeanDefinitionNames();
        Arrays.sort(beanNames);
        for (String beanName : beanNames) {
            if (beanName.contains("Service") || beanName.contains("Manager")) {
                diagnosis.append("- ").append(beanName).append("\n");
            }
        }
        
        // Memory Status
        diagnosis.append("\nMemory:\n");
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        long totalMemory = runtime.totalMemory() / 1024 / 1024;
        long freeMemory = runtime.freeMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;
        
        diagnosis.append("- Max Memory: ").append(maxMemory).append(" MB\n");
        diagnosis.append("- Total Memory: ").append(totalMemory).append(" MB\n");
        diagnosis.append("- Used Memory: ").append(usedMemory).append(" MB\n");
        diagnosis.append("- Free Memory: ").append(freeMemory).append(" MB\n");
        
        LogUtil.info(getClass().getName(), diagnosis.toString());
        return diagnosis.toString();
    }
}
```

### 3. Performance Profiling

```java
public class PerformanceProfiler {
    
    private final Map<String, Long> timings = new LinkedHashMap<>();
    private long lastCheckpoint;
    
    public void start() {
        lastCheckpoint = System.currentTimeMillis();
        timings.clear();
    }
    
    public void checkpoint(String name) {
        long now = System.currentTimeMillis();
        long duration = now - lastCheckpoint;
        timings.put(name, duration);
        lastCheckpoint = now;
    }
    
    public String getReport() {
        StringBuilder report = new StringBuilder();
        report.append("Performance Profile:\n");
        
        long total = 0;
        for (Map.Entry<String, Long> entry : timings.entrySet()) {
            report.append(String.format("- %s: %d ms\n", 
                entry.getKey(), entry.getValue()));
            total += entry.getValue();
        }
        
        report.append(String.format("Total: %d ms\n", total));
        return report.toString();
    }
}

// Usage in plugin
public Object execute(Map properties) {
    PerformanceProfiler profiler = new PerformanceProfiler();
    profiler.start();
    
    validateInput(properties);
    profiler.checkpoint("Validation");
    
    loadData();
    profiler.checkpoint("Data Loading");
    
    processBusinessLogic();
    profiler.checkpoint("Processing");
    
    saveResults();
    profiler.checkpoint("Saving");
    
    LogUtil.info(getClass().getName(), profiler.getReport());
    return "Complete";
}
```

---

## Deployment Best Practices

### 1. Pre-Deployment Checklist

```java
public class DeploymentValidator {
    
    public boolean validateForProduction(Plugin plugin) {
        List<String> issues = new ArrayList<>();
        
        // Check version
        if (plugin.getVersion().contains("SNAPSHOT")) {
            issues.add("Version contains SNAPSHOT");
        }
        
        // Check logging
        if (containsDebugLogging(plugin)) {
            issues.add("Debug logging is enabled");
        }
        
        // Check hardcoded values
        if (containsHardcodedValues(plugin)) {
            issues.add("Contains hardcoded values");
        }
        
        // Check error handling
        if (!hasProperErrorHandling(plugin)) {
            issues.add("Missing error handling");
        }
        
        // Check resource cleanup
        if (!hasResourceCleanup(plugin)) {
            issues.add("Missing resource cleanup");
        }
        
        if (!issues.isEmpty()) {
            LogUtil.error(getClass().getName(), null,
                "Production validation failed:\n" + String.join("\n", issues));
            return false;
        }
        
        return true;
    }
}
```

### 2. Deployment Script

```bash
#!/bin/bash

# Deployment script for Joget plugin

PLUGIN_NAME="my-plugin"
VERSION="1.0.0"
ENVIRONMENT=$1

if [ -z "$ENVIRONMENT" ]; then
    echo "Usage: deploy.sh [dev|staging|production]"
    exit 1
fi

# Build plugin
echo "Building plugin..."
mvn clean package

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

# Run tests
echo "Running tests..."
mvn test

if [ $? -ne 0 ]; then
    echo "Tests failed!"
    exit 1
fi

# Backup existing plugin
if [ "$ENVIRONMENT" == "production" ]; then
    BACKUP_DIR="/backup/plugins/$(date +%Y%m%d_%H%M%S)"
    mkdir -p $BACKUP_DIR
    cp /opt/joget/wflow/app_plugins/${PLUGIN_NAME}*.jar $BACKUP_DIR/
    echo "Backed up existing plugin to $BACKUP_DIR"
fi

# Deploy based on environment
case $ENVIRONMENT in
    dev)
        SERVER="dev-server"
        JOGET_PATH="/opt/joget-dev"
        ;;
    staging)
        SERVER="staging-server"
        JOGET_PATH="/opt/joget-staging"
        ;;
    production)
        SERVER="prod-server"
        JOGET_PATH="/opt/joget"
        
        # Additional confirmation for production
        read -p "Deploy to PRODUCTION? Type 'yes' to confirm: " confirm
        if [ "$confirm" != "yes" ]; then
            echo "Deployment cancelled"
            exit 0
        fi
        ;;
esac

# Copy plugin
echo "Deploying to $ENVIRONMENT..."
scp target/${PLUGIN_NAME}-${VERSION}.jar joget@${SERVER}:${JOGET_PATH}/wflow/app_plugins/

# Restart Joget (optional)
if [ "$ENVIRONMENT" == "production" ]; then
    echo "Please manually restart Joget when ready"
else
    ssh joget@${SERVER} "sudo systemctl restart joget"
    echo "Joget restarted"
fi

echo "Deployment complete!"
```

---

## Anti-Patterns to Avoid

### 1. ❌ Hardcoding Values

```java
// Bad
public Object execute(Map properties) {
    sendEmail("admin@company.com", "Alert", "Something happened");
    connectToDatabase("192.168.1.100", "3306", "mydb", "root", "password123");
}

// Good
public Object execute(Map properties) {
    String recipient = getPropertyString("alertRecipient");
    String dbHost = getPropertyString("dbHost");
    String dbPort = getPropertyString("dbPort");
    // Use configuration
}
```

### 2. ❌ Ignoring Null Checks

```java
// Bad
public Object execute(Map properties) {
    WorkflowAssignment assignment = (WorkflowAssignment) properties.get("workflowAssignment");
    String processId = assignment.getProcessId(); // NPE if no workflow!
}

// Good
public Object execute(Map properties) {
    WorkflowAssignment assignment = (WorkflowAssignment) properties.get("workflowAssignment");
    if (assignment != null) {
        String processId = assignment.getProcessId();
    }
}
```

### 3. ❌ Swallowing Exceptions

```java
// Bad
public Object execute(Map properties) {
    try {
        return riskyOperation();
    } catch (Exception e) {
        // Silent failure - very bad!
    }
    return null;
}

// Good
public Object execute(Map properties) {
    try {
        return riskyOperation();
    } catch (SpecificException e) {
        LogUtil.error(getClass().getName(), e, "Operation failed");
        return "Error: " + e.getMessage();
    }
}
```

### 4. ❌ Resource Leaks

```java
// Bad
public Object execute(Map properties) {
    Connection conn = getConnection();
    PreparedStatement stmt = conn.prepareStatement(sql);
    ResultSet rs = stmt.executeQuery();
    // Resources never closed!
    return processResults(rs);
}

// Good
public Object execute(Map properties) {
    try (Connection conn = getConnection();
         PreparedStatement stmt = conn.prepareStatement(sql);
         ResultSet rs = stmt.executeQuery()) {
        return processResults(rs);
    } catch (SQLException e) {
        LogUtil.error(getClass().getName(), e, "Database error");
        return "Error";
    }
}
```

---

## Advanced Troubleshooting

### 1. Memory Leak Detection

```java
public class MemoryMonitor {
    
    private static final Map<String, WeakReference<Object>> monitoredObjects = 
        new ConcurrentHashMap<>();
    
    public static void monitor(String key, Object object) {
        monitoredObjects.put(key, new WeakReference<>(object));
    }
    
    public static void checkForLeaks() {
        System.gc(); // Suggest garbage collection
        
        for (Map.Entry<String, WeakReference<Object>> entry : monitoredObjects.entrySet()) {
            if (entry.getValue().get() != null) {
                LogUtil.warn(MemoryMonitor.class.getName(),
                    "Potential memory leak: " + entry.getKey() + " still in memory");
            }
        }
    }
}
```

### 2. Thread Safety Issues

```java
// Thread-safe plugin implementation
public class ThreadSafePlugin extends DefaultApplicationPlugin {
    
    // Use thread-safe collections
    private final Map<String, Object> cache = new ConcurrentHashMap<>();
    
    // Use ThreadLocal for thread-specific data
    private final ThreadLocal<SimpleDateFormat> dateFormat = 
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
    
    // Synchronize access to shared mutable state
    private int counter = 0;
    
    public synchronized void incrementCounter() {
        counter++;
    }
    
    // Use immutable objects where possible
    private final String configValue;
    
    public ThreadSafePlugin() {
        this.configValue = loadConfiguration();
    }
}
```

### 3. Deadlock Prevention

```java
public class DeadlockFreePlugin extends DefaultApplicationPlugin {
    
    private final Object lock1 = new Object();
    private final Object lock2 = new Object();
    
    // Always acquire locks in the same order
    public void method1() {
        synchronized (lock1) {
            synchronized (lock2) {
                // Work here
            }
        }
    }
    
    public void method2() {
        synchronized (lock1) {  // Same order as method1
            synchronized (lock2) {
                // Work here
            }
        }
    }
    
    // Or use tryLock with timeout
    private final ReentrantLock lock = new ReentrantLock();
    
    public boolean tryOperation() {
        try {
            if (lock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    // Perform operation
                    return true;
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }
}
```

---

## Performance Troubleshooting

### 1. Identifying Bottlenecks

```java
public class PerformanceAnalyzer {
    
    public void analyzePerformance(Map properties) {
        StopWatch watch = new StopWatch();
        
        watch.start("Database Query");
        List<Record> records = queryDatabase();
        watch.stop();
        
        watch.start("API Call");
        ApiResponse response = callExternalApi();
        watch.stop();
        
        watch.start("Data Processing");
        ProcessedData data = processData(records, response);
        watch.stop();
        
        watch.start("Result Generation");
        String result = generateResult(data);
        watch.stop();
        
        // Log performance metrics
        LogUtil.info(getClass().getName(), 
            "Performance Analysis:\n" + watch.prettyPrint());
        
        // Identify slow operations
        for (StopWatch.TaskInfo task : watch.getTaskInfo()) {
            if (task.getTimeMillis() > 1000) {
                LogUtil.warn(getClass().getName(),
                    "Slow operation detected: " + task.getTaskName() + 
                    " took " + task.getTimeMillis() + "ms");
            }
        }
    }
}
```

### 2. Database Query Optimization

```java
public class OptimizedDatabasePlugin extends DefaultApplicationPlugin {
    
    // Use prepared statement cache
    private final Map<String, PreparedStatement> statementCache = 
        new ConcurrentHashMap<>();
    
    public List<Record> efficientQuery(Connection conn, String criteria) 
            throws SQLException {
        
        // Use index-friendly queries
        String sql = "SELECT * FROM app_fd_records " +
                    "WHERE c_status = ? AND c_created_date > ? " +
                    "ORDER BY c_created_date DESC " +
                    "LIMIT 100";
        
        PreparedStatement stmt = statementCache.computeIfAbsent(sql, 
            k -> {
                try {
                    return conn.prepareStatement(k);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
        
        // Use batch fetching
        stmt.setFetchSize(100);
        
        stmt.setString(1, "active");
        stmt.setTimestamp(2, new Timestamp(System.currentTimeMillis() - 86400000));
        
        List<Record> results = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                results.add(mapResultToRecord(rs));
            }
        }
        
        return results;
    }
}
```

---

## Production Readiness Checklist

### Pre-Production Validation

```java
public class ProductionReadinessChecker {
    
    public boolean isProductionReady(Plugin plugin) {
        List<String> issues = new ArrayList<>();
        
        // Version check
        if (!isValidVersion(plugin.getVersion())) {
            issues.add("Invalid version format");
        }
        
        // Security checks
        if (!hasSecurityValidation(plugin)) {
            issues.add("Missing security validation");
        }
        
        // Performance checks
        if (!hasPerformanceOptimization(plugin)) {
            issues.add("Missing performance optimization");
        }
        
        // Error handling
        if (!hasComprehensiveErrorHandling(plugin)) {
            issues.add("Incomplete error handling");
        }
        
        // Documentation
        if (!hasAdequateDocumentation(plugin)) {
            issues.add("Insufficient documentation");
        }
        
        // Testing
        if (!hasTestCoverage(plugin)) {
            issues.add("Inadequate test coverage");
        }
        
        // Logging
        if (!hasProperLogging(plugin)) {
            issues.add("Improper logging configuration");
        }
        
        // Resource management
        if (!hasResourceCleanup(plugin)) {
            issues.add("Resource cleanup issues");
        }
        
        if (!issues.isEmpty()) {
            LogUtil.error(getClass().getName(), null,
                "Production readiness check failed:\n" + 
                String.join("\n- ", issues));
            return false;
        }
        
        LogUtil.info(getClass().getName(), 
            "Plugin " + plugin.getName() + " is production ready");
        return true;
    }
}
```

### Final Checklist

- [ ] **Version Management**
  - [ ] Semantic versioning used
  - [ ] No SNAPSHOT versions
  - [ ] Change log updated

- [ ] **Security**
  - [ ] Input validation implemented
  - [ ] SQL injection prevention
  - [ ] XSS prevention
  - [ ] Sensitive data encrypted
  - [ ] CSRF protection enabled

- [ ] **Performance**
  - [ ] Caching implemented where appropriate
  - [ ] Connection pooling configured
  - [ ] Batch processing for large datasets
  - [ ] Lazy loading for expensive resources
  - [ ] No memory leaks

- [ ] **Error Handling**
  - [ ] All exceptions caught and handled
  - [ ] User-friendly error messages
  - [ ] Error logging with context
  - [ ] Retry logic for transient failures
  - [ ] Graceful degradation

- [ ] **Testing**
  - [ ] Unit tests written (>80% coverage)
  - [ ] Integration tests completed
  - [ ] Performance tests passed
  - [ ] Security tests performed
  - [ ] User acceptance testing done

- [ ] **Documentation**
  - [ ] Code properly commented
  - [ ] README file complete
  - [ ] Configuration guide written
  - [ ] API documentation if applicable
  - [ ] Troubleshooting guide provided

- [ ] **Monitoring**
  - [ ] Appropriate logging levels
  - [ ] Performance metrics tracked
  - [ ] Health checks implemented
  - [ ] Alert thresholds configured
  - [ ] Audit trail maintained

- [ ] **Deployment**
  - [ ] Deployment scripts tested
  - [ ] Rollback procedure documented
  - [ ] Configuration externalized
  - [ ] Dependencies documented
  - [ ] Compatible with target Joget version

---

## Conclusion

This comprehensive guide covers best practices and troubleshooting techniques for Joget DX8 plugin development. Key takeaways:

1. **Follow the Joget-ic Way**: Configuration over code, leverage platform features
2. **Prioritize Security**: Always validate, sanitize, and protect data
3. **Optimize Performance**: Use caching, pooling, and efficient algorithms
4. **Handle Errors Gracefully**: Comprehensive error handling with proper logging
5. **Test Thoroughly**: Unit, integration, and performance testing
6. **Document Everything**: Code, configuration, and troubleshooting guides
7. **Monitor Production**: Implement logging, metrics, and health checks

Remember:
- Start with simple, working code, then optimize
- Use Joget's built-in services instead of reinventing
- Test in an environment similar to production
- Keep security at the forefront of development
- Document as you develop, not after

By following these best practices and using the troubleshooting techniques provided, you'll build robust, secure, and performant Joget plugins that are production-ready and maintainable.