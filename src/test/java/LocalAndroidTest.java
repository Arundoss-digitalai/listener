import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.AndroidElement;
import io.appium.java_client.remote.AndroidMobileCapabilityType;
import io.appium.java_client.remote.MobileCapabilityType;
import org.junit.jupiter.api.*;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.ScreenOrientation;
import org.openqa.selenium.remote.DesiredCapabilities;

import java.net.MalformedURLException;
import java.net.URL;

public class LocalAndroidTest {

    protected AndroidDriver<AndroidElement> driver = null;
    DesiredCapabilities dc = new DesiredCapabilities();
    private String accessKey = "";
    private AppiumMobileListenerManager listenerManager;

    @BeforeEach
    public void setUp() throws MalformedURLException {
        dc.setCapability("digitalai:testName", "Quick Start Android Native Demo");
        dc.setCapability("digitalai:accessKey", accessKey);
        dc.setCapability("digitalai:deviceQuery", "@os='android' and @category='PHONE'");
        dc.setCapability(MobileCapabilityType.APP, "cloud:com.experitest.ExperiBank/.LoginActivity");
        dc.setCapability(AndroidMobileCapabilityType.APP_PACKAGE, "com.experitest.ExperiBank");
        dc.setCapability(AndroidMobileCapabilityType.APP_ACTIVITY, ".LoginActivity");
        dc.setCapability("digitalai:appiumVersion", "3.5.2");
        driver = new AndroidDriver<>(new URL("https://uscloud.experitest.com/wd/hub"), dc);

        
        // Initialize mobile listener manager
        listenerManager = new AppiumMobileListenerManager(driver);
        
        // Register listeners for common UI interrupts
        // Example 1: Handle loading spinner/progress bar using WAIT_AND_DISMISS
        listenerManager.addMobileListener(
            "xpath=//android.widget.ProgressBar",
            ListenerAction.WAIT_AND_DISMISS
        );
        
        // Example 2: Handle dialogs using CLICK action
        listenerManager.addMobileListener(
            "id=android:id/alertTitle",
            ListenerAction.CLICK
        );
        
        // Example 3: Custom listener for loader with callback logic
        listenerManager.addMobileListener(
            "xpath=//*[contains(@resource-id, 'progressBar') or contains(@class, 'ProgressBar')]",
            new MobileListener() {
                @Override
                public boolean recover(String locator) {
                    System.out.println("🔄 Loading indicator detected, waiting for it to disappear...");
                    try {
                        // Wait up to 60 seconds for the loader to disappear
                        int maxAttempts = 60;
                        for (int i = 0; i < maxAttempts; i++) {
                            try {
                                driver.findElement(By.xpath("//*[contains(@resource-id, 'progressBar') or contains(@class, 'ProgressBar')]"));
                                Thread.sleep(1000);
                            } catch (NoSuchElementException e) {
                                System.out.println("✓ Loader disappeared after " + ((i + 1) * 1000) + "ms");
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
        
        // Example 4: Handle Play Store detection and re-launch app
        listenerManager.addMobileListener(
            "//*[@text='Play Store']",
            new MobileListener() {
                @Override
                public boolean recover(String locator) {
                    System.out.println("⚠️  Play Store detected! App may have crashed or navigated away.");
                    try {
                        System.out.println("🔄 Attempting to re-launch the app...");
                        // Small delay before reactivating
                        Thread.sleep(500);
                        
                        // Activate/launch the app (no reinstall, preserves state)
                        driver.activateApp("com.experitest.ExperiBank");
                        
                        // Wait for app to fully load
                        Thread.sleep(2000);
                        
                        System.out.println("✓ App re-launched successfully!");
                        return true;
                    } catch (Exception e) {
                        System.err.println("❌ Failed to re-launch app: " + e.getMessage());
                        return false;
                    }
                }
            }
        );
        
        // Start monitoring - blocks until all 4 listeners are checked once
        listenerManager.startMonitoring();
    }

    @Test
    public void quickStartAndroidNativeDemo() throws InterruptedException {
        driver.rotate(ScreenOrientation.PORTRAIT);
        
        // Wait for listeners to detect and handle any issues from rotate (crash-prone operation)
        listenerManager.waitForListenersToSettle(5000);
        
        driver.findElement(By.id("com.experitest.ExperiBank:id/usernameTextField")).sendKeys("company");

        listenerManager.waitForListenersToSettle(5000);
        driver.findElement(By.id("com.experitest.ExperiBank:id/passwordTextField")).sendKeys("company");
        driver.findElement(By.id("com.experitest.ExperiBank:id/loginButton")).click();
        // driver.findElement(By.id("com.experitest.ExperiBank:id/makePaymentButton")).click();
        // driver.findElement(By.id("com.experitest.ExperiBank:id/phoneTextField")).sendKeys("0501234567");
        // driver.findElement(By.id("com.experitest.ExperiBank:id/nameTextField")).sendKeys("John Snow");
        // driver.findElement(By.id("com.experitest.ExperiBank:id/amountTextField")).sendKeys("50");
        // driver.findElement(By.id("com.experitest.ExperiBank:id/countryTextField")).sendKeys("'Switzerland'");
        // driver.findElement(By.id("com.experitest.ExperiBank:id/sendPaymentButton")).click();
        //add sleep
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        driver.findElement(By.id("com.experitest.ExperiBank:id/logoutButton")).click();
    }

    @AfterEach
    public void tearDown() {
        // Shutdown listener manager
        if (listenerManager != null) {
            listenerManager.shutdown();
        }
        System.out.println("Report URL: " + driver.getCapabilities().getCapability("digitalai:reportUrl"));
        driver.quit();
    }
}