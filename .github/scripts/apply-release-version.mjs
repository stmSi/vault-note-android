import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseVersion } from './release-version.mjs';

function readText(file) {
  return readFileSync(file, 'utf8');
}

function writeText(file, contents) {
  writeFileSync(file, contents.endsWith('\n') ? contents : `${contents}\n`, 'utf8');
}

function replaceRequired(contents, pattern, replacement, label) {
  if (!pattern.test(contents)) {
    throw new Error(`Could not locate ${label}.`);
  }
  return contents.replace(pattern, replacement);
}

function updateJson(file, update) {
  const value = JSON.parse(readText(file));
  update(value);
  writeText(file, JSON.stringify(value, null, 2));
}

export function applyReleaseVersion(repositoryRoot, version) {
  parseVersion(version);
  const desktop = path.join(repositoryRoot, 'desktop');

  updateJson(path.join(desktop, 'package.json'), (value) => {
    if (value.name !== 'vaultnote-desktop') {
      throw new Error('Unexpected desktop package name.');
    }
    value.version = version;
  });

  updateJson(path.join(desktop, 'package-lock.json'), (value) => {
    if (value.name !== 'vaultnote-desktop' || value.packages?.['']?.name !== 'vaultnote-desktop') {
      throw new Error('Unexpected desktop package lock structure.');
    }
    value.version = version;
    value.packages[''].version = version;
  });

  updateJson(path.join(desktop, 'src-tauri', 'tauri.conf.json'), (value) => {
    if (value.productName !== 'VaultNote') {
      throw new Error('Unexpected Tauri product name.');
    }
    value.version = version;
  });

  const cargoManifestPath = path.join(desktop, 'src-tauri', 'Cargo.toml');
  const cargoManifest = replaceRequired(
    readText(cargoManifestPath),
    /(\[package\]\n(?:[^\n]*\n)*?version\s*=\s*")[^"]+("\n)/u,
    `$1${version}$2`,
    'vaultnote-desktop package version in Cargo.toml',
  );
  writeText(cargoManifestPath, cargoManifest);

  const cargoLockPath = path.join(desktop, 'src-tauri', 'Cargo.lock');
  const cargoLock = replaceRequired(
    readText(cargoLockPath),
    /(\[\[package\]\]\nname = "vaultnote-desktop"\nversion = ")[^"]+("\n)/u,
    `$1${version}$2`,
    'vaultnote-desktop package version in Cargo.lock',
  );
  writeText(cargoLockPath, cargoLock);
}

function main() {
  const version = process.argv[2];
  if (version === undefined) {
    throw new Error('Usage: node apply-release-version.mjs MAJOR.MINOR.PATCH');
  }
  const root = path.resolve(import.meta.dirname, '..', '..');
  applyReleaseVersion(root, version);
  console.log(`Applied desktop release version ${version}`);
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
