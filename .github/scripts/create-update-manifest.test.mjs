import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, it } from 'node:test';
import { createUpdateManifest } from './create-update-manifest.mjs';

const temporaryDirectories = [];

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((path) => rm(path, { recursive: true })));
});

async function updateApk() {
  const directory = await mkdtemp(join(tmpdir(), 'vaultnote-update-manifest-'));
  temporaryDirectories.push(directory);
  const apkPath = join(directory, 'VaultNote-Android.apk');
  writeFileSync(apkPath, Buffer.from('verified apk fixture'));
  return apkPath;
}

describe('Android update manifest creation', () => {
  it('creates bounded metadata for the canonical APK', async () => {
    const apkPath = await updateApk();
    const manifest = createUpdateManifest({
      apkPath,
      version: '0.2.4',
      versionCode: '100024',
      packageName: 'com.vaultnote',
      channel: 'production',
      certificateSha256: 'AA:'.repeat(31) + 'AA',
      apkSha256: 'b'.repeat(64),
    });

    assert.equal(manifest.tagName, 'v0.2.4');
    assert.equal(manifest.versionCode, 100024);
    assert.equal(manifest.asset.name, 'VaultNote-Android.apk');
    assert.equal(manifest.asset.sizeBytes, 20);
    assert.equal(manifest.certificateSha256, 'a'.repeat(64));
  });

  it('rejects invalid package, digest, channel, and version metadata', async () => {
    const apkPath = await updateApk();
    const valid = {
      apkPath,
      version: '0.2.4',
      versionCode: 100024,
      packageName: 'com.vaultnote',
      channel: 'debug',
      certificateSha256: 'a'.repeat(64),
      apkSha256: 'b'.repeat(64),
    };

    assert.throws(() => createUpdateManifest({ ...valid, packageName: '../vault' }), /packageName/u);
    assert.throws(() => createUpdateManifest({ ...valid, channel: 'nightly' }), /channel/u);
    assert.throws(() => createUpdateManifest({ ...valid, apkSha256: 'bad' }), /apkSha256/u);
    assert.throws(() => createUpdateManifest({ ...valid, version: '01.2.4' }), /version/u);
  });
});
