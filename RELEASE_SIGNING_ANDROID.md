# Create the permanent QunView release key

This is a one-time owner action. The permanent signing key must remain under the repository owner's control. Never send the keystore, its passwords, or its Base64 contents in ChatGPT, issues, commits, or messages.

## Recommended method: encrypted GitHub Actions backup (no Codespaces or Termux)

This method creates the key on a temporary GitHub-hosted runner and uploads **only an AES-256-encrypted backup**. The password is chosen by you and entered directly into GitHub Secrets. The workflow does not print the key or password.

### 1. Choose and save a strong password

In the QunView repository, open **Settings → Secrets and variables → Actions → New repository secret**.

Create this secret:

- Name: `QUNVIEW_KEYSTORE_PASSWORD`
- Value: a new, strong, unique password that you create and save securely in a password manager or another safe place.

Do not use a password you use on other accounts. Do not send it in ChatGPT or commit it to the repository. The workflow uses this same password for the keystore and its AES-256 encrypted backup.

### 2. Run the one-time key-generation workflow

Open **Actions → Create encrypted QunView release key → Run workflow** on the `main` branch.

The workflow creates:
- `qunview-release.jks` — the permanent private signing key
- `QUNVIEW_KEYSTORE_BASE64.txt` — the value needed for GitHub Actions
- `README.txt` — alias and setup notes

These files are placed inside an AES-256-encrypted ZIP. The workflow uploads only that encrypted ZIP as an Actions artifact. The unencrypted working files are removed from the temporary runner after the job.

**Do not run this workflow again after the permanent key has been configured.** It deliberately refuses to replace a key once `QUNVIEW_KEYSTORE_BASE64` exists.

### 3. Download and open the encrypted backup

From the completed workflow run, download the artifact named `qunview-encrypted-release-key`. Extract the outer GitHub artifact ZIP. Inside it is `qunview-release-key-AES256.zip`.

Open that inner ZIP with a trusted Android archive app that supports AES-encrypted ZIP files, or on a trusted computer. Enter the exact password you saved as `QUNVIEW_KEYSTORE_PASSWORD`.

Keep `qunview-release.jks` as a private offline backup. Never upload it to a public repository or share it.

### 4. Add the remaining signing secrets

Open the extracted `QUNVIEW_KEYSTORE_BASE64.txt` locally and copy its complete contents directly into GitHub:

| Secret name | Value |
|---|---|
| `QUNVIEW_KEYSTORE_BASE64` | Complete contents of `QUNVIEW_KEYSTORE_BASE64.txt` |
| `QUNVIEW_KEY_ALIAS` | `qunview-release` |
| `QUNVIEW_KEY_PASSWORD` | Same password as `QUNVIEW_KEYSTORE_PASSWORD` |

Create each at **Settings → Secrets and variables → Actions → New repository secret**. Never paste the Base64 value or passwords into chat, issues, commits, or repository files.

### 5. Build the permanent-key-signed APK

After all four signing secrets are saved, run **Actions → Signed Android release APK → Run workflow**. The workflow builds the Release APK, verifies its signature with Android's `apksigner`, and uploads the APK artifact.

Do not install or distribute the release until the two-device test and Play Protect checks are complete.

## Alternative: create the key in Codespaces

If you prefer to create the key locally in a Codespaces terminal, follow the commands below. This method does not use the encrypted-key workflow above.

```bash
mkdir -p "$HOME/qunview-release-key"
cd "$HOME/qunview-release-key"

keytool -genkeypair -v \
  -keystore qunview-release.jks \
  -alias qunview-release \
  -keyalg RSA -keysize 4096 -validity 10000
```

Enter a strong keystore password and press Enter at the key-password prompt to use the same password. Keep the alias exactly `qunview-release`. Back up the JKS file securely, then prepare its Base64 value:

```bash
base64 -w 0 qunview-release.jks > qunview-release.base64
```

Use the Base64 file and the same four GitHub secret names listed above.

## Important

The normal Android CI Release preflight uses a disposable test key. It proves the Release build and signature-verification steps work, but it is not the permanent key and its APK is not a distributable release. Losing the permanent key can prevent future updates from installing over the existing app.
