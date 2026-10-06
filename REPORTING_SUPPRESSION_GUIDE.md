# Suppressing Listener Actions from Reports

## Problem Solved

When mobile listeners execute recovery actions (click dialogs, wait for loaders, re-launch apps), those commands were being reported to Digital.ai, cluttering the test report and making the main functional flow hard to trace.

## Solution: Automatic Reporting Suppression

The updated `AppiumMobileListenerManager` now automatically suppresses all listener actions from being reported to Digital.ai.

### How It Works

```
┌─────────────────────────────────────────────────────────┐
│               TEST EXECUTION FLOW                       │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  1. Main test: driver.click(loginBtn)  ← REPORTED ✓   │
│                                                         │
│  2. Listener detects loader...                         │
│     suppressReporting() ← Turns OFF reporting           │
│     executeAction(WAIT_AND_DISMISS) ← NOT reported    │
│     enableReporting() ← Turns ON reporting             │
│                                                         │
│  3. Main test: driver.click(nextBtn)  ← REPORTED ✓    │
│                                                         │
│  Result: Report shows only main flow, NOT listener    │
│          actions → CLEAN & TRACEABLE                   │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### Implementation Details

```java
private void handleListener(ListenerConfig config, WebElement element) {
    try {
        boolean recovered = false;
      
        suppressReporting();  // ← Turn off Digital.ai reporting
      
        try {
            if (config.getAction() == ListenerAction.CUSTOM) {
                recovered = config.getCustomListener().recover(...);
            } else {
                recovered = executeAction(config, element);
            }
        } finally {
            enableReporting();  // ← Turn reporting back on
        }
      
        if (recovered) {
            System.out.println("✓ Listener handled: " + config.getLocator());
        }
    } catch (Exception e) {
        System.err.println("Error handling listener: " + e.getMessage());
    }
}

private void suppressReporting() {
    driver.executeScript("digitalai:suppressReport", true);
}

private void enableReporting() {
    driver.executeScript("digitalai:suppressReport", false);
}
```

## Report Output Comparison

### BEFORE (Cluttered with Listener Commands)

```
Test Report: LoginTest
├─ login with credentials
│  └─ Click username field ...................... ✓
│  └─ Send keys "user@example.com" .............. ✓
│  └─ Click password field ...................... ✓
│  └─ Send keys "password123" ................... ✓
│  └─ Click login button ........................ ✓
│  └─ Find element: xpath=//ProgressBar ........ ✓    ← LISTENER
│  └─ Wait 500ms ................................ ✓    ← LISTENER
│  └─ Click ProgressBar ......................... ✓    ← LISTENER
│  └─ Activate app: com.experitest.ExperiBank . ✓    ← LISTENER
│  └─ Wait 2000ms ............................... ✓    ← LISTENER
│  └─ Find element: //*[@text='Play Store'] ... ✓    ← LISTENER
│  └─ Activate app: com.experitest.ExperiBank . ✓    ← LISTENER
│  └─ Wait 2000ms ............................... ✓    ← LISTENER
├─ verify dashboard
│  └─ Find element: id=dashboard ............... ✓
│  └─ Assert displayed .......................... ✓
└─ PASSED

❌ Hard to trace main flow - too much noise!
```

### AFTER (Clean Functional Flow)

```
Test Report: LoginTest
├─ login with credentials
│  └─ Click username field ...................... ✓
│  └─ Send keys "user@example.com" .............. ✓
│  └─ Click password field ...................... ✓
│  └─ Send keys "password123" ................... ✓
│  └─ Click login button ........................ ✓
│  [Listener handled Play Store interrupt] .... [Internal]
│  [Listener handled loader interrupt] ........ [Internal]
├─ verify dashboard
│  └─ Find element: id=dashboard ............... ✓
│  └─ Assert displayed .......................... ✓
└─ PASSED

✅ Main flow is clear and traceable!
```

## Digital.ai Capabilities Used

The suppression works via Digital.ai's reporting control capabilities:

```java
// Suppress reporting
driver.executeScript("digitalai:suppressReport", true);

// Re-enable reporting
driver.executeScript("digitalai:suppressReport", false);
```

These commands tell the Digital.ai/SeeTest Automation server to exclude subsequent commands from the test report until reporting is re-enabled.

## Alternative Approaches (If Default Doesn't Work)

### Option 1: Use Digital.ai Report Groups (Collapsible Sections)

If `digitalai:suppressReport` doesn't work, you can group listener actions in a collapsible section:

```java
private void handleListener(ListenerConfig config, WebElement element) {
    try {
        // Start a collapsible report section
        driver.executeScript("digitalai:reportStartSection", 
            "Internal Recovery: " + config.getLocator());
      
        boolean recovered = executeAction(config, element);
      
        // End the section
        driver.executeScript("digitalai:reportEndSection");
      
        if (recovered) {
            System.out.println("✓ Listener handled");
        }
    } catch (Exception e) {
        System.err.println("Error: " + e.getMessage());
    }
}
```

### Option 2: Silent/Non-Reporting Driver Wrapper

Create a wrapper that executes commands without reporting:

```java
public class SilentAndroidDriver {
    private AndroidDriver driver;
  
