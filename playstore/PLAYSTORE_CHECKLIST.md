# Play Store Release Checklist

## 1) Build and signing

- [ ] Confirm release signing is configured in `keystore.properties`.
- [ ] Build release bundle:
  - `./gradlew bundleRelease` (Linux/macOS)
  - `./gradlew.bat bundleRelease` (Windows)
- [ ] Verify output AAB exists:
  - `app/build/outputs/bundle/release/app-release.aab`

## 2) Google Play Console setup

- [ ] Create app in Play Console with package name: `com.securescreen.app`
- [ ] Set app category and contact details
- [ ] Upload `app-release.aab` to Internal testing first
- [ ] Create release notes

## 3) Privacy Policy URL

- [ ] Host this file publicly: `docs/privacy-policy.html`
- [ ] Example with GitHub Pages: `https://adikul1023.github.io/SecureScreen/privacy-policy.html`
- [ ] Paste the final public URL into Play Console Privacy Policy field

## 4) Store Listing

- [ ] Use listing text from `playstore/STORE_LISTING.en-US.md`
- [ ] Add app icon, feature graphic, and screenshots

## 5) App Content declarations

- [ ] Data safety section (see `playstore/DATA_SAFETY_AND_PERMISSIONS.md`)
- [ ] Ads declaration: No (if no ads SDK)
- [ ] App access instructions: explain how to enable Usage Access and optional Overlay
- [ ] Foreground service declaration: explain continuous protection use-case
- [ ] Sensitive permissions declaration: justify Usage Access and Overlay as core user-facing functionality

## 6) Policy readiness checks

- [ ] No deceptive behavior claims
- [ ] Clear user-facing explanation for every sensitive permission
- [ ] Privacy policy matches runtime behavior
- [ ] Test on Android 13+ notification permission flow

## 7) Rollout strategy

- [ ] Internal testing
- [ ] Closed testing
- [ ] Production staged rollout (for example 10%, then 50%, then 100%)
