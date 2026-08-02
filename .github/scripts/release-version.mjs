import { appendFileSync, readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const VERSION_PATTERN = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/u;
const COMMIT_VERSION_PATTERN = /\[version:\s*((?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*))\s*\]/giu;
const MAX_ANDROID_VERSION_CODE = 2_100_000_000;
const ANDROID_VERSION_CODE_OFFSET = 100_000;

export function parseVersion(value) {
  const match = VERSION_PATTERN.exec(value.trim());
  if (match === null) {
    throw new Error(`Invalid release version "${value}". Use MAJOR.MINOR.PATCH without leading zeroes.`);
  }
  const parts = match.slice(1).map(Number);
  if (parts.some((part) => !Number.isSafeInteger(part))) {
    throw new Error(`Release version "${value}" exceeds the supported numeric range.`);
  }
  return { major: parts[0], minor: parts[1], patch: parts[2] };
}

export function formatVersion(version) {
  return `${version.major}.${version.minor}.${version.patch}`;
}

export function compareVersions(left, right) {
  return left.major - right.major || left.minor - right.minor || left.patch - right.patch;
}

export function explicitVersionFromCommit(message) {
  const versions = [...message.matchAll(COMMIT_VERSION_PATTERN)].map((match) => match[1]);
  const unique = [...new Set(versions)];
  if (unique.length > 1) {
    throw new Error(`Commit message contains conflicting release versions: ${unique.join(', ')}.`);
  }
  return unique[0] ?? '';
}

export function resolveReleaseVersion({ tags, requestedVersion = '', commitMessage = '', runNumber }) {
  const versions = tags
    .filter((tag) => tag.startsWith('v'))
    .map((tag) => {
      try {
        return { tag, version: parseVersion(tag.slice(1)) };
      } catch {
        return null;
      }
    })
    .filter((entry) => entry !== null);
  const latest = versions.reduce(
    (current, candidate) => current === null || compareVersions(candidate.version, current.version) > 0
      ? candidate
      : current,
    null,
  );

  const requested = requestedVersion.trim();
  const fromCommit = explicitVersionFromCommit(commitMessage);
  if (requested !== '' && fromCommit !== '' && requested !== fromCommit) {
    throw new Error(`Manual version ${requested} conflicts with commit version ${fromCommit}.`);
  }

  const explicit = requested || fromCommit;
  let next;
  if (explicit !== '') {
    next = parseVersion(explicit);
    if (latest !== null && compareVersions(next, latest.version) <= 0) {
      throw new Error(`Release version ${explicit} must be newer than ${formatVersion(latest.version)}.`);
    }
  } else if (latest === null) {
    next = { major: 0, minor: 0, patch: 1 };
  } else {
    if (latest.version.patch === Number.MAX_SAFE_INTEGER) {
      throw new Error('Patch version cannot be incremented safely. Set a newer minor version explicitly.');
    }
    next = { ...latest.version, patch: latest.version.patch + 1 };
  }

  const numericRunNumber = Number(runNumber);
  if (!Number.isSafeInteger(numericRunNumber) || numericRunNumber < 1) {
    throw new Error(`Invalid GitHub run number "${runNumber}".`);
  }
  const androidVersionCode = ANDROID_VERSION_CODE_OFFSET + numericRunNumber;
  if (androidVersionCode > MAX_ANDROID_VERSION_CODE) {
    throw new Error('GitHub run number exceeds Android version-code capacity.');
  }

  const version = formatVersion(next);
  return {
    androidVersionCode,
    latestTag: latest?.tag ?? '',
    source: explicit === '' ? 'automatic' : requested !== '' ? 'manual' : 'commit',
    tag: `v${version}`,
    version,
  };
}

function gitTags() {
  const tagsFile = process.env.RELEASE_TAGS_FILE;
  if (tagsFile !== undefined && tagsFile !== '') {
    return readFileSync(tagsFile, 'utf8').split('\n').map((tag) => tag.trim()).filter(Boolean);
  }
  const output = execFileSync('git', ['tag', '--list'], { encoding: 'utf8' });
  return output.split('\n').map((tag) => tag.trim()).filter(Boolean);
}

function writeOutput(name, value) {
  const outputPath = process.env.GITHUB_OUTPUT;
  if (outputPath !== undefined && outputPath !== '') {
    appendFileSync(outputPath, `${name}=${value}\n`, { encoding: 'utf8' });
  }
}

function main() {
  const result = resolveReleaseVersion({
    tags: gitTags(),
    requestedVersion: process.env.REQUESTED_VERSION ?? '',
    commitMessage: process.env.COMMIT_MESSAGE ?? '',
    runNumber: process.env.GITHUB_RUN_NUMBER ?? '',
  });
  for (const [name, value] of Object.entries({
    version: result.version,
    tag: result.tag,
    source: result.source,
    latest_tag: result.latestTag,
    android_version_code: result.androidVersionCode,
  })) {
    writeOutput(name, value);
  }
  console.log(`Resolved ${result.tag} (${result.source})`);
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
