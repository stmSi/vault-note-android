import { statSync, writeFileSync } from 'node:fs';
import { basename } from 'node:path';
import { pathToFileURL } from 'node:url';

const VERSION_PATTERN = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/u;
const PACKAGE_PATTERN = /^[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+$/u;
const SHA256_PATTERN = /^[a-f0-9]{64}$/u;
const MAX_ANDROID_VERSION_CODE = 2_100_000_000;
const MAX_APK_BYTES = 200 * 1024 * 1024;

function normalizedDigest(value, field) {
  const digest = value.replaceAll(':', '').trim().toLowerCase();
  if (!SHA256_PATTERN.test(digest)) {
    throw new Error(`${field} must be a 64-character SHA-256 digest.`);
  }
  return digest;
}

export function createUpdateManifest({
  apkPath,
  version,
  versionCode,
  packageName,
  channel,
  certificateSha256,
  apkSha256,
}) {
  if (!VERSION_PATTERN.test(version)) {
    throw new Error('version must use MAJOR.MINOR.PATCH without leading zeroes.');
  }
  const numericVersionCode = Number(versionCode);
  if (
    !Number.isSafeInteger(numericVersionCode) ||
    numericVersionCode < 1 ||
    numericVersionCode > MAX_ANDROID_VERSION_CODE
  ) {
    throw new Error('versionCode is outside the supported Android range.');
  }
  if (!PACKAGE_PATTERN.test(packageName)) {
    throw new Error('packageName is not a valid Android application ID.');
  }
  if (channel !== 'debug' && channel !== 'production') {
    throw new Error('channel must be debug or production.');
  }

  const assetName = basename(apkPath);
  if (assetName !== 'VaultNote-Android.apk') {
    throw new Error('The canonical update asset must be VaultNote-Android.apk.');
  }
  const sizeBytes = statSync(apkPath).size;
  if (sizeBytes < 1 || sizeBytes > MAX_APK_BYTES) {
    throw new Error('APK size is outside the supported update range.');
  }

  return {
    schemaVersion: 1,
    tagName: `v${version}`,
    versionName: version,
    versionCode: numericVersionCode,
    packageName,
    channel,
    certificateSha256: normalizedDigest(certificateSha256, 'certificateSha256'),
    asset: {
      name: assetName,
      sizeBytes,
      sha256: normalizedDigest(apkSha256, 'apkSha256'),
    },
  };
}

function main() {
  const [apkPath, outputPath, version, versionCode, packageName, channel, certificate, apkDigest] =
    process.argv.slice(2);
  if ([apkPath, outputPath, version, versionCode, packageName, channel, certificate, apkDigest]
    .some((value) => value === undefined || value === '')) {
    throw new Error(
      'Usage: create-update-manifest APK OUTPUT VERSION VERSION_CODE PACKAGE CHANNEL CERT_SHA APK_SHA',
    );
  }
  const manifest = createUpdateManifest({
    apkPath,
    version,
    versionCode,
    packageName,
    channel,
    certificateSha256: certificate,
    apkSha256: apkDigest,
  });
  writeFileSync(outputPath, `${JSON.stringify(manifest, null, 2)}\n`, { encoding: 'utf8' });
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
