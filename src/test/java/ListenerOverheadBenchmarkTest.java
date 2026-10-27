import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.AndroidElement;
import org.junit.jupiter.api.*;
import org.openqa.selenium.By;
import org.openqa.selenium.ScreenOrientation;
import org.openqa.selenium.remote.DesiredCapabilities;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * A/B benchmark for the cost of PRE_COMMAND listener interception.
 *
 * Exactly the same command sequence as LocalDeviceAndroidTest runs in both modes; the only
 * difference is whether the driver is built on the intercepting executor. No listener is
 * ever triggered, so this measures pure overhead.
 *
 *   mvn test -Dtest=ListenerOverheadBenchmarkTest -Dlisteners=off
 *   mvn test -Dtest=ListenerOverheadBenchmarkTest -Dlisteners=on
 *
 * The 10s Thread.sleep from the original test is kept but excluded from the reported
 * figure, since it is a constant in both modes and would only dilute the comparison.
 */
public class ListenerOverheadBenchmarkTest {

    private static final String APPIUM_URL = System.getProperty("appium.url", "http://127.0.0.1:4723/");
    private static final String DEVICE_UDID = System.getProperty("appium.udid", "RZ8N60CB43F");
    private static final boolean LISTENERS_ON = !"off".equalsIgnoreCase(System.getProperty("listeners", "on"));

    private AndroidDriver<AndroidElement> driver;
    private InterceptingListenerRegistry listenerManager;
    private ListenerCommandExecutor executor;

    @BeforeEach
    public void setUp() throws MalformedURLException {
        DesiredCapabilities dc = new DesiredCapabilities();
        dc.setCapability("platformName", "Android");
        dc.setCapability("appium:automationName", "UiAutomator2");
        dc.setCapability("appium:udid", DEVICE_UDID);
        dc.setCapability("appium:appPackage", "com.experitest.ExperiBank");
        dc.setCapability("appium:appActivity", ".LoginActivity");
        dc.setCapability("appium:noReset", true);
        dc.setCapability("appium:newCommandTimeout", 300);

        if (LISTENERS_ON) {
            // Same listener as LocalDeviceAndroidTest. It is never expected to match here.
            listenerManager = new InterceptingListenerRegistry()
                    .setMode(InterceptingListenerRegistry.Mode.PRE_COMMAND)
                    .setVerbose(true);
            listenerManager.addMobileListener("//*[@text='Clock']", locator -> {
                System.out.println("!! listener fired during the benchmark; this run is not clean");
                driver.activateApp("com.experitest.ExperiBank");
                return true;
            });
            executor = new ListenerCommandExecutor(new URL(APPIUM_URL), listenerManager);
            driver = new AndroidDriver<>(executor, dc);
            listenerManager.setDriver(driver);
        } else {
            driver = new AndroidDriver<>(new URL(APPIUM_URL), dc);
        }
    }

    @Test
    public void timeTheSameCommandSequence() throws InterruptedException {
        long t0 = System.currentTimeMillis();
        driver.rotate(ScreenOrientation.PORTRAIT);
        driver.findElement(By.id("com.experitest.ExperiBank:id/usernameTextField")).sendKeys("company");
        driver.findElement(By.id("com.experitest.ExperiBank:id/passwordTextField")).sendKeys("company");
        driver.findElement(By.id("com.experitest.ExperiBank:id/loginButton")).click();
        long beforeSleep = System.currentTimeMillis() - t0;

        Thread.sleep(10000); // kept for fidelity with the original test, excluded from the total

        long t1 = System.currentTimeMillis();
        driver.findElement(By.id("com.experitest.ExperiBank:id/logoutButton")).click();
        long afterSleep = System.currentTimeMillis() - t1;

        System.out.println("[bench] listeners=" + (LISTENERS_ON ? "ON " : "OFF")
                + " commandTimeExcludingSleep=" + (beforeSleep + afterSleep) + "ms"
                + " (pre-sleep=" + beforeSleep + "ms, post-sleep=" + afterSleep + "ms)");
        if (LISTENERS_ON) {
            System.out.println("[bench] " + executor.stats());
        }
    }

    @AfterEach
    public void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }
}
