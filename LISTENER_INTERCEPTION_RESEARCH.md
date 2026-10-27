# SeeTest-style listeners on a plain Appium server

Research + working proof of concept for replicating SeeTestClient's mobile-listener
mechanism on an Appium session where we do **not** control the server.

Measured on: Samsung SM-A217F (Android 12), Appium 3.1.2, uiautomator2 6.7.8,
java-client 7.6.0, Selenium 3.141.59.

---

## 1. What SeeTest actually does

Primary sources: the Digital.ai docs repo and the internal `regressionstable` suite.

### The documented contract

`client.addMobileListener(zone, xpath, listener)` — register as many as you like;
passing `null` as the listener removes it. `recover()` returns whether it recovered.

The Ruby doc states the sequence explicitly:

> Assuming that clicking on the OK Button failed, due to the fact that a blocking pop-up
> was visible on the screen. Then: 1. The recovery method will be invoked, and the Pop-up
> will be dismissed. 2. **If the recovery method succeeds, then the client will execute the
> original method yet again.**

So recovery is paired with a **re-attempt of the original command**, and it is bounded —
`listener.max.recovery.attempts`, documented as "how many times **in a row** recovery would
be attempted. If not set, the number of recovery attempts would be equal to the number of
mobile listeners set **times 2**."

### But detection is not purely failure-driven

`regressionstable/tests/android/.../MobileListenerTests.java` settles it:

```java
client.sendText("{HOME}");                                  // app goes to background
client.waitForElement("Native", "xpath=//*[@id='cantFindIt']", 0, 5000);  // never exists
// assert: the app is back in the foreground
```

The listener fires **during** a `waitForElement` for an element that never exists. The
recovery (re-launch the app) runs off the dump that the element search was taking anyway.

**What the sources support:** SeeTest evaluates listeners against the dump it is already
taking during element resolution, and after a successful recovery re-attempts the command,
bounded by the max-attempts setting. The check rides along with a dump the server was taking
anyway, which is why the docs can claim it costs no execution speed.

**What they do not settle:** whether SeeTest also checks before a command that *would have
succeeded*. Both regression tests are equally consistent with "check on every failed find,
recover, retry" — `waitForElement` on a non-existent element polls, and every poll is a
failed find; the blocked-click test likewise fits either model. Distinguishing the two needs
the engine source or the SeeTest engine owners, who are reachable internally.

### There is no server-side listener for Appium sessions

Listed the SeeTest Appium Extension's documented device commands
(`docs/te/.../appium/seetest-appium-extension/device-commands/`): `setProperty`,
`syncElements`, `deviceAction`, etc. — **no `addMobileListener` documented there**. `addMobileListener`
appears only under the legacy SeeTestAutomation client (marked a Legacy tool).

`listener.max.recovery.attempts` *is* listed in the Appium extension's `setProperty` page,
but no command exists to register a listener through that path.

So there is **no documented or discoverable server-side listener for Appium sessions** —
on local Appium or on the Digital.ai grid. A client-side mechanism is the portable option
for both. (Worth a confirmation from the grid team before treating this as final.)

---

## 2. Where to intercept on the client

| Option | Verdict |
| --- | --- |
| **Custom `CommandExecutor`** | **Chosen.** Every driver and element call funnels through `execute(Command)`. |
| `EventFiringWebDriver` (Selenium 3) | Rejected: fires on a fixed subset of events only, and returns `WebDriver`, losing `AndroidDriver`. |
| Dynamic proxy over the driver | Rejected: never sees `WebElement` methods (`click`, `sendKeys`) — they are issued by the element, not the driver. |
| Background polling thread (current `AppiumMobileListenerManager`) | Rejected as the primary mechanism: it races the main thread, needs `waitForListenersToSettle` sprinkled through tests, and gives no ordering guarantee relative to the command. |
| AspectJ load-time weaving | Works, but needs a javaagent and weaves third-party classes. Heavier than the executor for no extra coverage. |
| Appium server plugin | This is literally SeeTest's model, and viable **locally** since you run the server. Not available on the Digital.ai cloud, so it does not generalise. |

