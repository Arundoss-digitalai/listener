import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebElement;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;

/**
 * Manages mobile listeners for Appium AndroidDriver
 * Monitors for registered elements and handles them automatically
 */
public class AppiumMobileListenerManager {
    
    private AndroidDriver driver;
    private List<ListenerConfig> listeners;
    private ExecutorService executorService;
    private AtomicBoolean monitoring;
    private CountDownLatch firstPollComplete; // Wait for first listener poll to complete
    private AtomicBoolean listenerRecoveryInProgress; // Track if listeners are actively recovering
    
    public AppiumMobileListenerManager(AndroidDriver driver) {
        this.driver = driver;
        this.listeners = new CopyOnWriteArrayList<>();
        this.executorService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AppiumMobileListenerMonitor");
            t.setDaemon(true);
            return t;
        });
        this.monitoring = new AtomicBoolean(false);
        this.firstPollComplete = null;
        this.listenerRecoveryInProgress = new AtomicBoolean(false);
    }
    
    /**
     * Register a listener with predefined action
     */
    public void addMobileListener(String locatorString, ListenerAction action) {
        By locator = parseLocator(locatorString);
        ListenerConfig config = new ListenerConfig(locator, action);
        listeners.add(config);
    }
    
    /**
     * Register a listener with custom callback
     */
    public void addMobileListener(String locatorString, MobileListener listener) {
        By locator = parseLocator(locatorString);
        ListenerConfig config = new ListenerConfig(locator, listener);
        listeners.add(config);
    }
    
    /**
     * Register a listener with By locator
     */
    public void addMobileListener(By locator, MobileListener listener) {
        ListenerConfig config = new ListenerConfig(locator, listener);
        listeners.add(config);
    }
    
    /**
     * Start background monitoring of all registered listeners
     * MUST be called after all listeners are registered
     * Blocks until first complete poll of all listeners is done
     */
    public void startMonitoring() {
        ensureMonitoring();
    }
    
    /**
     * Remove a listener
     */
    public void removeMobileListener(String locatorString) {
        By locator = parseLocator(locatorString);
        listeners.removeIf(l -> l.getLocator().toString().equals(locator.toString()));
    }
    
    /**
     * Disable all listeners
     */
    public void disableAllListeners() {
        listeners.forEach(l -> l.setEnabled(false));
    }
    
    /**
     * Enable all listeners
     */
    public void enableAllListeners() {
        listeners.forEach(l -> l.setEnabled(true));
    }
    
    /**
     * Clear all listeners
     */
    public void clearListeners() {
        listeners.clear();
        stopMonitoring();
    }
    
    /**
     * Start monitoring thread if not already running
     * Blocks until first listener poll is complete to ensure listeners are active before test starts
     */
    private synchronized void ensureMonitoring() {
        if (!monitoring.get() && !listeners.isEmpty()) {
            monitoring.set(true);
            firstPollComplete = new CountDownLatch(1);
            executorService.submit(this::monitoringLoop);
            
            // Wait for first poll to complete (ensures listeners are ready before test starts)
            try {
                firstPollComplete.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Main monitoring loop - runs in background thread
     */
    private void monitoringLoop() {
        try {
            // First poll - blocks setUp() until this completes
            checkAndHandleListeners();
            if (firstPollComplete != null) {
                firstPollComplete.countDown();
            }
            
            // Continue monitoring in background
            while (monitoring.get() && !listeners.isEmpty()) {
                try {
                    Thread.sleep(50); // Check every 50ms - increased frequency to catch crashes faster
                    checkAndHandleListeners();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("Error in listener monitoring: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Error in listener monitoring: " + e.getMessage());
        }
        monitoring.set(false);
    }
    
    /**
     * Check each listener and handle if element is found
     */
    private void checkAndHandleListeners() {
        for (ListenerConfig config : listeners) {
            if (!config.isEnabled()) {
                continue;
            }
            
            try {
                WebElement element = driver.findElement(config.getLocator());
                if (element != null && element.isDisplayed()) {
                    handleListener(config, element);
                }
            } catch (NoSuchElementException e) {
                // Element not found, continue
            } catch (Exception e) {
                System.err.println("Error checking listener: " + e.getMessage());
            }
        }
    }
    
    /**
     * Execute recovery action for detected element
     */
    private void handleListener(ListenerConfig config, WebElement element) {
        listenerRecoveryInProgress.set(true);
        try {
            boolean recovered = false;
            
            if (config.getAction() == ListenerAction.CUSTOM) {
                recovered = config.getCustomListener().recover(config.getLocator().toString());
            } else {
                recovered = executeAction(config, element);
            }
            
            if (recovered) {
                System.out.println("✓ Listener handled: " + config.getLocator());
            }
        } catch (Exception e) {
            System.err.println("Error handling listener: " + e.getMessage());
        } finally {
            listenerRecoveryInProgress.set(false);
        }
    }
    
    /**
     * Execute predefined action
     */
    private boolean executeAction(ListenerConfig config, WebElement element) {
        try {
            switch (config.getAction()) {
                case CLICK:
                    element.click();
                    return true;
                    
                case DISMISS:
                    try {
                        element.click();
                    } catch (Exception e) {
                        // If click fails, try clicking parent or just log
                        System.err.println("Could not dismiss element via click: " + e.getMessage());
                    }
                    return true;
                    
                case SEND_KEYS:
                    if (config.getActionData() != null) {
                        element.sendKeys(config.getActionData());
                        return true;
                    }
                    return false;
                    
                case WAIT_AND_DISMISS:
                    Thread.sleep(500);
                    element.click();
                    return true;
                    
                default:
                    return false;
            }
        } catch (Exception e) {
            System.err.println("Error executing action: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Stop monitoring
     */
    public void stopMonitoring() {
        monitoring.set(false);
    }
    
    /**
     * Wait for listeners to settle and complete any ongoing recovery
     * Call this after critical operations (like rotate) to ensure listeners have handled any issues
     * @param timeoutMs Maximum time to wait in milliseconds
     * @throws InterruptedException if interrupted while waiting
     */
    public void waitForListenersToSettle(long timeoutMs) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        
        // Wait for any ongoing listener recovery to complete
        while (listenerRecoveryInProgress.get()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                System.err.println("⚠️  Timeout waiting for listener recovery after " + timeoutMs + "ms");
                break;
            }
            Thread.sleep(10);
        }
        
        // Do one more immediate poll to catch any issues that just appeared
        checkAndHandleListeners();
        
        // Small delay to ensure recovery starts if needed
        Thread.sleep(50);
        
        // Wait one more time if recovery was triggered
        startTime = System.currentTimeMillis();
        while (listenerRecoveryInProgress.get()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                System.err.println("⚠️  Timeout waiting for listener recovery after " + timeoutMs + "ms");
                break;
            }
            Thread.sleep(10);
        }
    }
    
    /**
     * Parse locator string (supports id:xxx, xpath=xxx, etc.)
     */
    private By parseLocator(String locator) {
        if (locator.startsWith("id:") || locator.startsWith("id=")) {
            return By.id(locator.substring(3));
        } else if (locator.startsWith("xpath=")) {
            return By.xpath(locator.substring(6));
        } else if (locator.startsWith("xpath://")) {
            return By.xpath(locator.substring(6));
        } else if (locator.startsWith("class=")) {
            return By.className(locator.substring(6));
        } else if (locator.startsWith("text=")) {
            return By.xpath("//*[@text='" + locator.substring(5) + "']");
        } else if (locator.startsWith("accessibilityId=")) {
            return By.id(locator.substring(16));
        }
        return By.xpath(locator);
    }
    
    /**
     * Shutdown manager
     */
    public void shutdown() {
        stopMonitoring();
        executorService.shutdown();
    }
}
