# NectarLink Repository Guidelines

## Project Structure & Paths
- **Android App ("Link")**: Always use `C:\Users\adars\AndroidStudioProjects\NectarLink` for all Android app development, maintenance, and builds of NectarLink (app name: `Link`).
- **Desktop App**: The desktop application (Qt/QML/Rust) is developed in `C:\Users\adars\nectarlink-main`.
- **Removed Directory**: The `android/` subfolder in `C:\Users\adars\nectarlink-main` has been removed and must never be recreated or used. All Android code is strictly located in `C:\Users\adars\AndroidStudioProjects\NectarLink`.

## Development Rules
- Always use **Android CLI** for Android development tasks in `C:\Users\adars\AndroidStudioProjects\NectarLink`.
- The Android app display name is `Link` (`app/src/main/res/values/strings.xml`).
