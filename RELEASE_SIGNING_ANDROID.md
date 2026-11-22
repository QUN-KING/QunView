# QunView permanent Android release key

The permanent signing key is generated once by the GitHub Actions workflow. Its private JKS file and Base64 representation are stored only inside an AES-256-encrypted GPG archive uploaded as a short-lived GitHub Actions artifact. The password is the repository secret `QUNVIEW_KEYSTORE_PASSWORD`. Never send the password, JKS, or Base64 key to ChatGPT, issues, commits, or messages.

## One-time key generation

1. Add repository secret `QUNVIEW_KEYSTORE_PASSWORD` with a strong unique password and keep it safely.
2. Run **Actions → Create encrypted QunView release key → Run workflow** on `main` once.
3. Confirm the `create-key` job succeeds and the artifact `qunview-encrypted-release-key` is present.
4. Keep the artifact available until the signed APK workflow has successfully used it. The artifact expires after 7 days.

The artifact download from GitHub is an outer ZIP. Inside it is `qunview-release-key-AES256.tar.gz.gpg`. The inner file is a GPG symmetric AES-256 encrypted tar archive, NOT an encrypted ZIP. The release workflow decrypts it on a temporary GitHub runner using the repository password secret, extracts the JKS, validates its alias, builds the APK, verifies its signature, and deletes temporary key files.

## Build the permanent-key signed APK

After the key-generation workflow succeeds, run **Actions → Signed Android release APK → Run workflow**. The workflow automatically locates the latest successful key-generation artifact, decrypts it on the runner, signs the APK, verifies the signature with `apksigner`, and uploads `qunview-signed-release-apk`.

No Base64, alias, or key-password secrets need to be copied manually. The alias is `qunview-release`, and the keystore/key password is the value already stored in `QUNVIEW_KEYSTORE_PASSWORD`.

## Important security and release notes

- Do not run key generation again once a key artifact has been successfully created and used. A newly generated key would not match the installed app's signing identity.
- Keep the password backed up separately. The encrypted artifact is temporary (7-day retention); it is not a permanent backup. Download and retain the encrypted artifact in a private, secure location if you want an offline backup.
- The private key is generated and decrypted on GitHub-hosted runners. This is convenient but means you are trusting GitHub Actions as the temporary key-handling environment.
- The signed APK is not ready for public release until real two-device testing and Play Protect review are complete.
