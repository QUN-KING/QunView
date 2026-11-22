# Create the permanent QunView release key from GitHub Codespaces

This is a one-time owner action. The permanent signing key must remain under the repository owner's control. Never send the keystore, its passwords, or its Base64 contents in ChatGPT, issues, commits, or messages.

## 1. Open a Codespaces terminal

Open the QunView repository in GitHub Codespaces on the `main` branch, then open its terminal. The reviewed implementation is already merged into `main`. The commands below create the key in your home directory, outside the Git repository, so it is not accidentally committed.

## 2. Generate the permanent key

Copy and run:

```bash
mkdir -p "$HOME/qunview-release-key"
cd "$HOME/qunview-release-key"

keytool -genkeypair -v \
  -keystore qunview-release.jks \
  -alias qunview-release \
  -keyalg RSA -keysize 4096 -validity 10000
```

- Enter a strong keystore password when prompted and keep it in a password manager or another secure place.
- When asked for the key password, press Enter to use the same password as the keystore.
- Keep the alias exactly `qunview-release`.
- Do not share the password with anyone.

Verify that the key exists:

```bash
keytool -list -v -keystore qunview-release.jks -alias qunview-release
```

## 3. Make an offline backup

Download `qunview-release.jks` from the Codespaces file explorer to a safe location you control. Keep at least one secure backup outside Codespaces. Do not delete the only copy. Losing this key can prevent future app updates from installing over the existing app.

## 4. Prepare the Base64 value

In the Codespaces terminal, run:

```bash
base64 -w 0 qunview-release.jks > qunview-release.base64
```

Open `qunview-release.base64` in the Codespaces editor and copy its complete contents. This value is the `QUNVIEW_KEYSTORE_BASE64` secret. Do not paste it into chat or into a repository file.

## 5. Add four GitHub Actions secrets

In the QunView repository, open **Settings → Secrets and variables → Actions → New repository secret**. Add these four secrets:

| Secret name | Value |
|---|---|
| `QUNVIEW_KEYSTORE_BASE64` | Complete contents of `qunview-release.base64` |
| `QUNVIEW_KEYSTORE_PASSWORD` | The keystore password you created |
| `QUNVIEW_KEY_ALIAS` | `qunview-release` |
| `QUNVIEW_KEY_PASSWORD` | The key password (same as the keystore password if you pressed Enter) |

Enter the values directly in GitHub. Never send them in chat.

## 6. Build the permanent-key-signed APK

After the four secrets are saved and the reviewed PR has been merged to `main`, run **Actions → Signed Android release APK → Run workflow** or push a version tag beginning with `v`.

The workflow checks the secrets, builds the Release APK, verifies its signature with Android's `apksigner`, and uploads the signed APK artifact. Do not install it until the two-device test and Play Protect scan are complete.

## Important

The normal Android CI also runs a Release preflight with a disposable test key. That proves the Release build and signature-verification steps work, but it is not the permanent key and its APK is not a distributable release.
