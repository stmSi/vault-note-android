# VaultNote releases

Every push to `main` or `master` runs the Android and desktop checks, builds the distributable files, verifies their SHA-256 checksums, and creates a GitHub release.

The first release is `v0.0.1`. Later automatic releases increment the patch number. To start a new release line, use either:

- A commit message containing `[version: 0.1.0]`.
- **Actions → Test, build, and release → Run workflow**, with `0.1.0` in the version field.

The explicit version must be greater than every existing `vMAJOR.MINOR.PATCH` tag. After `v0.1.0`, the next automatic release is `v0.1.1`.

## Android signing

Configure all four repository Actions secrets to publish APKs signed by a stable production key:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded JKS or PKCS12 keystore contents.
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Keep the keystore and its passwords in a separate secure backup. Losing the key prevents future APKs from upgrading an installed production build.

If no signing secrets are configured, the workflow succeeds but labels release APKs as unsigned and also publishes an installable debug APK. A partially configured signing secret set fails closed.

The stable `VaultNote-Android.apk` release asset is the production-signed universal APK when signing is configured and the installable debug APK otherwise. Stable asset names keep the `/releases/latest/download/...` links in the root README valid across versions.
