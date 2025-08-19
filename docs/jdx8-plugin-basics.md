# Joget DX8 Plugin Basics - Developer's Guide

## Table of Contents

1. [Introduction](#introduction)
2. [What is a Joget Plugin?](#what-is-a-joget-plugin)
3. [Plugin Architecture Overview](#plugin-architecture-overview)
4. [OSGi Framework in Joget](#osgi-framework-in-joget)
5. [Plugin Class Hierarchy](#plugin-class-hierarchy)
6. [Property System](#property-system)
7. [Plugin Lifecycle](#plugin-lifecycle)
8. [Getting Started](#getting-started)
9. [Your First Plugin](#your-first-plugin)
10. [Development Best Practices](#development-best-practices)

---

## Introduction

Joget DX8 is a no-code/low-code platform that emphasizes configuration over coding. However, when you need custom functionality beyond what's available out-of-the-box, plugins provide a powerful extension mechanism. This guide covers the fundamental concepts you need to understand before developing Joget plugins.

### Who Should Read This Guide?

- Java developers new to Joget plugin development
- System integrators extending Joget functionality
- Technical architects designing Joget-based solutions
- Anyone wanting to understand how Joget's extensibility works

### Prerequisites

- Java 11+ programming experience
- Basic understanding of Maven
- Familiarity with enterprise application concepts
- Joget DX8 platform basics (recommended)

---

## What is a Joget Plugin?

A Joget plugin is a Java component that extends the platform's functionality. Think of plugins as "building blocks" that you can plug into Joget to add new features, integrate with external systems, or customize business logic.

### Plugin Characteristics

- **Hot-deployable**: Add or update plugins without restarting the server
- **Configurable**: Users can configure plugins through UI without coding
- **Isolated**: Each plugin runs in its own space, preventing conflicts
- **Reusable**: Write once, use in multiple apps and processes

### Common Plugin Use Cases

1. **Process Automation**: Execute custom business logic in workflows
2. **Form Processing**: Validate data, transform inputs, or trigger actions after form submission
3. **System Integration**: Connect to external APIs, databases, or services
4. **Custom UI Elements**: Create specialized form fields or display components
5. **Data Processing**: Custom data lists, reports, or transformations
6. **Authentication**: Custom login providers or security mechanisms

---

## Plugin Architecture Overview

Joget's plugin architecture is built on three key technologies:

### 1. PluginManager - The Orchestrator

The PluginManager is Joget's central command center for all plugin operations. It handles:

```java
public class PluginManager {
    // Core responsibilities:
    // 1. Plugin Discovery & Loading
    // 2. Plugin Lifecycle Management  
    // 3. Plugin Caching & Performance
    // 4. Hot Deployment Support
}
```

**What it does for you:**
- Automatically detects new plugin JAR files
- Loads and unloads plugins dynamically
- Manages plugin dependencies
- Provides plugin caching for performance

### 2. OSGi Framework - The Foundation

Joget uses Apache Felix (OSGi implementation) to provide:
- **Module isolation**: Each plugin is a separate module
- **Dynamic loading**: Add/remove plugins at runtime
- **Version management**: Multiple plugin versions can coexist
- **Service registry**: Plugins can share services

### 3. Spring Framework - The Glue

Spring provides:
- Dependency injection for plugin components
- Application context management
- Bean lifecycle management
- Integration with Joget's core services

---

## OSGi Framework in Joget

OSGi (Open Service Gateway Initiative) is like a "smart apartment building" where each plugin is a separate apartment that can be:

- **Installed** without affecting others
- **Updated** individually
- **Started/stopped** independently
- **Share services** with neighbors

### How OSGi Works in Joget

```java
// Each plugin is an OSGi "Bundle"
public class MyPlugin extends DefaultPlugin {
    
    // OSGi calls this when plugin is loaded
    public void start(BundleContext context) {
        // Register your plugin service
        registration = context.registerService(
            getClass().getName(), this, null
        );
    }
    
    // OSGi calls this when plugin is unloaded
    public void stop(BundleContext context) {
        // Clean up resources
        registration.unregister();
    }
}
```

### Benefits for Developers

1. **Isolation**: Your plugin won't crash others
2. **Hot Deployment**: Update without server restart
3. **Dependency Management**: Automatic JAR conflict handling
4. **Version Control**: Multiple versions can coexist

---

## Plugin Class Hierarchy

Think of the plugin class hierarchy as "building blocks" where each level adds more capabilities:

```
Plugin (Interface)
    ↓
DefaultPlugin (OSGi Integration)
    ↓
ExtDefaultPlugin (Property Management)
    ↓
DefaultApplicationPlugin (Business Logic)
    ↓
Your Custom Plugin
```

### 1. Plugin Interface - The Contract

```java
public interface Plugin {
    String getName();           // Unique identifier
    String getVersion();        // Plugin version
    String getDescription();    // What does it do?
    String getClassName();      // Full class name
}
```

**What it means**: Every plugin must provide basic identification information.

### 2. DefaultPlugin - OSGi Integration Layer

```java
public abstract class DefaultPlugin implements Plugin, BundleActivator {
    protected ServiceRegistration registration;
    
    // Automatic i18n support
    public String getI18nLabel() {
        return ResourceBundleUtil.getMessage(
            getClass().getName() + ".pluginLabel"
        );
    }
}
```

**What it provides:**
- OSGi framework integration (automatic)
- Internationalization support (multi-language)
- Plugin registration/unregistration (automatic)

**When to use**: You DON'T extend this directly. Always use ExtDefaultPlugin or higher.

### 3. ExtDefaultPlugin - Property Management Layer

```java
public abstract class ExtDefaultPlugin extends DefaultPlugin {
    protected Map<String, Object> properties;
    
    // Property management
    public String getPropertyString(String property) {
        Object value = (properties != null) ? 
            properties.get(property) : null;
        return (value != null) ? value.toString() : "";
    }
    
    public void setProperty(String property, Object value) {
        if (properties == null) {
            properties = new HashMap<String, Object>();
        }
        properties.put(property, value);
    }
}
```

**What it provides:**
- Property storage and retrieval
- Hash variable support (automatic processing of `#{variable}`)
- Configuration management

**When to use**: For plugins that need configuration but don't execute business logic directly.

### 4. DefaultApplicationPlugin - Business Logic Layer

```java
public abstract class DefaultApplicationPlugin extends ExtDefaultPlugin 
    implements ApplicationPlugin, PropertyEditable {
    
    // This is where your business logic goes
    public abstract Object execute(Map props);
}
```

**What it provides:**
- Business logic execution framework
- WorkflowAssignment integration
- Property configuration UI support

**When to use**: For workflow tools, form processors, decision plugins - anything that executes business logic.

---

## Property System

The property system allows users to configure your plugin without touching code. It's like a "settings panel" for your plugin.

### Property Definition (JSON)

```json
[{
    "title": "Basic Configuration",
    "properties": [{
        "name": "apiUrl",
        "label": "API URL",
        "type": "textfield",
        "required": "true",
        "value": "https://api.example.com"
    }, {
        "name": "apiKey",
        "label": "API Key",
        "type": "password",
        "required": "true"
    }, {
        "name": "retryCount",
        "label": "Retry Count",
        "type": "selectbox",
        "options": [{
            "value": "1",
            "label": "Once"
        }, {
            "value": "3",
            "label": "Three Times"
        }]
    }]
}]
```

### Property Types

- **textfield**: Single-line text input
- **textarea**: Multi-line text input
- **password**: Password field (masked)
- **selectbox**: Dropdown selection
- **checkbox**: Boolean selection
- **radio**: Radio button group
- **grid**: Table/grid for multiple entries
- **elementselect**: Select another plugin dynamically

### Using Properties in Your Plugin

```java
@Override
public Object execute(Map properties) {
    // Get configured values
    String apiUrl = getPropertyString("apiUrl");
    String apiKey = getPropertyString("apiKey");
    int retryCount = Integer.parseInt(
        getPropertyString("retryCount")
    );
    
    // Hash variables are automatically processed
    // If apiUrl = "#{envVariable.API_URL}", it's resolved here
    
    // Your business logic using these values
    return callApi(apiUrl, apiKey, retryCount);
}
```

### Hash Variable Support

Properties automatically support Joget hash variables:

```java
// User configures: "Hello #{currentUser.firstName}"
String message = getPropertyString("message");
// Result: "Hello John" (hash variable is resolved)
```

---

## Plugin Lifecycle

Understanding the plugin lifecycle helps you write robust, efficient plugins.

### 1. Installation Phase

```
JAR copied to plugin directory
    ↓
PluginManager detects new file
    ↓
OSGi loads the bundle
    ↓
Plugin.start() called
    ↓
Plugin registered in system
```

### 2. Configuration Phase

```
User opens plugin configuration
    ↓
getPropertyOptions() returns JSON schema
    ↓
UI renders configuration form
    ↓
User saves configuration
    ↓
setProperties() stores values
```

### 3. Execution Phase

```
Plugin triggered (by workflow/form/etc)
    ↓
execute() method called with properties
    ↓
Hash variables resolved
    ↓
Business logic runs
    ↓
Result returned
```

### 4. Update/Refresh Phase

```
New JAR version deployed
    ↓
Old plugin.stop() called
    ↓
Resources cleaned up
    ↓
New version loaded
    ↓
New plugin.start() called
```

### Lifecycle Best Practices

1. **Initialization**: Use constructor for one-time setup
2. **Resources**: Clean up in stop() method
3. **Configuration**: Validate in setProperties()
4. **Execution**: Handle errors gracefully in execute()

---

## Getting Started

### Development Environment Setup

#### 1. Install Prerequisites

```bash
# Verify Java 11+
java -version

# Verify Maven 3.6+
mvn -version

# Install IDE (IntelliJ IDEA recommended)
```

#### 2. Create Project Structure

```
your-plugin/
├── pom.xml
├── src/
│   └── main/
│       ├── java/
│       │   └── com/
│       │       └── company/
│       │           └── plugin/
│       │               ├── Activator.java
│       │               └── MyPlugin.java
│       └── resources/
│           └── messages/
│               └── MyPlugin.properties
```

#### 3. Maven Configuration (pom.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    
    <groupId>com.company</groupId>
    <artifactId>my-joget-plugin</artifactId>
    <version>1.0.0</version>
    <packaging>bundle</packaging>
    
    <properties>
        <joget.version>8.0-SNAPSHOT</joget.version>
        <java.version>11</java.version>
    </properties>
    
    <repositories>
        <repository>
            <id>central</id>
            <url>https://repo.maven.apache.org/maven2</url>
        </repository>
        <repository>
            <id>joget-repo</id>
            <url>https://dev.joget.org/archiva/repository/internal</url>
            <snapshots>
                <enabled>false</enabled>
            </snapshots>
        </repository>
    </repositories>
    
    <dependencies>
        <dependency>
            <groupId>org.joget</groupId>
            <artifactId>wflow-core</artifactId>
            <version>${joget.version}</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.felix</groupId>
                <artifactId>maven-bundle-plugin</artifactId>
                <version>5.1.8</version>
                <extensions>true</extensions>
                <configuration>
                    <instructions>
                        <Bundle-Activator>
                            com.company.plugin.Activator
                        </Bundle-Activator>
                        <Import-Package>
                            !com.company.plugin.*,
                            *
                        </Import-Package>
                    </instructions>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## Your First Plugin

Let's create a simple "Hello World" process tool plugin.

### Step 1: Create the Activator

```java
package com.company.plugin;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import java.util.ArrayList;
import java.util.Collection;

public class Activator implements BundleActivator {
    
    protected Collection<ServiceRegistration> registrationList;
    
    public void start(BundleContext context) {
        registrationList = new ArrayList<>();
        
        // Register your plugin
        registrationList.add(context.registerService(
            HelloWorldPlugin.class.getName(),
            new HelloWorldPlugin(),
            null
        ));
    }
    
    public void stop(BundleContext context) {
        // Unregister all plugins
        for (ServiceRegistration registration : registrationList) {
            registration.unregister();
        }
    }
}
```

### Step 2: Create the Plugin Class

```java
package com.company.plugin;

import org.joget.apps.app.service.AppUtil;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.plugin.base.PluginManager;
import java.util.Map;

public class HelloWorldPlugin extends DefaultApplicationPlugin {
    
    @Override
    public String getName() {
        return "HelloWorldPlugin";
    }
    
    @Override
    public String getVersion() {
        return "1.0.0";
    }
    
    @Override
    public String getLabel() {
        return "Hello World Process Tool";
    }
    
    @Override
    public String getDescription() {
        return "A simple plugin that logs Hello World";
    }
    
    @Override
    public String getClassName() {
        return getClass().getName();
    }
    
    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(
            getClass().getName(),
            "/properties/HelloWorldPlugin.json",
            null,
            true,
            null
        );
    }
    
    @Override
    public Object execute(Map properties) {
        // Get configuration
        String message = getPropertyString("message");
        String logLevel = getPropertyString("logLevel");
        
        // Default message if not configured
        if (message == null || message.isEmpty()) {
            message = "Hello World!";
        }
        
        // Log the message
        if ("ERROR".equals(logLevel)) {
            LogUtil.error(getClassName(), null, message);
        } else if ("WARN".equals(logLevel)) {
            LogUtil.warn(getClassName(), message);
        } else {
            LogUtil.info(getClassName(), message);
        }
        
        // Return success
        return "Message logged: " + message;
    }
}
```

### Step 3: Create Property Configuration

Create `src/main/resources/properties/HelloWorldPlugin.json`:

```json
[{
    "title": "Configuration",
    "properties": [{
        "name": "message",
        "label": "Message to Log",
        "type": "textfield",
        "value": "Hello World!",
        "description": "The message that will be logged"
    }, {
        "name": "logLevel",
        "label": "Log Level",
        "type": "selectbox",
        "value": "INFO",
        "options": [{
            "value": "INFO",
            "label": "Information"
        }, {
            "value": "WARN",
            "label": "Warning"
        }, {
            "value": "ERROR",
            "label": "Error"
        }]
    }]
}]
```

### Step 4: Build and Deploy

```bash
# Build the plugin
mvn clean package

# Copy to Joget plugin directory
cp target/my-joget-plugin-1.0.0.jar /path/to/joget/wflow/app_plugins/

# Restart Joget or wait for auto-detection
```

### Step 5: Use Your Plugin

1. Open Joget App Composer
2. Edit a process
3. Add a "Tool" to any activity
4. Select "Hello World Process Tool" from the list
5. Configure the message and log level
6. Save and test

---

## Development Best Practices

### 1. Error Handling

Always handle exceptions gracefully:

```java
@Override
public Object execute(Map properties) {
    try {
        // Your business logic
        return processData();
    } catch (Exception e) {
        LogUtil.error(getClassName(), e, "Processing failed");
        // Return meaningful error
        return "Error: " + e.getMessage();
    }
}
```

### 2. Resource Management

Clean up resources properly:

```java
Connection conn = null;
try {
    conn = getConnection();
    // Use connection
} finally {
    if (conn != null) {
        try {
            conn.close();
        } catch (SQLException e) {
            LogUtil.error(getClassName(), e, "Failed to close connection");
        }
    }
}
```

### 3. Property Validation

Validate configuration in setProperties():

```java
@Override
public void setProperties(Map<String, Object> properties) {
    super.setProperties(properties);
    
    // Validate required fields
    String apiUrl = getPropertyString("apiUrl");
    if (apiUrl == null || apiUrl.isEmpty()) {
        throw new IllegalArgumentException("API URL is required");
    }
    
    // Validate format
    if (!apiUrl.startsWith("http")) {
        throw new IllegalArgumentException("Invalid API URL format");
    }
}
```

### 4. Logging Best Practices

Use appropriate log levels:

```java
// Debug - Development only
LogUtil.debug(getClassName(), "Processing record: " + recordId);

// Info - Important business events
LogUtil.info(getClassName(), "Order processed: " + orderId);

// Warn - Recoverable issues
LogUtil.warn(getClassName(), "Retry attempt " + attempt);

// Error - Failures requiring attention
LogUtil.error(getClassName(), e, "Failed to process order: " + orderId);
```

### 5. Performance Considerations

1. **Cache expensive operations**:
```java
private static Map<String, Object> cache = new HashMap<>();

public Object getCachedData(String key) {
    if (!cache.containsKey(key)) {
        cache.put(key, loadExpensiveData(key));
    }
    return cache.get(key);
}
```

2. **Use connection pooling** for database operations
3. **Implement timeouts** for external API calls
4. **Process large datasets in batches**

### 6. Security Best Practices

1. **Never hardcode credentials**
2. **Validate all inputs**
3. **Use parameterized queries** for SQL
4. **Escape output** to prevent XSS
5. **Check permissions** before sensitive operations

### 7. Testing Your Plugin

Create a test harness:

```java
public class PluginTest {
    public static void main(String[] args) {
        // Create plugin instance
        HelloWorldPlugin plugin = new HelloWorldPlugin();
        
        // Set properties
        Map<String, Object> props = new HashMap<>();
        props.put("message", "Test Message");
        props.put("logLevel", "INFO");
        plugin.setProperties(props);
        
        // Execute
        Object result = plugin.execute(props);
        System.out.println("Result: " + result);
    }
}
```

### 8. Documentation

Always document your plugin:

1. **Code comments** for complex logic
2. **Property descriptions** in JSON configuration
3. **README.md** with usage examples
4. **Version history** for updates

---

## Common Pitfalls and Solutions

### Issue 1: Plugin Not Loading

**Symptoms**: Plugin JAR in directory but not appearing in Joget

**Solutions**:
- Check Activator registration
- Verify Bundle-Activator in pom.xml
- Check logs for OSGi errors
- Ensure unique plugin name

### Issue 2: ClassNotFoundException

**Symptoms**: Plugin loads but throws class not found errors

**Solutions**:
- Add missing dependencies to pom.xml
- Check Import-Package configuration
- Verify scope is "provided" for Joget libraries

### Issue 3: Properties Not Saving

**Symptoms**: Configuration doesn't persist

**Solutions**:
- Verify JSON syntax in property definition
- Check property names match in code
- Ensure getPropertyOptions() returns valid JSON

### Issue 4: Hash Variables Not Resolving

**Symptoms**: #{variable} appears as literal text

**Solutions**:
- Use getPropertyString() not direct map access
- Ensure PropertyUtil.getHashVariableSupportedMap() is used
- Check hash variable syntax

---

## Next Steps

Now that you understand the basics:

1. **Explore Plugin Types**: Learn about different plugin types (Form Elements, Validators, Data Lists, etc.)
2. **Study Core APIs**: Deep dive into FormUtil, AppUtil, WorkflowManager
3. **Review Examples**: Analyze Joget's built-in plugins for patterns
4. **Build Complex Plugins**: Create plugins that integrate with external systems
5. **Contribute**: Share your plugins with the Joget community

---

## Conclusion

This guide covered the fundamental concepts of Joget plugin development:

- **Architecture**: How plugins fit into Joget's ecosystem
- **OSGi Framework**: Module isolation and hot deployment
- **Class Hierarchy**: Building blocks for different plugin types
- **Property System**: Making plugins configurable
- **Lifecycle**: From installation to execution
- **Best Practices**: Writing robust, secure, performant plugins

Remember:
- Start simple and gradually add complexity
- Always handle errors gracefully
- Use Joget's built-in services instead of reinventing
- Test thoroughly before production deployment
- Keep learning from the community and documentation

Happy plugin development!