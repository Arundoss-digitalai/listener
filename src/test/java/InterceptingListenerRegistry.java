import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the registered listeners and evaluates them against a single page-source dump,
 * the way SeeTest evaluates its listeners against the dump it already holds.
 *
 * This class performs NO remote calls of its own for detection: the caller (the command
 * executor) hands it the XML dump. Detection is therefore one round trip per check,
 * regardless of how many listeners are registered.
 *
 * Recovery, by contrast, does drive the device through the driver.
 */
public class InterceptingListenerRegistry {

    /** When listeners are evaluated. */
    public enum Mode {
        /** Check the dump before every intercepted command (what SeeTest appears to do). */
        PRE_COMMAND,
        /** Only check after a command throws, then retry the command. (SeeTest's documented retry.) */
        ON_FAILURE,
        /** Both of the above. */
        BOTH
    }

    /** One registered listener. */
    static class Entry {
        final String rawLocator;
        final String xpath;
        final ListenerAction action;
        final MobileListener callback;
        volatile boolean enabled = true;

        Entry(String rawLocator, String xpath, ListenerAction action, MobileListener callback) {
            this.rawLocator = rawLocator;
            this.xpath = xpath;
            this.action = action;
            this.callback = callback;
        }
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final Map<String, XPathExpression> compiled = new LinkedHashMap<>();
    private final XPath xPath = XPathFactory.newInstance().newXPath();
    private final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

    private volatile AndroidDriver<?> driver;
    private volatile Mode mode = Mode.PRE_COMMAND;
    private volatile Integer maxRecoveryAttempts; // null => listeners * 2, like SeeTest's default
    private volatile boolean verbose = false;

    // Simple instrumentation so the overhead of this mechanism is measurable, not guessed.
    private long checks = 0;
    private long totalCheckNanos = 0;
    private long recoveries = 0;

    public InterceptingListenerRegistry() {
        dbf.setNamespaceAware(false);
    }

    /** The driver is injected after construction: the executor must exist before the driver does. */
    public void setDriver(AndroidDriver<?> driver) {
        this.driver = driver;
    }

    public InterceptingListenerRegistry setMode(Mode mode) {
        this.mode = mode;
        return this;
    }

    public Mode getMode() {
        return mode;
    }

    public InterceptingListenerRegistry setMaxRecoveryAttempts(int attempts) {
        this.maxRecoveryAttempts = attempts;
        return this;
    }

    public InterceptingListenerRegistry setVerbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    public int maxRecoveryAttempts() {
        return maxRecoveryAttempts != null ? maxRecoveryAttempts : Math.max(1, entries.size() * 2);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Interception must be gated on this, not on {@link #isEmpty()}. Otherwise disabling all
     * listeners still costs a dump round trip on every single command.
     */
    public boolean hasEnabledListeners() {
        return entries.stream().anyMatch(e -> e.enabled);
    }

    // ---------------------------------------------------------------- registration

    public InterceptingListenerRegistry addMobileListener(String locator, ListenerAction action) {
        entries.add(new Entry(locator, toXPath(locator), action, null));
        return this;
    }

    public InterceptingListenerRegistry addMobileListener(String locator, MobileListener callback) {
        // Passing null removes the listener, mirroring SeeTest's addMobileListener(..., null).
        if (callback == null) {
            entries.removeIf(e -> e.rawLocator.equals(locator));
            return this;
        }
        entries.add(new Entry(locator, toXPath(locator), ListenerAction.CUSTOM, callback));
        return this;
    }

    public void removeMobileListener(String locator) {
        entries.removeIf(e -> e.rawLocator.equals(locator));
    }

    public void clear() {
        entries.clear();
    }

    public void setAllEnabled(boolean enabled) {
        entries.forEach(e -> e.enabled = enabled);
    }

    // ---------------------------------------------------------------- evaluation

    /**
     * Evaluate every enabled listener against one dump and run the recovery of each that matches.
     *
     * @param pageSource the XML dump, already fetched by the caller
     * @return true if at least one listener recovered (so the caller may re-attempt its command)
     */
    boolean checkAndRecover(String pageSource) {
        long start = System.nanoTime();
        boolean recoveredAny = false;
        Document doc;
        try {
            doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(pageSource.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            if (verbose) {
                System.err.println("[listener] could not parse dump: " + e.getMessage());
            }
            return false;
        }

        List<Entry> matched = new ArrayList<>();
        for (Entry entry : entries) {
            if (!entry.enabled) {
                continue;
            }
            try {
                XPathExpression expr = compiled.computeIfAbsent(entry.xpath, x -> {
                    try {
                        return xPath.compile(x);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Bad listener XPath: " + x, e);
                    }
                });
                NodeList nodes = (NodeList) expr.evaluate(doc, XPathConstants.NODESET);
                if (nodes.getLength() > 0) {
                    matched.add(entry);
                }
            } catch (Exception e) {
                if (verbose) {
                    System.err.println("[listener] evaluation failed for " + entry.rawLocator + ": " + e.getMessage());
                }
            }
        }

        checks++;
        totalCheckNanos += System.nanoTime() - start;

        for (Entry entry : matched) {
            if (recover(entry)) {
                recoveredAny = true;
            }
        }
        return recoveredAny;
    }

    private boolean recover(Entry entry) {
        recoveries++;
        if (verbose) {
            System.out.println("[listener] matched " + entry.rawLocator + " -> recovering");
        }
        try {
            if (entry.action == ListenerAction.CUSTOM) {
                return entry.callback.recover(entry.rawLocator);
            }
            return runBuiltInAction(entry);
        } catch (Exception e) {
            System.err.println("[listener] recovery failed for " + entry.rawLocator + ": " + e.getMessage());
            return false;
        }
    }

    private boolean runBuiltInAction(Entry entry) throws InterruptedException {
        AndroidDriver<?> d = driver;
        if (d == null) {
            return false;
        }
        switch (entry.action) {
            case CLICK:
            case DISMISS:
                WebElement el = d.findElement(By.xpath(entry.xpath));
                el.click();
                return true;
            case WAIT_AND_DISMISS:
                Thread.sleep(500);
                d.findElement(By.xpath(entry.xpath)).click();
                return true;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- locator translation

    /**
     * Translate the locator syntax used by the existing manager into a plain XPath that can be
     * evaluated locally against the dump. Note accessibilityId maps to content-desc, not to id.
     */
    static String toXPath(String locator) {
        if (locator.startsWith("id=") || locator.startsWith("id:")) {
            return "//*[@resource-id='" + locator.substring(3) + "']";
        } else if (locator.startsWith("xpath=")) {
            return locator.substring(6);
        } else if (locator.startsWith("class=")) {
            return "//*[@class='" + locator.substring(6) + "']";
        } else if (locator.startsWith("text=")) {
            return "//*[@text='" + locator.substring(5) + "']";
        } else if (locator.startsWith("accessibilityId=")) {
            return "//*[@content-desc='" + locator.substring(16) + "']";
        }
        return locator;
    }

    // ---------------------------------------------------------------- stats

    public String stats() {
        double avgMs = checks == 0 ? 0 : (totalCheckNanos / (double) checks) / 1_000_000d;
        return String.format("listener checks=%d, avg local-xpath eval=%.2fms, recoveries=%d", checks, avgMs, recoveries);
    }
}
