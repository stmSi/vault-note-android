import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import { applyReleaseVersion } from './apply-release-version.mjs';

function write(file, value) {
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, value, 'utf8');
}

describe('desktop release version application', () => {
  it('updates every desktop package metadata source consistently', () => {
    const root = mkdtempSync(path.join(tmpdir(), 'vaultnote-release-'));
    const desktop = path.join(root, 'desktop');
    write(path.join(desktop, 'package.json'), '{"name":"vaultnote-desktop","version":"0.5.3"}');
    write(
      path.join(desktop, 'package-lock.json'),
      '{"name":"vaultnote-desktop","version":"0.5.3","packages":{"":{"name":"vaultnote-desktop","version":"0.5.3"}}}',
    );
    write(path.join(desktop, 'src-tauri', 'tauri.conf.json'), '{"productName":"VaultNote","version":"0.5.3"}');
    write(path.join(desktop, 'src-tauri', 'Cargo.toml'), '[package]\nname = "vaultnote-desktop"\nversion = "0.5.3"\n\n[dependencies]\n');
    write(
      path.join(desktop, 'src-tauri', 'Cargo.lock'),
      'version = 4\n\n[[package]]\nname = "dependency"\nversion = "0.5.3"\n\n[[package]]\nname = "vaultnote-desktop"\nversion = "0.5.3"\n',
    );

    applyReleaseVersion(root, '0.6.0');

    assert.equal(JSON.parse(readFileSync(path.join(desktop, 'package.json'))).version, '0.6.0');
    const packageLock = JSON.parse(readFileSync(path.join(desktop, 'package-lock.json')));
    assert.equal(packageLock.version, '0.6.0');
    assert.equal(packageLock.packages[''].version, '0.6.0');
    assert.equal(JSON.parse(readFileSync(path.join(desktop, 'src-tauri', 'tauri.conf.json'))).version, '0.6.0');
    assert.match(readFileSync(path.join(desktop, 'src-tauri', 'Cargo.toml'), 'utf8'), /version = "0\.6\.0"/u);
    const cargoLock = readFileSync(path.join(desktop, 'src-tauri', 'Cargo.lock'), 'utf8');
    assert.match(cargoLock, /name = "vaultnote-desktop"\nversion = "0\.6\.0"/u);
    assert.match(cargoLock, /name = "dependency"\nversion = "0\.5\.3"/u);
  });

  it('rejects malformed semantic versions before changing files', () => {
    assert.throws(() => applyReleaseVersion('/unused', '1.02.3'), /Invalid release version/u);
  });
});