    public SilentAndroidDriver(AndroidDriver driver) {
        this.driver = driver;
    }
  
    public void suppressedClick(By locator) {
        try {
            driver.executeScript("digitalai:suppressReport", true);
            driver.findElement(locator).click();
            driver.executeScript("digitalai:suppressReport", false);
        } catch (Exception e) {
            driver.executeScript("digitalai:suppressReport", false);
            throw e;
        }
    }
  
    public void suppressedActivateApp(String packageName) {
        try {
            driver.executeScript("digitalai:suppressReport", true);
            driver.activateApp(packageName);
            driver.executeScript("digitalai:suppressReport", false);
        } catch (Exception e) {
            driver.executeScript("digitalai:suppressReport", false);
            throw e;
        }
    }
}
```

Then use:

```java
private SilentAndroidDriver silentDriver;

public AppiumMobileListenerManager(AndroidDriver driver) {
    this.driver = driver;
    this.silentDriver = new SilentAndroidDriver(driver);
    // ...
}

private void executeAction(ListenerConfig config, WebElement element) {
    switch (config.getAction()) {
        case CLICK:
            silentDriver.suppressedClick(config.getLocator());
            return true;
        // ...
    }
}
```

### Option 3: Mark Actions with Custom Comments

If suppression isn't possible, at least identify listener actions clearly:

```java
private void handleListener(ListenerConfig config, WebElement element) {
    try {
        driver.executeScript("digitalai:report", 
            "[INTERNAL-LISTENER] Handling: " + config.getLocator());
      
        boolean recovered = executeAction(config, element);
      
        if (recovered) {
            driver.executeScript("digitalai:report", 
                "[INTERNAL-LISTENER] Successfully handled");
        }
    } catch (Exception e) {
        System.err.println("Error: " + e.getMessage());
    }
}
```

Reports will show:

```
├─ [INTERNAL-LISTENER] Handling: //*[@text='Play Store']
├─ [INTERNAL-LISTENER] Successfully handled
```

## Configuration

### Enable/Disable Reporting Suppression

Add a flag to control suppression behavior:

```java
public class AppiumMobileListenerManager {
    private boolean suppressReporting = true;  // Default: enabled
  
    public void setSuppressReporting(boolean suppress) {
        this.suppressReporting = suppress;
    }
  
    private void handleListener(ListenerConfig config, WebElement element) {
        if (suppressReporting) {
            suppressReporting();
        }
      
        try {
            // execute action
        } finally {
            if (suppressReporting) {
                enableReporting();
            }
        }
    }
}
```

Usage:

```java
// In test setup
listenerManager.setSuppressReporting(true);   // Suppress (default)
listenerManager.setSuppressReporting(false);  // Show all actions
```

## What Gets Suppressed

✅ Suppressed (Internal Listener Actions):

- `element.click()` in listener
- `driver.activateApp()`
- `driver.findElement()` checks
- `Thread.sleep()` waits
- Any custom recovery logic

❌ NOT Suppressed (Main Test Actions):

- User clicks in test method
- User input in test method
- Assertions in test method
- Main test flow steps

## Verification

After running a test, check the Digital.ai report:

1. **Login to Digital.ai Portal**
2. **View Test Report**
3. **Verify Main Flow Section** shows only functional steps
4. **Verify No Listener Commands** in main report flow

Example clean report section:

```
Test Steps (Functional Flow):
1. Navigate to app ........................... ✓
2. Enter credentials ......................... ✓
3. Click login .............................. ✓
4. Verify dashboard displayed ............... ✓

Internal Listener Actions: (Suppressed from main flow)
- Handled Play Store popup
- Handled loading indicator
- Re-launched app
```

## Best Practices

✅ DO

- Use reporting suppression for all listener actions
- Enable/disable suppression globally in setUp()
- Keep listener actions truly internal
- Log summary of what was recovered (shown in console, not report)

❌ DON'T

- Mix listener commands with test assertions
- Report listener actions as test steps
- Suppress reporting for actual test failures (need those visible)
- Forget to re-enable reporting (the finally block handles this)

## Troubleshooting

### Reports Still Show Listener Commands

1. **Check Digital.ai Version**: Some older versions don't support `digitalai:suppressReport`
2. **Try Alternative Approaches**: Use report sections or custom comments
3. **Enable Debug Mode**: Set `digitalai:debugReporting: true` to see what's being reported
4. **Check Logs**: Look at console output for suppression warnings

### Suppression Not Working on Custom Listeners

Ensure your custom MobileListener also respects suppression:

```java
@Override
public boolean recover(String locator) {
    // The manager already suppressed reporting
    // Just do your recovery logic
    driver.activateApp("com.experitest.ExperiBank");
    Thread.sleep(2000);
    return true;
    // Reporting will be re-enabled by manager
}
```

## Related Documentation

- [Digital.ai/SeeTest Automation Reporting API](https://docs.digital.ai/continuous-testing/)
- [Appium executeScript Documentation](https://appium.io/docs/en/writing-running-appium/appium-bindings/)
- [Mobile Listener Framework Guide](MOBILE_LISTENER_GUIDE.md)
