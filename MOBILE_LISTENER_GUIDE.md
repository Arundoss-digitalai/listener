# Mobile Listener Framework for Appium

A custom listener mechanism for Appium that automatically handles unexpected UI interrupts (loaders, dialogs, alerts) without explicit if-else conditions in your test code.

**Inspired by**: [SeeTest Automation Mobile Listeners](https://docs.digital.ai/continuous-testing/docs/sta/seetest-automation-home/seetestautomation-how-to/seetestautomation-working-from-code-environment/seetestautomation-java-clients/seetestautomation-java-junit4/seetestautomation-using-mobile-listeners-in-java)

## 🎯 How It Works

1. **Register Listeners** - Define elements (locators) that may appear during test execution
2. **Background Monitoring** - A daemon thread monitors for these elements every 100ms
3. **Automatic Handling** - When an element is detected, execute a predefined or custom action
4. **Non-Blocking** - Monitoring happens in background without blocking main test thread

## 📦 Components

### 1. `MobileListener` Interface
Custom callback for handling detected elements:

```java
public interface MobileListener {
    boolean recover(String locator);
}
```

### 2. `ListenerAction` Enum
Predefined actions:
- `CLICK` - Click on element
- `DISMISS` - Dismiss dialog/alert
- `SEND_KEYS` - Send keys to element
- `WAIT_AND_DISMISS` - Wait 500ms then click
- `CUSTOM` - Use custom listener callback

### 3. `ListenerConfig` Class
Configuration holder for each listener with:
- Locator (By object)
- Action type
- Timeout (default 60s)
- Poll interval (default 500ms)
- Enable/disable toggle

### 4. `AppiumMobileListenerManager` Class
Main manager that:
- Registers listeners
- Manages background monitoring thread
- Executes recovery actions
- Thread-safe operations (CopyOnWriteArrayList)

## 🚀 Usage Examples

### Setup in @BeforeEach

```java
@BeforeEach
public void setUp() throws MalformedURLException {
    // ... initialize driver ...
    driver = new AndroidDriver<>(new URL("..."), dc);
    
    // Initialize listener manager
    listenerManager = new AppiumMobileListenerManager(driver);
}
```

### Example 1: Simple CLICK Action
```java
// Register listener to click on element when found
listenerManager.addMobileListener(
    "id=com.app:id/dismissButton",
    ListenerAction.CLICK
);
```

### Example 2: Locator Formats Supported
```java
// ID format
listenerManager.addMobileListener("id=android:id/button1", ListenerAction.CLICK);

// XPath format
listenerManager.addMobileListener("xpath=//android.widget.Button[@text='OK']", ListenerAction.CLICK);

// XPath shorthand
listenerManager.addMobileListener("xpath://android.widget.ProgressBar", ListenerAction.WAIT_AND_DISMISS);

// Text locator (converts to XPath)
listenerManager.addMobileListener("text=Loading", ListenerAction.WAIT_AND_DISMISS);

// Class name
listenerManager.addMobileListener("class=android.widget.AlertDialog", ListenerAction.CLICK);
```

### Example 3: Custom Listener with Callback
```java
// Register with custom logic
listenerManager.addMobileListener(
    "xpath=//*[contains(@resource-id, 'progressBar')]",
    new MobileListener() {
        @Override
        public boolean recover(String locator) {
            System.out.println("🔄 Loading detected...");
            try {
                // Wait up to 60 seconds for element to disappear
                for (int i = 0; i < 60; i++) {
                    try {
                        driver.findElement(By.xpath("//*[contains(@resource-id, 'progressBar')]"));
                        Thread.sleep(1000);
                    } catch (NoSuchElementException e) {
                        System.out.println("✓ Loading finished!");
                        return true;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
);
```

### Example 4: Multiple Listeners
```java
// Register multiple listeners for different elements
listenerManager.addMobileListener("id=progressBar", ListenerAction.WAIT_AND_DISMISS);
listenerManager.addMobileListener("xpath=//android.widget.AlertDialog", ListenerAction.CLICK);
listenerManager.addMobileListener(
    "id=popup_close_button",
    new MobileListener() {
        @Override
        public boolean recover(String locator) {
            // Custom handling
            return true;
        }
    }
);
```

## 🎮 Manager Operations

### Add Listeners
```java
// With predefined action
listenerManager.addMobileListener("id=loadingSpinner", ListenerAction.WAIT_AND_DISMISS);

// With custom callback
listenerManager.addMobileListener("xpath=//dialog", myListener);

// With By locator
listenerManager.addMobileListener(By.id("popup"), myListener);
```

### Remove Listeners
```java
listenerManager.removeMobileListener("id=loadingSpinner");
```

### Disable/Enable
```java
// Disable all listeners temporarily
listenerManager.disableAllListeners();

// Enable all listeners
listenerManager.enableAllListeners();

// Or disable/enable individual configs via ListenerConfig object
```

### Clear All
```java
listenerManager.clearListeners();
```

### Shutdown
```java
// In @AfterEach
@AfterEach
public void tearDown() {
    if (listenerManager != null) {
        listenerManager.shutdown();
    }
    driver.quit();
}
```

## 📊 Complete Test Example

```java
public class MobileListenerExampleTest {

    protected AndroidDriver<AndroidElement> driver = null;
    DesiredCapabilities dc = new DesiredCapabilities();
    private AppiumMobileListenerManager listenerManager;

    @BeforeEach
    public void setUp() throws MalformedURLException {
        dc.setCapability("digitalai:testName", "Loader Test");
        dc.setCapability("digitalai:accessKey", "YOUR_KEY");
        // ... other capabilities ...
        driver = new AndroidDriver<>(new URL("https://uscloud.experitest.com/wd/hub"), dc);
        
        // Initialize listener
        listenerManager = new AppiumMobileListenerManager(driver);
        
        // Register handlers for loaders and dialogs
        listenerManager.addMobileListener(
            "xpath=//*[contains(@class, 'ProgressBar')]",
            ListenerAction.WAIT_AND_DISMISS
        );
    }

    @Test
    public void testWithLoaders() {
        // No need for explicit waits or if-else checks!
        // Loaders will be handled automatically in background
        driver.findElement(By.id("username")).sendKeys("user");
        driver.findElement(By.id("password")).sendKeys("pass");
        driver.findElement(By.id("login")).click();
        // If loader appears, it's handled automatically
        
        // Continue with test
        WebElement element = driver.findElement(By.id("dashboard"));
        assertTrue(element.isDisplayed());
    }

    @AfterEach
    public void tearDown() {
        if (listenerManager != null) {
            listenerManager.shutdown();
        }
        driver.quit();
    }
}
```

## ⚙️ Configuration Options

### Listener Config Properties

```java
ListenerConfig config = new ListenerConfig(locator, action);

// Set custom timeout (default 60s)
config.setTimeout(120);

// Set poll interval (default 500ms) - lower = more frequent checks
config.setPollInterval(200);

// Disable temporarily
config.setEnabled(false);

// Set action data (for SEND_KEYS)
config.setActionData("someText");
```

## 🔄 How Background Monitoring Works

1. **Thread Creation**: Single daemon thread created when first listener added
2. **Polling Loop**: Runs every 100ms checking all registered listeners
3. **Element Detection**: Uses driver.findElement() with registered locators
4. **Action Execution**: When element found and displayed, executes recovery action
5. **Error Handling**: Exceptions caught and logged, monitoring continues
6. **Shutdown**: Executor service shutdown in `shutdown()` method

## 📝 Best Practices

### ✅ DO
- Register listeners in `@BeforeEach` setup
- Shutdown manager in `@AfterEach`
- Use XPath for complex element matching
- Handle `InterruptedException` in custom listeners
- Log recovery actions for debugging

### ❌ DON'T
- Register/remove listeners during test execution (use disable instead)
- Create multiple manager instances (use single shared manager)
- Block in custom listeners (they run in background thread)
- Forget to shutdown (can leave thread hanging)

## 🐛 Troubleshooting

### Listener Not Triggered
- Verify locator is correct (test with `driver.findElement()` first)
- Check if element is actually displayed (isDisplayed() must be true)
- Verify listener is enabled

### Performance Issues
- Reduce number of listeners if possible
- Increase poll interval if immediate detection not needed
- Use specific XPath instead of broad wildcards

### Thread Still Running After Test
- Ensure `shutdown()` is called in `@AfterEach`
- Use try-finally to guarantee cleanup:
```java
@AfterEach
public void tearDown() {
    try {
        // cleanup
    } finally {
        if (listenerManager != null) {
            listenerManager.shutdown();
        }
        driver.quit();
    }
}
```

## 📚 Files Included

- `MobileListener.java` - Interface for custom recovery
- `ListenerAction.java` - Enum of predefined actions
- `ListenerConfig.java` - Configuration holder
- `AppiumMobileListenerManager.java` - Main manager class
- `LocalAndroidTest.java` - Example integration

## 🔗 Related Documentation

- [Appium Documentation](https://appium.io/)
- [Selenium Java Documentation](https://www.selenium.dev/selenium/docs/api/java/)
- [SeeTest Mobile Listeners](https://docs.digital.ai/continuous-testing/docs/sta/seetest-automation-home/seetestautomation-how-to/seetestautomation-working-from-code-environment/seetestautomation-java-clients/seetestautomation-java-junit4/seetestautomation-using-mobile-listeners-in-java)

---

**Key Advantage**: No more cluttering your test logic with if-else conditions or explicit waits for common UI interrupts. Just register them once and focus on testing!
