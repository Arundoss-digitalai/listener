import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.AndroidElement;
import org.junit.jupiter.api.*;
import org.openqa.selenium.By;
import org.openqa.selenium.ScreenOrientation;
import org.openqa.selenium.remote.DesiredCapabilities;

import java.net.MalformedURLException;
import java.net.URL;


public class LocalDeviceAndroidTest {

    // Appium 3 serves the WebDriver protocol at the root path (no /wd/hub).
    private static final String APPIUM_URL = System.getProperty("appium.url", "http://127.0.0.1:4723/");
    private static final String DEVICE_UDID = System.getProperty("appium.udid", "RZ8N60CB43F");

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

        listenerManager.addMobileListener(
            "//*[@text='Clock']",
            new MobileListener() {
                @Override
                public boolean recover(String locator) {
                    System.out.println("⚠️  Clock detected! App may have crashed or navigated away.");
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

        // Capabilities are written with the explicit "appium:" prefix because Appium 2+
        // rejects unprefixed, non-standard capabilities.
        dc.setCapability("platformName", "Android");
        dc.setCapability("appium:automationName", "UiAutomator2");
        dc.setCapability("appium:udid", DEVICE_UDID);
        dc.setCapability("appium:appPackage", "com.experitest.ExperiBank");
        dc.setCapability("appium:appActivity", ".LoginActivity");
        dc.setCapability("appium:noReset", true);
        dc.setCapability("appium:newCommandTimeout", 300);

        // The driver is built on the intercepting executor instead of on the URL directly.
        // That is what puts the listener check in front of every command below.
        executor = new ListenerCommandExecutor(new URL(APPIUM_URL), listenerManager);
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
            System.out.println("[listener] " + executor.stats());
            System.out.println("Report URL:" + driver.getCapabilities().getCapability("reportUrl"));
            driver.quit();
    }
       
}
