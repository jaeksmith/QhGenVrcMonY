# VRChat Monitor

A monitoring application for tracking VRChat user status.

## Features

- Real-time monitoring of VRChat user status
- Web-based UI with status indicators
- Text-to-speech announcements for status changes
- Session persistence across application restarts

## Configuration

The application is configured via `config.json` in the application's root directory.

### Main Configuration Options

- `users`: Array of users to monitor (each with `hrToken`, `vrcUid`, and `pollRate`)
- `logErrorsToFile`: When true, errors are logged to files in the `logs` directory
- `fileCacheSesssionInfo`: When true, session cookies are cached to enable persistence between restarts

### Session Persistence

When `fileCacheSesssionInfo` is set to `true`, the application will:

1. Save VRChat session cookies to `vrc_session_cache.json` after successful login
2. Attempt to restore the session from cache on startup
3. Automatically clear the cache file when the session is terminated or becomes invalid

This allows the application to maintain its authenticated session after restarts without requiring re-login. 

**Note**: The session cache only stores authentication cookies, not login credentials.

## Usage

1. Configure the users you want to monitor in `config.json`
2. Run the application
3. Log in via the web interface
4. The status of monitored users will be displayed in the UI

## Development

This is a Spring Boot application using:
- Java 17+
- WebFlux for reactive API communication
- WebSockets for real-time UI updates 