# Geek Monitor Admin

Flutter client for the Geek Monitor API. The same application builds for Web, Linux desktop, and Android.

## Local development

```sh
flutter pub get
flutter run -d linux --dart-define=API_BASE_URL=http://localhost:8080
```

The sign-in and first-install screens let you select the `geek-mintor.geekcorelab.cn` or `.com` API, or enter a custom HTTP(S) API root. The selection is saved on the device. Native clients default to the `.cn` API; Web uses the origin that serves the client. The built-in server allows credentialed Web requests from those two hostnames. A custom Web API must allow the app's origin and credentials through CORS.

## Release builds

Pushing a `v*` tag matching the versions in `pubspec.yaml` and the server starts separate parallel jobs for Linux desktop packages (`.deb`, `.rpm`, and AppImage) and an Android release APK. The release is published after the server and both client jobs finish successfully.
