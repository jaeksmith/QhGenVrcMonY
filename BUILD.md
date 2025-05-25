# VRC Monitor Build and Run Instructions

## Prerequisites
- Java 17 or higher
- Maven 3.6.3 or higher
- Git (optional, for version control)

## Development Build and Run

### Building for Development
1. Clone or download the repository
2. Navigate to the project root directory
3. Run the following command to build the project:
```
mvn clean compile
```

### Running in Development Mode
Run the application using Maven Spring Boot plugin:
```
mvn spring-boot:run
```

The application will be accessible at http://localhost:8080

## Production Build

### Creating an Executable JAR
To build an executable JAR file that can be distributed and run:
```
mvn clean package
```

This will create a JAR file in the `target` directory named `vrc-monitor-1.0-SNAPSHOT.jar`.

### Running the Production JAR
The built JAR file can be run using:
```
java -jar target/vrc-monitor-1.0-SNAPSHOT.jar
```

## Deployment Options

### Option 1: Simple JAR Deployment
1. Build the JAR file as described above
2. Copy the JAR file to your target server
3. Run the JAR using the provided `run.bat` script or directly with the java command:
```
java -jar vrc-monitor-1.0-SNAPSHOT.jar
```

### Option 2: Custom Configuration
You can customize the application by providing environment variables or system properties:

```
java -Dserver.port=9090 -jar vrc-monitor-1.0-SNAPSHOT.jar
```

To change the server port to 9090, for example.

## Troubleshooting

### Common Issues

1. **Port already in use**:
   - Change the port using the server.port property: `java -Dserver.port=9090 -jar vrc-monitor-1.0-SNAPSHOT.jar`

2. **Missing dependencies**:
   - If running the JAR directly and encountering dependency issues, ensure you're using the JAR built with `mvn package` and not just compiled classes

3. **Java version issues**:
   - Ensure you're using Java 17 or higher. Check with: `java -version` 