The enabler on java-client 7.6.0:

```java
public AndroidDriver(HttpCommandExecutor executor, Capabilities capabilities)
public static final Map<String, CommandInfo> MobileCommand.commandRepository
```

Both public, so a subclass of `AppiumCommandExecutor` can be injected into the driver.

---

## 3. Implementation

Three files, all additive:

- `src/test/java/InterceptingListenerRegistry.java` — listener storage, dump evaluation, recovery
- `src/test/java/ListenerCommandExecutor.java` — the interception point
- `src/test/java/InterceptedListenerTest.java` — PoC, 3 scenarios

### Non-obvious things that will bite

1. **A failed Appium command does not throw at executor level.**
   `HttpCommandExecutor.execute()` returns a `Response` carrying the error; it is
   `RemoteWebDriver.execute()` further up that converts it into `NoSuchElementException`.
   A `catch (WebDriverException)` around `super.execute()` never fires. Failure must be
   detected with `response.getStatus() != 0`. (Cost me a debugging cycle.)

2. **Re-entrancy.** The check itself calls `getPageSource`, and recovery callbacks call
   `findElement` / `activateApp` — all of which come back through the same executor on the
   same thread. A `ThreadLocal` guard is mandatory or you get infinite recursion.

3. **Evaluate the dump locally, not with N `findElement` calls.** One `GET /source` plus
   local `javax.xml.xpath` evaluation costs **one** round trip no matter how many listeners
   are registered, and it is immune to implicit waits. N `findElement` calls cost N round
   trips and each miss blocks for the full implicit-wait duration.

4. **Commands that must never be intercepted:** `NEW_SESSION` (no session id yet), `QUIT`,
   `STATUS`, `GET_PAGE_SOURCE` (recursion), `SCREENSHOT`, context handles.

5. **Locator translation bug in the existing manager.** `parseLocator` maps
   `accessibilityId=` to `By.id`. On Android accessibility id is `content-desc`, not
   `resource-id`, so those listeners never match. Fixed in the new registry.

6. **The driver does not exist when listeners are registered.** The executor must be built
   first, so the registry takes the driver via a setter afterwards.

---

## 4. Measured results

**The headline numbers are per-command, because the wall-clock figures below are not
directly comparable** (different step counts, and the PRE_COMMAND run includes an injected
lock plus its recovery).

| Measured quantity | Value |
| --- | --- |
| Dump round trip (`GET /session/<id>/source`) | **276–554ms** per intercepted command (an upper bound on cost, not the net cost — see the A/B below) |
| Local XPath evaluation of 4 listeners against the dump | **2.6–10.7ms** |
| Extra round trips added by N listeners | **1, regardless of N** |

### Controlled A/B measurement

`ListenerOverheadBenchmarkTest` runs the identical command sequence with and without the
intercepting executor, no listener ever firing, 3 alternating iterations each, app
force-stopped before every run. Command time excludes the 10s sleep and session creation.

| | Run 1 | Run 2 | Run 3 | Mean |
| --- | --- | --- | --- | --- |
| Listeners OFF | 3382ms | 3382ms | 3409ms | **3391ms** |
| Listeners ON (`PRE_COMMAND`) | 4441ms | 4473ms | 4493ms | **4469ms** |

**Net overhead: +1078ms (+32%) over 9 intercepted commands — about 120ms per command.**
Spread is ±30ms across runs; 0 recoveries in every ON run.

End-to-end per run, including the 10s sleep and session creation: OFF 20.83–20.96s,
ON 21.99–22.15s.

> **Multiplying the raw dump average by the command count overstates the real cost by
> roughly 3×** (9 × 277ms ≈ 2.5s predicted, 1.08s actual). The non-dump work runs faster
> when interception is on. Plausible causes are UiAutomator2 reusing the hierarchy the dump
> just built, or a one-off slow first fetch inflating the executor's average — not verified,
> and it does not change the ~120ms/command answer.

