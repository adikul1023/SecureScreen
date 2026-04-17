# Submit SecureScreen To F-Droid

This project is prepared for F-Droid submission with clean source tag `v1.0.1`.

## Files prepared in this repository

- `fdroid/com.securescreen.app.yml` (metadata template for fdroiddata)

## Submission steps

1. Fork `https://gitlab.com/fdroid/fdroiddata` to your GitLab account.
2. Clone your fork locally.
3. Copy `fdroid/com.securescreen.app.yml` from this repo into `metadata/com.securescreen.app.yml` in your fdroiddata fork.
4. Commit and push to your fork.
5. Open a Merge Request to `fdroid/fdroiddata`.

## Optional CLI flow

```bash
git clone https://gitlab.com/<your-user>/fdroiddata.git
cd fdroiddata
cp /path/to/SecureScreen/fdroid/com.securescreen.app.yml metadata/com.securescreen.app.yml
git checkout -b add-securescreen
git add metadata/com.securescreen.app.yml
git commit -m "Add SecureScreen (com.securescreen.app)"
git push origin add-securescreen
```

Then open the Merge Request in GitLab.

## App source details used by metadata

- Repo: `https://github.com/adikul1023/SecureScreen.git`
- Build tag: `v1.0.1`
- Package name: `com.securescreen.app`
- Version: `1.0.0` (`versionCode` 1)
