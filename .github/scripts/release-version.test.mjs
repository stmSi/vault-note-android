import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  explicitVersionFromCommit,
  resolveReleaseVersion,
} from './release-version.mjs';

describe('release version resolution', () => {
  it('starts a repository without release tags at 0.0.1', () => {
    const result = resolveReleaseVersion({ tags: [], runNumber: 1 });
    assert.equal(result.version, '0.0.1');
    assert.equal(result.androidVersionCode, 100_001);
    assert.equal(result.source, 'automatic');
  });

  it('increments the patch component of the highest semantic release tag', () => {
    const result = resolveReleaseVersion({
      tags: ['desktop-0.5.3', 'v0.0.8', 'v0.1.0', 'v0.0.12'],
      runNumber: 9,
    });
    assert.equal(result.version, '0.1.1');
    assert.equal(result.latestTag, 'v0.1.0');
  });

  it('accepts an explicit version from the commit message', () => {
    const result = resolveReleaseVersion({
      tags: ['v0.0.9'],
      commitMessage: 'feat: sync polish [version: 0.1.0]',
      runNumber: 10,
    });
    assert.equal(result.version, '0.1.0');
    assert.equal(result.source, 'commit');
  });

  it('accepts the manual workflow version when it agrees with the commit', () => {
    const result = resolveReleaseVersion({
      tags: ['v0.1.0'],
      requestedVersion: '0.2.0',
      commitMessage: '[version: 0.2.0]',
      runNumber: 11,
    });
    assert.equal(result.version, '0.2.0');
    assert.equal(result.source, 'manual');
  });

  it('rejects downgrades and conflicting explicit versions', () => {
    assert.throws(
      () => resolveReleaseVersion({ tags: ['v0.1.2'], requestedVersion: '0.1.0', runNumber: 12 }),
      /must be newer/u,
    );
    assert.throws(
      () => resolveReleaseVersion({
        tags: [],
        requestedVersion: '0.2.0',
        commitMessage: '[version: 0.3.0]',
        runNumber: 12,
      }),
      /conflicts/u,
    );
  });

  it('rejects ambiguous commit directives and leading zeroes', () => {
    assert.throws(
      () => explicitVersionFromCommit('[version: 0.2.0] [version: 0.3.0]'),
      /conflicting/u,
    );
    assert.throws(
      () => resolveReleaseVersion({ tags: [], requestedVersion: '0.01.0', runNumber: 13 }),
      /Invalid release version/u,
    );
  });
});