Wall clocks actually recorded, with their caveats:

| Run | Steps | Wall clock | Caveat |
| --- | --- | --- | --- |
| No listeners | 4 app steps | 4.0s | baseline; no `executor.stats()` captured |
| `PRE_COMMAND` | 4 app steps + injected lock + recovery | 8.8s | includes `lockDevice` and the unlock |
| `ON_FAILURE` | 3 app steps, happy path only | 2.7s | no logout step, no interrupt |

- The cost is the dump round trip, minus whatever the dump gives back by warming the
  server's hierarchy. SeeTest pays zero because it already holds the dump in process.
- `ON_FAILURE`'s 5 dumps in that run were 4 `setUp` housekeeping commands plus the 1 genuine
  failure — verified against the executor's own counters, no false positives. The happy path
  really is zero extra round trips.
- Recovery after an injected interrupt in `ON_FAILURE` took 13.2s = 10s implicit wait + dump
  + unlock + retry. The implicit wait dominates; lower it if you use this mode.

> These numbers were measured **before** two later fixes: gating interception on
> *enabled* listeners (previously a disabled listener still cost a dump per command) and the
> multi-pass PRE_COMMAND loop. Neither changes the per-command dump cost.

### Recovery was proven, not assumed

The PoC injects a genuine interrupt mid-test (`driver.lockDevice()`), and the lock-screen
listener detects `com.android.systemui:id/lock_icon_view` in the dump and unlocks before
the next command runs:

```
[test] locking the device to simulate an unexpected interrupt
[listener] matched id=com.android.systemui:id/lock_icon_view -> recovering
[test] lock screen detected, unlocking the device
```

(The lock injection was only ever run *with* listeners enabled; this PoC does not include a
controlled without-listeners comparison of that same interrupt.)

---

## 5. Choosing a mode

**`PRE_COMMAND` is the mode that meets your stated requirement** — check the listeners,
handle them, then perform the command — and it is the code default. Measured cost is
**~120ms per intercepted command, +32% on command time** for this test's command mix
(see the A/B in section 4). The levers below reduce it further.

**`ON_FAILURE` is an opt-in cheaper mode** matching SeeTest's documented retry contract:
zero overhead when nothing goes wrong. It has a real blind spot, though: it only fires when
a command *fails*. An interrupt that leaves your target element still in the hierarchy — an
in-app overlay, a coach mark, a banner in the same window — lets `click` or `sendKeys`
return success against the wrong thing, and no recovery is ever triggered. `PRE_COMMAND`
catches those; `ON_FAILURE` does not.

**`BOTH`** checks before and retries after, at the cost of both.

### Limits that no client-side mechanism can remove

- **The gap race.** The UI can change between your dump and the command landing on the
  device. SeeTest avoids this only because it evaluates inside the process that serves the
  command. From outside the server the race is unavoidable.
- **Stale elements after recovery.** If a recovery relaunches or restarts the app, the
  element ID the pending command targets can go stale. The executor cannot re-find the
  element on the caller's behalf, so the command may still fail even though recovery
  succeeded. Re-find inside the recovery callback where this matters.

### If PRE_COMMAND is too slow, cut the dump cost

- Restrict interception to an allowlist of commands (`findElement`, `click`, `sendKeys`)
  rather than everything.
- Cache the dump for a short TTL (~200ms) and reuse it across a burst of commands.
- Disable listeners around sections where interrupts are impossible — now genuinely free,
  since interception is gated on *enabled* listeners.

### If you want the real thing locally

An Appium server plugin reproduces SeeTest's architecture exactly, because locally you *do*
control the server. It will not work against the Digital.ai cloud, so the executor remains
the portable answer.

## 6. Running the PoC

```
appium                                     # in another terminal
mvn test -Dtest=InterceptedListenerTest
```

Override with `-Dappium.url=...` and `-Dappium.udid=...`.
