/**
 * Interface for handling mobile UI interrupts (loaders, dialogs, alerts, etc.)
 * Similar to SeeTest Automation MobileListener
 */
public interface MobileListener {
    
    /**
     * Called when the registered element is detected
     * 
     * @param locator The locator that was triggered
     * @return true if recovery was successful, false otherwise
     */
    boolean recover(String locator);
}
