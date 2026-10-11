# Release signing

The repository never contains a signing key. Google Play App Signing is recommended: you keep an **upload key**, Google holds the app signing key.

1. Create an upload key (once), store it outside the repo, back it up in a password manager/secure vault:
   ```bash
   keytool -genkeypair -v -keystore ~/secure/omd-upload.jks -alias omd-upload \
     -keyalg RSA -keysize 4096 -validity 9125
   ```
2. Local builds: create `android/keystore.properties` (git-ignored):
   ```properties
   storeFile=/home/you/secure/omd-upload.jks
   storePassword=…
   keyAlias=omd-upload
   keyPassword=…
   ```
   CI builds: set secrets `OMD_KEYSTORE_FILE` (path to a keystore decoded from a base64 secret at job start), `OMD_KEYSTORE_PASSWORD`, `OMD_KEY_ALIAS`, `OMD_KEY_PASSWORD`.
3. Build: `cd android && ./gradlew bundleRelease -Pomd.apiBaseUrl.release=https://api.your-domain.example`
   Output: `app/build/outputs/bundle/release/app-release.aab`. Without signing properties the bundle is unsigned and cannot be uploaded.
4. Enrol in Play App Signing when creating the app in Play Console and upload the AAB.

If the upload key is lost, request an upload-key reset in Play Console; never commit or email keystores.
