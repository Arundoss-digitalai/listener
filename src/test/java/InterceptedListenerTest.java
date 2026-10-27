import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.nativekey.AndroidKey;
import io.appium.java_client.android.nativekey.KeyEvent;
import io.appium.java_client.android.AndroidElement;
import org.junit.jupiter.api.*;
import org.openqa.selenium.By;
import org.openqa.selenium.remote.DesiredCapabilities;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Proof of concept: SeeTest-style listeners on a plain Appium server, with no server-side
 * support, by intercepting every command in a custom CommandExecutor.
 *
 * Run against a local Appium server and an attached device:
 *   mvn test -Dtest=InterceptedListenerTest
 */
public class InterceptedListenerTest {

    private static final String APPIUM_URL = System.getProperty("appium.url", "http://127.0.0.1:4723/");
    private static final String DEVICE_UDID = System.getProperty("appium.udid", "RZ8N60CB43F");

    private AndroidDriver<AndroidElement> driver;
    private InterceptingListenerRegistry listeners;
    private ListenerCommandExecutor executor;

    @BeforeEach
    public void setUp() throws MalformedURLException {
        // 1. Register the listeners BEFORE the driver exists. The registry is what the
        //    executor consults, so it has to be constructed first.
        listeners = new InterceptingListenerRegistry()
                .setMode(InterceptingListenerRegistry.Mode.PRE_COMMAND)
                .setVerbose(true);

        listeners.addMobileListener("xpath=//android.widget.ProgressBar", ListenerAction.WAIT_AND_DISMISS);
        listeners.addMobileListener("id=android:id/alertTitle", ListenerAction.CLICK);
        // A genuine, reproducible interrupt: the device screen is locked. The listener
        // detects the keyguard in the dump and unlocks before the real command runs.
        listeners.addMobileListener("id=com.android.systemui:id/lock_icon_view", locator -> {
            System.out.println("[test] lock screen detected, unlocking the device");
            driver.pressKey(new KeyEvent(AndroidKey.WAKEUP));
            driver.unlockDevice();
            driver.activateApp("com.experitest.ExperiBank");
            return true;
        });

        listeners.addMobileListener("text=Play Store", locator -> {
            System.out.println("[test] Play Store detected, re-launching the app");
            driver.activateApp("com.experitest.ExperiBank");
            return true;
        });

        // 2. Build the driver on top of the intercepting executor.
        executor = new ListenerCommandExecutor(new URL(APPIUM_URL), listeners);

        DesiredCapabilities dc = new DesiredCapabilities();
        dc.setCapability("platformName", "Android");
        dc.setCapability("appium:automationName", "UiAutomator2");
        dc.setCapability("appium:udid", DEVICE_UDID);
        dc.setCapability("appium:appPackage", "com.experitest.ExperiBank");
        dc.setCapability("appium:appActivity", ".LoginActivity");
        dc.setCapability("appium:noReset", true);
        dc.setCapability("appium:newCommandTimeout", 300);
        driver = new AndroidDriver<>(executor, dc);

        // 3. Hand the driver back to the registry so built-in recovery actions can drive it.
        listeners.setDriver(driver);

        // Start every run from the login screen. noReset keeps app state between runs, so a
        // previous run can leave the app logged in. Listeners are off for this bit of
        // housekeeping so it does not pollute the measurements below.
        listeners.setAllEnabled(false);
        driver.unlockDevice();
        driver.terminateApp("com.experitest.ExperiBank");
        driver.activateApp("com.experitest.ExperiBank");
        listeners.setAllEnabled(true);

        // Explicit wait for both tests. Without it the baseline is unfair: the listener run
        // gets an accidental ~430ms grace period from its dump round trip after each command.
        driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS);
    }

    @Test
    public void listenersRunBeforeEveryCommand() {
        // No waitForListenersToSettle anywhere: every one of these commands is preceded by a
        // listener check inside the executor.
        long t0 = System.currentTimeMillis();
        driver.findElement(By.id("com.experitest.ExperiBank:id/usernameTextField")).sendKeys("company");
        driver.findElement(By.id("com.experitest.ExperiBank:id/passwordTextField")).sendKeys("company");
        driver.findElement(By.id("com.experitest.ExperiBank:id/loginButton")).click();

        // Inject a real interrupt mid-test: lock the device. The very next command's
        // pre-command check must spot the keyguard in the dump and recover before running.
        System.out.println("[test] locking the device to simulate an unexpected interrupt");
        driver.lockDevice();

        driver.findElement(By.id("com.experitest.ExperiBank:id/logoutButton")).click();
        long withListeners = System.currentTimeMillis() - t0;

        System.out.println("[stats] " + executor.stats());
        System.out.println("[stats] wall clock WITH listeners: " + withListeners + "ms");
        Assertions.assertTrue(executor.stats().contains("recoveries=0") == false,
                "the lock-screen listener should have recovered at least once");
    }

    @Test
    public void baselineWithoutListeners() {
        // Same commands, listeners disabled, so the interception cost is measurable rather
        // than guessed. An empty registry makes the executor a pass-through.
        listeners.clear();
        // Put the device in a known good state first, so this measures interception cost
        // and not the lock screen that the previous test may have left behind.
        driver.unlockDevice();
        driver.activateApp("com.experitest.ExperiBank");
        long t0 = System.currentTimeMillis();
        driver.findElement(By.id("com.experitest.ExperiBank:id/usernameTextField")).sendKeys("company");
        driver.findElement(By.id("com.experitest.ExperiBank:id/passwordTextField")).sendKeys("company");
        driver.findElement(By.id("com.experitest.ExperiBank:id/loginButton")).click();
        driver.findElement(By.id("com.experitest.ExperiBank:id/logoutButton")).click();
        System.out.println("[stats] wall clock WITHOUT listeners: " + (System.currentTimeMillis() - t0) + "ms");
    }

    @Test
    public void onFailureModeCostsNothingUntilSomethingBreaks() {
        // SeeTest's documented semantics: let the command run, and only if it fails, check the
        // listeners, recover, and retry the command. Zero extra round trips on the happy path.
        listeners.setMode(InterceptingListenerRegistry.Mode.ON_FAILURE);

        long t0 = System.currentTimeMillis();
        driver.findElement(By.id("com.experitest.ExperiBank:id/usernameTextField")).sendKeys("company");
        driver.findElement(By.id("com.experitest.ExperiBank:id/passwordTextField")).sendKeys("company");
        driver.findElement(By.id("com.experitest.ExperiBank:id/loginButton")).click();
        long happyPath = System.currentTimeMillis() - t0;

        System.out.println("[test] locking the device to simulate an unexpected interrupt");
        driver.lockDevice();

        long t1 = System.currentTimeMillis();
        driver.findElement(By.id("com.experitest.ExperiBank:id/logoutButton")).click();
        long afterInterrupt = System.currentTimeMillis() - t1;

        System.out.println("[stats] ON_FAILURE " + executor.stats());
        System.out.println("[stats] ON_FAILURE happy path: " + happyPath + "ms, recovered command: " + afterInterrupt + "ms");
    }

    @AfterEach
    public void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }
}
