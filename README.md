# Appium Mobile Listeners

SeeTest-style mobile listeners for Appium: register any number of listeners, and every
Appium command is preceded by a check for them, with recovery run before the command
proceeds.

Appium has no server-side equivalent of SeeTestClient's listeners — and neither does the
Digital.ai grid for Appium sessions — so the check is done client side by intercepting
every command in a custom `CommandExecutor`. See
[LISTENER_INTERCEPTION_RESEARCH.md](LISTENER_INTERCEPTION_RESEARCH.md) for the sources,
the design decisions and the measured overhead.

## Project structure

```
listener/
├── pom.xml
├── LISTENER_INTERCEPTION_RESEARCH.md        # research, measurements, trade-offs
└── src/test/java/
    ├── ListenerCommandExecutor.java         # intercepts every Appium command
    ├── InterceptingListenerRegistry.java    # listener storage, dump evaluation, recovery
    ├── MobileListener.java                  # recover(locator) callback interface
    ├── ListenerAction.java                  # built-in actions (CLICK, WAIT_AND_DISMISS, ...)
    ├── LocalDeviceAndroidTest.java          # runs on a local device via a local Appium server
    └── LocalAndroidTest.java                # the same test on a Digital.ai cloud device
```

## Prerequisites

- JDK 11 or higher, Maven 3.6+
- **For the local test**: an Appium 3 server (`appium`), a connected device (`adb devices`),
  and `com.experitest.ExperiBank` installed on it
- **For the cloud test**: a Digital.ai access key

## Running

Local device, local Appium server:

```bash
appium                                    # in another terminal
mvn test -Dtest=LocalDeviceAndroidTest
```

Override the server or device if needed:

```bash
mvn test -Dtest=LocalDeviceAndroidTest -Dappium.url=http://127.0.0.1:4723/ -Dappium.udid=<udid>
```

Digital.ai cloud device:

```bash
mvn test -Dtest=LocalAndroidTest -Ddigitalai.accessKey=<your key>
```

The access key is read from a system property so it never has to be committed.

## How to use the listener

Three steps, in this order — the registry must exist before the driver, because the
executor the driver is built on consults it:

```java
// 1. Create the registry and register listeners (no driver needed yet)
InterceptingListenerRegistry listeners = new InterceptingListenerRegistry()
        .setMode(InterceptingListenerRegistry.Mode.PRE_COMMAND);

listeners.addMobileListener("//*[@text='Play Store']", locator -> {
    driver.activateApp("com.experitest.ExperiBank");
    return true;
});

// 2. Build the driver on the intercepting executor
ListenerCommandExecutor executor = new ListenerCommandExecutor(new URL(url), listeners);
AndroidDriver<AndroidElement> driver = new AndroidDriver<>(executor, caps);

// 3. Hand the driver back so recovery callbacks can act on it
listeners.setDriver(driver);
```

After that, the test body needs no changes at all: every `findElement`, `sendKeys`,
`click` and `rotate` is checked before it runs.

### Locator syntax

`id=`, `xpath=`, `class=`, `text=`, `accessibilityId=`, or a bare XPath.

### Modes

| Mode | Behaviour |
| --- | --- |
| `PRE_COMMAND` (default) | Check the listeners before every command, then run it. |
| `ON_FAILURE` | Run the command; only if it fails, recover and retry. Free on the happy path, but misses interrupts that do not make the command fail. |
| `BOTH` | Both of the above. |

### Cost

Measured on a Samsung SM-A217F with a local Appium server: **~120ms per intercepted
command, +32% on command time**. Detection costs one page-source round trip per command
regardless of how many listeners are registered, because all listeners are evaluated
locally against that single dump.

Note that on the Digital.ai cloud those extra page-source calls also appear in the test
report alongside your functional steps.
