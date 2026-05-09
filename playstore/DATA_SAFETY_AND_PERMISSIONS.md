# Data Safety and Permissions Guide

Use this as a reference while filling Play Console forms.

## Data safety (expected)

- Data collected: No
- Data shared: No
- App activity tracked off-device: No
- Device/app identifiers sent to server: No

If you later add analytics, crash reporting, or remote APIs, update this immediately.

## Permission purpose mapping

- `android.permission.PACKAGE_USAGE_STATS`
  - Purpose: Detect foreground app to apply protection to user-selected targets
  - User-facing core feature: Yes

- `android.permission.SYSTEM_ALERT_WINDOW` (optional)
  - Purpose: Overlay-based protection/watermark feature when user enables it
  - User-facing core feature: Optional feature

- `android.permission.POST_NOTIFICATIONS`
  - Purpose: Foreground service notification and quick controls
  - User-facing core feature: Operational transparency and controls

- `android.permission.FOREGROUND_SERVICE` and `android.permission.FOREGROUND_SERVICE_DATA_SYNC`
  - Purpose: Keep protection active reliably while app is running
  - User-facing core feature: Yes

- `android.permission.RECEIVE_BOOT_COMPLETED`
  - Purpose: Restore protection service after reboot when user opted in
  - User-facing core feature: Yes

## Play Console declarations to prepare

- Foreground service declaration
- Sensitive permission justification for Usage Access and Overlay
- Privacy policy URL hosted publicly
- Clear app access instructions for reviewer account/test device
