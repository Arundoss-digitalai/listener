import io.appium.java_client.MobileCommand;
import io.appium.java_client.remote.AppiumCommandExecutor;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.remote.Command;
import org.openqa.selenium.remote.DriverCommand;
import org.openqa.selenium.remote.Response;

import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * An Appium command executor that evaluates registered listeners around every command,
 * which is the closest client-side equivalent of SeeTest's server-side listener hook.
 *
 * Every single thing the driver does -- driver.findElement, element.click, element.sendKeys,
 * rotate, activateApp, the lot -- is funnelled through {@link #execute(Command)}, so this is
 * the only interception point on java-client 7 / Selenium 3 that genuinely sees all commands.
 * EventFiringWebDriver only fires on a fixed subset, and a dynamic proxy over the driver never
 * sees WebElement methods at all.
 */
public class ListenerCommandExecutor extends AppiumCommandExecutor {

    /**
     * Commands that must never trigger a listener check.
     * NEW_SESSION/QUIT/STATUS have no usable session; GET_PAGE_SOURCE is what the check itself
     * uses (intercepting it would recurse); the rest are cheap metadata reads.
     */
    private static final Set<String> NEVER_INTERCEPT = new HashSet<>(Arrays.asList(
            DriverCommand.NEW_SESSION,
            DriverCommand.QUIT,
            DriverCommand.STATUS,
            DriverCommand.GET_PAGE_SOURCE,
            DriverCommand.SCREENSHOT,
            DriverCommand.GET_ALL_SESSIONS,
            DriverCommand.GET_CURRENT_CONTEXT_HANDLE,
            DriverCommand.GET_CONTEXT_HANDLES
    ));

    /**
     * Guards against re-entry: the listener check and the recovery callbacks issue their own
     * commands, and those come back through this same executor on this same thread.
     */
    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> false);

    private final InterceptingListenerRegistry registry;
    private long interceptedCommands = 0;
    private long totalDumpNanos = 0;
    private long dumps = 0;

    public ListenerCommandExecutor(URL remoteAddress, InterceptingListenerRegistry registry) {
        super(MobileCommand.commandRepository, remoteAddress);
        this.registry = registry;
    }

    @Override
    public Response execute(Command command) throws WebDriverException {
        if (!shouldIntercept(command)) {
            return super.execute(command);
        }

        INSIDE.set(true);
        try {
            interceptedCommands++;
            InterceptingListenerRegistry.Mode mode = registry.getMode();

            if (mode == InterceptingListenerRegistry.Mode.PRE_COMMAND
                    || mode == InterceptingListenerRegistry.Mode.BOTH) {
                // Keep checking while something is still recovering: dismissing one interrupt
                // can reveal another behind it. Bounded like SeeTest's "times in a row" limit.
                // The happy path still costs exactly one dump.
                int passes = registry.maxRecoveryAttempts();
                while (passes-- > 0 && checkOnce(command)) {
                    // a listener recovered; look again before letting the command through
                }
            }

            if (mode == InterceptingListenerRegistry.Mode.PRE_COMMAND) {
                return superExecute(command);
            }

            // ON_FAILURE / BOTH: run the command, and on failure recover and retry it,
            // bounded the way SeeTest bounds it with listener.max.recovery.attempts.
            //
            // Note: at this level a failed Appium command does NOT throw. HttpCommandExecutor
            // hands back a Response carrying the error, and it is RemoteWebDriver.execute()
            // further up that turns it into NoSuchElementException. So the failure has to be
            // detected by inspecting the Response status, not with a catch block.
            int attemptsLeft = registry.maxRecoveryAttempts();
            while (true) {
                Response response;
                try {
                    response = superExecute(command);
                } catch (WebDriverException e) {
                    // transport-level failure (socket, timeout); still worth one recovery pass
                    if (attemptsLeft-- <= 0 || !checkOnce(command)) {
                        throw e;
                    }
                    continue;
                }
                if (!isFailure(response)) {
                    return response;
                }
                if (attemptsLeft-- <= 0 || !checkOnce(command)) {
                    return response; // let the driver raise the original error
                }
                // a listener recovered something; retry the original command
            }
        } finally {
            INSIDE.set(false);
        }
    }

    /** Fetch one dump and let the registry evaluate every listener against it. */
    private boolean checkOnce(Command command) {
        try {
            long t0 = System.nanoTime();
            Response r = superExecute(new Command(command.getSessionId(), DriverCommand.GET_PAGE_SOURCE));
            totalDumpNanos += System.nanoTime() - t0;
            dumps++;
            Object value = r.getValue();
            if (!(value instanceof String)) {
                return false;
            }
            return registry.checkAndRecover((String) value);
        } catch (Exception e) {
            // A listener check must never be the reason a test fails.
            return false;
        }
    }

    /** A non-zero status means the server rejected the command (element not found, etc.). */
    private static boolean isFailure(Response response) {
        Integer status = response.getStatus();
        return status != null && status != 0;
    }

    private Response superExecute(Command command) {
        return super.execute(command);
    }

    private boolean shouldIntercept(Command command) {
        if (INSIDE.get()) {
            return false;
        }
        if (registry == null || !registry.hasEnabledListeners()) {
            return false;
        }
        if (command.getSessionId() == null) {
            return false;
        }
        return !NEVER_INTERCEPT.contains(command.getName());
    }

    public String stats() {
        double avgDumpMs = dumps == 0 ? 0 : (totalDumpNanos / (double) dumps) / 1_000_000d;
        return String.format("intercepted commands=%d, dumps fetched=%d, avg dump round trip=%.1fms | %s",
                interceptedCommands, dumps, avgDumpMs, registry.stats());
    }
}
