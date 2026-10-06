# Android Test Project

A properly configured Maven project for testing Android applications using Appium and Java.

## Project Structure

```
listener/
├── pom.xml                                 # Maven configuration file
├── src/
│   └── test/
│       └── java/
│           └── LocalAndroidTest.java      # Test class
├── target/                                 # Built artifacts (auto-generated)
├── lib/                                    # Optional local libraries
└── README.md                              # This file
```

## Prerequisites

- **Java**: JDK 11 or higher
- **Maven**: 3.6.0 or higher
- **Appium Server**: Running and accessible at `https://uscloud.experitest.com/wd/hub`

## Dependencies

The project includes:
- **JUnit 5.9.3**: Testing framework
- **Appium Java Client 7.6.0**: Mobile automation library
- **Selenium 4.0.0**: WebDriver for browser automation

## Building the Project

### Compile the code:
```bash
mvn clean compile
```

### Compile tests:
```bash
mvn clean test-compile
```

### Full build:
```bash
mvn clean package
```

## Running the Tests

### Run all tests:
```bash
mvn test
```

### Run a specific test class:
```bash
mvn test -Dtest=LocalAndroidTest
```

### Run with Maven Surefire Plugin:
```bash
mvn clean test
```

## Configuration

Before running the tests, configure the access key in `LocalAndroidTest.java`:

```java
private String accessKey = "[Enter Your Access key here]";
```

## Key Test Classes

### LocalAndroidTest
- **Method**: `quickStartAndroidNativeDemo()`
- **Purpose**: Demonstrates a complete Android test flow with Appium
- **Features**:
  - Device orientation control
  - Element interaction (click, send keys)
  - Dialog handling
  - Report URL generation

### Setup and Teardown

- **setUp()**: Initializes the AndroidDriver with required capabilities
- **tearDown()**: Prints the report URL and closes the driver session

## Maven Plugins

- **maven-compiler-plugin**: Compiles source code (Java 11)
- **maven-surefire-plugin**: Runs unit tests
- **maven-shade-plugin**: Creates fat JARs with all dependencies

## Troubleshooting

### Build Warnings
- "bootstrap class path not set" - Can be safely ignored with Java 11
- "system modules path not set" - Can be safely ignored with Java 11

### Common Issues

1. **Access Key Error**: Ensure you've replaced the placeholder access key
2. **Connection Error**: Verify Appium server is running and URL is correct
3. **Element Not Found**: Verify the app is installed and responsive on the device

## Project Configuration

**Group ID**: com.example  
**Artifact ID**: android-test  
**Version**: 1.0-SNAPSHOT  
**Packaging**: jar

## Usage Example

```java
@Test
public void exampleTest() {
    // Rotate device to portrait
    driver.rotate(ScreenOrientation.PORTRAIT);
    
    // Find and interact with elements
    driver.findElement(By.id("elementId")).sendKeys("input");
    driver.findElement(By.id("buttonId")).click();
}
```

## Notes

- The project follows standard Maven directory structure
- All dependencies are managed through the `pom.xml` file
- Tests use JUnit 5 Jupiter annotations (`@Test`, `@BeforeEach`, `@AfterEach`)
- Compiled classes are in `target/test-classes/`
