import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.AndroidElement;
import io.appium.java_client.remote.AndroidMobileCapabilityType;
import io.appium.java_client.remote.MobileCapabilityType;
import org.junit.jupiter.api.*;
import org.openqa.selenium.By;
import org.openqa.selenium.ScreenOrientation;
import org.openqa.selenium.remote.DesiredCapabilities;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * The same listener mechanism as {@link LocalDeviceAndroidTest}, but running against a
 * device in the Digital.ai cloud instead of a local device.
 *
 * The listener wiring is identical: the Digital.ai grid exposes no server-side listener for
 * Appium sessions, so interception happens client side in {@link ListenerCommandExecutor}
 * exactly as it does for a local Appium server. Only the URL and the capabilities differ.
 *
 *   mvn test -Dtest=LocalAndroidTest -Ddigitalai.accessKey=<your key>
 *
 * Note: every intercepted command issues an extra getPageSource, and those calls appear in
 * the Digital.ai report alongside your functional steps. Suppressing them would need the
 * grid's own reporting controls, which are not wired up here.
 */
public class LocalAndroidTest {

    private static final String CLOUD_URL =
            System.getProperty("digitalai.url", "https://uscloud.experitest.com/wd/hub");
    // Keep the access key out of the repo: pass it with -Ddigitalai.accessKey=...
    private static final String ACCESS_KEY = System.getProperty("digitalai.accessKey", "");

    protected AndroidDriver<AndroidElement> driver = null;
    DesiredCapabilities dc = new DesiredCapabilities();
    private InterceptingListenerRegistry listenerManager;
    private ListenerCommandExecutor executor;

    @BeforeEach
    public void setUp() throws MalformedURLException {
        // The registry has to exist before the driver, because the executor the driver is
        // built on consults it. Listeners are registered here, with no driver in hand yet.
        listenerManager = new InterceptingListenerRegistry()
                .setMode(InterceptingListenerRegistry.Mode.PRE_COMMAND)
                .setVerbose(true);

        // Cloud equivalent of the local test's Clock listener: if the launcher is showing,
        // the app crashed or was navigated away from, so bring it back.
        listenerManager.addMobileListener(
            "//*[@text='Play Store']",
            new MobileListener() {
                @Override
                public boolean recover(String locator) {
                    System.out.println("⚠️  Play Store detected! App may have crashed or navigated away.");
                    try {
                        System.out.println("🔄 Attempting to re-launch the app...");
                        Thread.sleep(500);

                        // Activate/launch the app (no reinstall, preserves state)
                        driver.activateApp("com.experitest.ExperiBank");

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

        dc.setCapability("digitalai:testName", "Quick Start Android Native Demo");
        dc.setCapability("digitalai:accessKey", ACCESS_KEY);
        dc.setCapability("digitalai:deviceQuery", "@os='android' and @category='PHONE'");
        dc.setCapability(MobileCapabilityType.APP, "cloud:com.experitest.ExperiBank/.LoginActivity");
        dc.setCapability(AndroidMobileCapabilityType.APP_PACKAGE, "com.experitest.ExperiBank");
        dc.setCapability(AndroidMobileCapabilityType.APP_ACTIVITY, ".LoginActivity");
        dc.setCapability("digitalai:appiumVersion", "3.5.2");

        // The driver is built on the intercepting executor instead of on the URL directly.
        // That is what puts the listener check in front of every command below.
        executor = new ListenerCommandExecutor(new URL(CLOUD_URL), listenerManager);
        driver = new AndroidDriver<>(executor, dc);

        // Close the loop: recovery callbacks need a driver to act on.
        listenerManager.setDriver(driver);
    }

    @Test
    public void quickStartAndroidNativeDemo() throws InterruptedException {
        driver.rotate(ScreenOrientation.PORTRAIT);
        driver.findElement(By.id("com.experitest.ExperiBank:id/usernameTextField")).sendKeys("company");
        driver.findElement(By.id("com.experitest.ExperiBank:id/passwordTextField")).sendKeys("company");
        driver.findElement(By.id("com.experitest.ExperiBank:id/loginButton")).click();
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        driver.findElement(By.id("com.experitest.ExperiBank:id/logoutButton")).click();
    }

    @AfterEach
    public void tearDown() {
        if (executor != null) {
            System.out.println("[listener] " + executor.stats());
        }
        if (driver != null) {
            System.out.println("Report URL: " + driver.getCapabilities().getCapability("digitalai:reportUrl"));
            driver.quit();
        }
    }
}
