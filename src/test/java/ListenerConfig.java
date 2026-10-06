import org.openqa.selenium.By;

/**
 * Configuration for a mobile listener
 */
public class ListenerConfig {
    private By locator;                    // Element to watch for
    private ListenerAction action;         // Action to perform
    private String actionData;             // Additional data (e.g., text to send)
    private int timeout;                   // Max wait time in seconds (default 60)
    private int pollInterval;              // Check interval in milliseconds (default 500)
    private MobileListener customListener;  // Custom recovery handler
    private boolean enabled;               // Is listener active
    
    public ListenerConfig(By locator, ListenerAction action) {
        this.locator = locator;
        this.action = action;
        this.timeout = 60;
        this.pollInterval = 500;
        this.enabled = true;
    }
    
    public ListenerConfig(By locator, MobileListener listener) {
        this.locator = locator;
        this.action = ListenerAction.CUSTOM;
        this.customListener = listener;
        this.timeout = 60;
        this.pollInterval = 500;
        this.enabled = true;
    }
    
    // Getters and Setters
    public By getLocator() { return locator; }
    public void setLocator(By locator) { this.locator = locator; }
    
    public ListenerAction getAction() { return action; }
    public void setAction(ListenerAction action) { this.action = action; }
    
    public String getActionData() { return actionData; }
    public void setActionData(String actionData) { this.actionData = actionData; }
    
    public int getTimeout() { return timeout; }
    public void setTimeout(int timeout) { this.timeout = timeout; }
    
    public int getPollInterval() { return pollInterval; }
    public void setPollInterval(int pollInterval) { this.pollInterval = pollInterval; }
    
    public MobileListener getCustomListener() { return customListener; }
    public void setCustomListener(MobileListener customListener) { this.customListener = customListener; }
    
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
