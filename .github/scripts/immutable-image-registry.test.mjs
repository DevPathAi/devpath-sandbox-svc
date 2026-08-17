import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';

import {
  createImmutableImageEvidence,
  RegistryContractError,
  inspectImmutableTag,
} from './immutable-image-registry.mjs';

const SOURCE_SHA = 'a'.repeat(40);
const OCI_SOURCE = 'https://github.com/DevPathAi/devpath-sandbox-svc';
const REPOSITORY = 'ghcr.io/devpathai/devpath-sandbox-svc';
const SECRET = 'registry-password-must-never-leak';

function digest(body) {
  return `sha256:${createHash('sha256').update(body).digest('hex')}`;
}

function jsonResponse(body, { status = 200, headers = {} } = {}) {
  const payload = typeof body === 'string' ? body : JSON.stringify(body);
  return new Response(payload, {
    status,
    headers: {
      'content-type': 'application/json',
      'content-length': String(Buffer.byteLength(payload)),
      ...headers,
    },
  });
}

function presentSequence({
  labels,
  rootfs = { type: 'layers', diff_ids: [`sha256:${'c'.repeat(64)}`] },
  configMediaType = 'application/vnd.oci.image.config.v1+json',
  layers = [{
    mediaType: 'application/vnd.oci.image.layer.v1.tar+gzip',
    digest: `sha256:${'d'.repeat(64)}`,
    size: 1,
  }],
  manifestStatus = 200,
  configStatus = 200,
} = {}) {
  const config = JSON.stringify({
    architecture: 'amd64',
    os: 'linux',
    rootfs,
    config: {
      Labels: labels ?? {
        'org.opencontainers.image.revision': SOURCE_SHA,
        'org.opencontainers.image.source': OCI_SOURCE,
      },
    },
  });
  const configDigest = digest(config);
  const manifest = JSON.stringify({
    schemaVersion: 2,
    mediaType: 'application/vnd.oci.image.manifest.v1+json',
    config: {
      mediaType: configMediaType,
      digest: configDigest,
      size: Buffer.byteLength(config),
    },
    layers,
  });
  const imageDigest = digest(manifest);
  return {
    config,
    configDigest,
    manifest,
    imageDigest,
    responses: [
      jsonResponse({ token: 'opaque-registry-token' }),
      jsonResponse(manifest, {
        status: manifestStatus,
        headers: {
          'content-type': 'application/vnd.oci.image.manifest.v1+json',
          'docker-content-digest': imageDigest,
        },
      }),
      jsonResponse(config, { status: configStatus }),
    ],
  };
}

function sequenceFetch(responses, observed = []) {
  return async (url, init = {}) => {
    const requested = String(url);
    observed.push({ url: requested, init });
    const response = responses.shift();
    assert.ok(response, `unexpected fetch: ${url}`);
    if (!response.url) {
      Object.defineProperty(response, 'url', {
        configurable: true,
        value: requested,
      });
    }
    return response;
  };
}

function options(fetchImpl, overrides = {}) {
  return {
    repository: REPOSITORY,
    tag: SOURCE_SHA,
    sourceSha: SOURCE_SHA,
    expectedOciSource: OCI_SOURCE,
    username: 'release-bot',
    password: SECRET,
    fetchImpl,
    ...overrides,
  };
}

test('accepts an exact linux/amd64 image and returns sanitized digest evidence', async () => {
  const present = presentSequence();
  const observed = [];
  const result = await inspectImmutableTag(options(
      sequenceFetch([...present.responses], observed)));

  assert.deepEqual(result, {
    schema_version: 'devpath.immutable-image.v1',
    state: 'present',
    source_sha: SOURCE_SHA,
    image_repository: REPOSITORY,
    image_tag: SOURCE_SHA,
    image_digest: present.imageDigest,
    manifest_digest: present.imageDigest,
    config_digest: present.configDigest,
    platform: { os: 'linux', architecture: 'amd64' },
    rootfs_diff_ids: [`sha256:${'c'.repeat(64)}`],
    oci_labels: {
      'org.opencontainers.image.source': OCI_SOURCE,
      'org.opencontainers.image.revision': SOURCE_SHA,
    },
  });
  assert.match(observed[0].init.headers.authorization, /^Basic /);
  assert.equal(observed[1].init.headers.authorization, 'Bearer opaque-registry-token');
  assert.equal(observed[2].init.headers.authorization, 'Bearer opaque-registry-token');
  assert.ok(observed.every(({ init }) => init.signal instanceof AbortSignal));
  assert.equal(new Set(observed.map(({ init }) => init.signal)).size, observed.length);
  const serialized = JSON.stringify(result);
  assert.doesNotMatch(serialized, /registry-password|opaque-registry-token|authorization/i);
});

test('only an exact tag-manifest 404 is classified as absent', async (t) => {
  const absentFetch = sequenceFetch([
    jsonResponse({ token: 'opaque-registry-token' }),
    jsonResponse({
      errors: [{ code: 'MANIFEST_UNKNOWN', message: 'manifest unknown' }],
    }, { status: 404 }),
  ]);
  const absent = await inspectImmutableTag(options(absentFetch, { allowAbsent: true }));
  assert.equal(absent.state, 'absent');
  assert.equal(absent.image_digest, null);

  for (const status of [400, 401, 403, 408, 429, 500, 503]) {
    await t.test(`HTTP ${status} fails closed`, async () => {
      const fetchImpl = sequenceFetch([
        jsonResponse({ token: 'opaque-registry-token' }),
        jsonResponse({ error: 'not absent' }, { status }),
      ]);
      await assert.rejects(
          inspectImmutableTag(options(fetchImpl, { allowAbsent: true })),
          RegistryContractError);
    });
  }
});

test('404 requires the exact manifest endpoint and MANIFEST_UNKNOWN envelope', async (t) => {
  const mutations = [
    {
      name: 'wrong registry code',
      response: jsonResponse({
        errors: [{ code: 'BLOB_UNKNOWN', message: 'blob unknown' }],
      }, { status: 404 }),
      error: /MANIFEST_UNKNOWN/,
    },
    {
      name: 'non-canonical error envelope',
      response: jsonResponse({
        errors: [{ code: 'MANIFEST_UNKNOWN', message: 'manifest unknown' }],
        state: 'absent',
      }, { status: 404 }),
      error: /canonical registry error envelope/,
    },
    {
      name: 'non-canonical message',
      response: jsonResponse({
        errors: [{ code: 'MANIFEST_UNKNOWN', message: 'unknown manifest' }],
      }, { status: 404 }),
      error: /MANIFEST_UNKNOWN/,
    },
    {
      name: 'extra detail field',
      response: jsonResponse({
        errors: [{
          code: 'MANIFEST_UNKNOWN',
          message: 'manifest unknown',
          detail: { repository: 'devpath-sandbox-svc' },
        }],
      }, { status: 404 }),
      error: /MANIFEST_UNKNOWN/,
    },
    {
      name: 'wrong endpoint response',
      response: jsonResponse({
        errors: [{ code: 'MANIFEST_UNKNOWN', message: 'manifest unknown' }],
      }, { status: 404 }),
      url: 'https://ghcr.io/v2/devpathai/devpath-sandbox-svc/manifests/wrong',
      error: /response URL/,
    },
  ];
  for (const mutation of mutations) {
    await t.test(mutation.name, async () => {
      if (mutation.url) {
        Object.defineProperty(mutation.response, 'url', { value: mutation.url });
      }
      const fetchImpl = sequenceFetch([
        jsonResponse({ token: 'opaque-registry-token' }),
        mutation.response,
      ]);
      await assert.rejects(
          inspectImmutableTag(options(fetchImpl, { allowAbsent: true })),
          mutation.error);
    });
  }
});

test('a missing config blob is drift, never tag absence', async () => {
  const present = presentSequence({ configStatus: 404 });
  await assert.rejects(
      inspectImmutableTag(options(sequenceFetch([...present.responses]), {
        allowAbsent: true,
      })),
      /config blob.*HTTP 404/);
});

test('config redirects are one-hop, host-bound, and never forward authorization', async (t) => {
  const present = presentSequence();
  const trustedLocation = 'https://pkg-containers.githubusercontent.com/ghcr1/config?sig=safe';
  const observed = [];
  const redirected = await inspectImmutableTag(options(sequenceFetch([
    present.responses[0],
    present.responses[1],
    new Response(null, { status: 307, headers: {
      'content-length': '0',
      location: trustedLocation,
    } }),
    jsonResponse(present.config),
  ], observed)));
  assert.equal(redirected.config_digest, present.configDigest);
  assert.equal(observed[2].init.headers.authorization, 'Bearer opaque-registry-token');
  assert.equal(observed[3].url, trustedLocation);
  assert.equal(observed[3].init.redirect, 'error');
  assert.equal(Object.hasOwn(observed[3].init.headers, 'authorization'), false);
  assert.notEqual(observed[2].init.signal, observed[3].init.signal);

  const redirectMutations = [
    {
      name: 'evil host',
      first: new Response(null, { status: 307, headers: {
        'content-length': '0',
        location: 'https://attacker.invalid/config',
      } }),
      error: /exact trusted package host/,
    },
    {
      name: 'userinfo',
      first: new Response(null, { status: 307, headers: {
        'content-length': '0',
        location: 'https://user:password@pkg-containers.githubusercontent.com/config',
      } }),
      error: /exact trusted package host/,
    },
    {
      name: 'unsupported 302',
      first: new Response(null, { status: 302, headers: {
        'content-length': '0',
        location: trustedLocation,
      } }),
      error: /HTTP 302/,
    },
    {
      name: 'second redirect',
      first: new Response(null, { status: 307, headers: {
        'content-length': '0',
        location: trustedLocation,
      } }),
      second: new Response(null, { status: 307, headers: {
        'content-length': '0',
        location: trustedLocation,
      } }),
      error: /config blob.*HTTP 307/,
    },
    {
      name: 'non-empty redirect body',
      first: new Response('redirect body', { status: 307, headers: {
        'content-length': String(Buffer.byteLength('redirect body')),
        location: trustedLocation,
      } }),
      error: /descriptor size/,
    },
  ];
  for (const mutation of redirectMutations) {
    await t.test(mutation.name, async () => {
      const fresh = presentSequence();
      const responses = [fresh.responses[0], fresh.responses[1], mutation.first];
      if (mutation.second) responses.push(mutation.second);
      await assert.rejects(
          inspectImmutableTag(options(sequenceFetch(responses))),
          mutation.error);
    });
  }
});

test('token endpoint response URL is exact', async () => {
  const token = jsonResponse({ token: 'opaque-registry-token' });
  Object.defineProperty(token, 'url', { value: 'https://ghcr.io/token?scope=wrong' });
  await assert.rejects(
      inspectImmutableTag(options(sequenceFetch([token]))),
      /token endpoint response URL/);
});

test('every registry hop has a bounded abort signal', async () => {
  let observedSignal;
  const hangingFetch = async (_url, init) => {
    observedSignal = init.signal;
    return new Promise((_resolve, reject) => {
      init.signal.addEventListener('abort', () => reject(init.signal.reason), {
        once: true,
      });
    });
  };
  await assert.rejects(
      inspectImmutableTag(options(hangingFetch, { requestTimeoutMs: 5 })),
      /registry token endpoint timed out/);
  assert.ok(observedSignal instanceof AbortSignal);
  assert.equal(observedSignal.aborted, true);
});

test('all response bodies are length-bound and streamed fail-closed', async (t) => {
  const tokenWithoutLength = new Response(JSON.stringify({ token: 'token' }), {
    headers: { 'content-type': 'application/json' },
  });
  await assert.rejects(
      inspectImmutableTag(options(sequenceFetch([tokenWithoutLength]))),
      /Content-Length is missing/);

  const tokenWithOversizedDeclaration = new Response(JSON.stringify({ token: 'token' }), {
    headers: {
      'content-type': 'application/json',
      'content-length': String(64 * 1024 + 1),
    },
  });
  await assert.rejects(
      inspectImmutableTag(options(sequenceFetch([tokenWithOversizedDeclaration]))),
      /Content-Length exceeds/);

  const mutations = [
    {
      name: 'missing manifest Content-Length',
      response(present) {
        return new Response(present.manifest, { headers: {
          'content-type': 'application/vnd.oci.image.manifest.v1+json',
          'docker-content-digest': present.imageDigest,
        } });
      },
      error: /Content-Length is missing/,
    },
    {
      name: 'oversized declared manifest',
      response(present) {
        return new Response(present.manifest, { headers: {
          'content-type': 'application/vnd.oci.image.manifest.v1+json',
          'content-length': String(1024 * 1024 + 1),
          'docker-content-digest': present.imageDigest,
        } });
      },
      error: /Content-Length exceeds/,
    },
    {
      name: 'body exceeds declared manifest length',
      response(present) {
        return new Response(present.manifest, { headers: {
          'content-type': 'application/vnd.oci.image.manifest.v1+json',
          'content-length': '1',
          'docker-content-digest': present.imageDigest,
        } });
      },
      error: /exceeds its declared/,
    },
  ];
  for (const mutation of mutations) {
    await t.test(mutation.name, async () => {
      const present = presentSequence();
      const fetchImpl = sequenceFetch([
        present.responses[0],
        mutation.response(present),
      ]);
      await assert.rejects(inspectImmutableTag(options(fetchImpl)), mutation.error);
    });
  }

  const truncatedManifest = presentSequence();
  truncatedManifest.responses[1] = new Response(
      truncatedManifest.manifest.slice(0, -1), {
        headers: {
          'content-type': 'application/vnd.oci.image.manifest.v1+json',
          'content-length': String(Buffer.byteLength(truncatedManifest.manifest)),
          'docker-content-digest': truncatedManifest.imageDigest,
        },
      });
  await assert.rejects(
      inspectImmutableTag(options(sequenceFetch([...truncatedManifest.responses]))),
      /truncated relative to Content-Length/);

  const truncatedConfig = presentSequence();
  truncatedConfig.responses[2] = new Response(truncatedConfig.config.slice(0, -1), {
    headers: {
      'content-type': 'application/json',
      'content-length': String(Buffer.byteLength(truncatedConfig.config)),
    },
  });
  await assert.rejects(
      inspectImmutableTag(options(sequenceFetch([...truncatedConfig.responses]))),
      /truncated relative to Content-Length/);

  const absentBody = JSON.stringify({
    errors: [{ code: 'MANIFEST_UNKNOWN', message: 'manifest unknown' }],
  });
  const truncatedAbsent = new Response(absentBody.slice(0, -1), {
    status: 404,
    headers: {
      'content-type': 'application/json',
      'content-length': String(Buffer.byteLength(absentBody)),
    },
  });
  await assert.rejects(
      inspectImmutableTag(options(sequenceFetch([
        jsonResponse({ token: 'opaque-registry-token' }),
        truncatedAbsent,
      ]), { allowAbsent: true })),
      /truncated relative to Content-Length/);

  const compressed = presentSequence();
  compressed.responses[1].headers.set('content-encoding', 'gzip');
  await assert.rejects(
      inspectImmutableTag(options(sequenceFetch([...compressed.responses]))),
      /content encoding must be identity/);

  const invalidUtf8 = new Response(Uint8Array.from([0xff]), {
    headers: {
      'content-type': 'application/json',
      'content-length': '1',
    },
  });
  await assert.rejects(
      inspectImmutableTag(options(sequenceFetch([invalidUtf8]))),
      /not valid UTF-8 JSON/);
});

test('digest and OCI source-label mutations fail closed', async (t) => {
  const mutations = [
    {
      name: 'revision drift',
      labels: {
        'org.opencontainers.image.revision': 'b'.repeat(40),
        'org.opencontainers.image.source': OCI_SOURCE,
      },
      error: /OCI revision/,
    },
    {
      name: 'source drift',
      labels: {
        'org.opencontainers.image.revision': SOURCE_SHA,
        'org.opencontainers.image.source': 'https://github.com/attacker/repository',
      },
      error: /OCI source/,
    },
  ];
  for (const mutation of mutations) {
    await t.test(mutation.name, async () => {
      const present = presentSequence({ labels: mutation.labels });
      await assert.rejects(
          inspectImmutableTag(options(sequenceFetch([...present.responses]))),
          mutation.error);
    });
  }

  const present = presentSequence();
  present.responses[1].headers.set('docker-content-digest', `sha256:${'0'.repeat(64)}`);
  await assert.rejects(
      inspectImmutableTag(options(sequenceFetch([...present.responses]))),
      /digest header does not match/);

  const malformedRootfs = presentSequence({ rootfs: { type: 'layers', diff_ids: [] } });
  await assert.rejects(
      inspectImmutableTag(options(sequenceFetch([...malformedRootfs.responses]))),
      /rootfs diff IDs/);

  const duplicateRootfs = presentSequence({
    rootfs: {
      type: 'layers',
      diff_ids: [
        `sha256:${'c'.repeat(64)}`,
        `sha256:${'c'.repeat(64)}`,
      ],
    },
    layers: [
      {
        mediaType: 'application/vnd.oci.image.layer.v1.tar+gzip',
        digest: `sha256:${'d'.repeat(64)}`,
        size: 1,
      },
      {
        mediaType: 'application/vnd.oci.image.layer.v1.tar+gzip',
        digest: `sha256:${'e'.repeat(64)}`,
        size: 1,
      },
    ],
  });
  await assert.rejects(
      inspectImmutableTag(options(sequenceFetch([...duplicateRootfs.responses]))),
      /rootfs diff IDs must be unique/);
});

test('config and layer descriptors are exact deployable media', async (t) => {
  const mutations = [
    {
      name: 'unsupported config media type',
      present: presentSequence({ configMediaType: 'application/json' }),
      error: /config descriptor media type/,
    },
    {
      name: 'unsupported layer media type',
      present: presentSequence({ layers: [{
        mediaType: 'application/octet-stream',
        digest: `sha256:${'d'.repeat(64)}`,
        size: 1,
      }] }),
      error: /layer media type/,
    },
    {
      name: 'external layer URL',
      present: presentSequence({ layers: [{
        mediaType: 'application/vnd.oci.image.layer.v1.tar+gzip',
        digest: `sha256:${'d'.repeat(64)}`,
        size: 1,
        urls: ['https://attacker.invalid/layer'],
      }] }),
      error: /external URLs/,
    },
  ];
  for (const mutation of mutations) {
    await t.test(mutation.name, async () => {
      await assert.rejects(
          inspectImmutableTag(options(sequenceFetch([...mutation.present.responses]))),
          mutation.error);
    });
  }
});

test('an OCI index must contain exactly one exact linux/amd64 image', async (t) => {
  const config = JSON.stringify({
    architecture: 'amd64',
    os: 'linux',
    rootfs: { type: 'layers', diff_ids: [`sha256:${'c'.repeat(64)}`] },
    config: { Labels: {
      'org.opencontainers.image.revision': SOURCE_SHA,
      'org.opencontainers.image.source': OCI_SOURCE,
    } },
  });
  const configDigest = digest(config);
  const child = JSON.stringify({
    schemaVersion: 2,
    mediaType: 'application/vnd.oci.image.manifest.v1+json',
    config: {
      mediaType: 'application/vnd.oci.image.config.v1+json',
      digest: configDigest,
      size: Buffer.byteLength(config),
    },
    layers: [{
      mediaType: 'application/vnd.oci.image.layer.v1.tar+gzip',
      digest: `sha256:${'d'.repeat(64)}`,
      size: 1,
    }],
  });
  const childDigest = digest(child);
  const index = JSON.stringify({
    schemaVersion: 2,
    mediaType: 'application/vnd.oci.image.index.v1+json',
    manifests: [
      {
        mediaType: 'application/vnd.oci.image.manifest.v1+json',
        digest: childDigest,
        size: Buffer.byteLength(child),
        platform: { os: 'linux', architecture: 'amd64' },
      },
    ],
  });
  const indexDigest = digest(index);
  const fetchImpl = sequenceFetch([
    jsonResponse({ token: 'opaque-registry-token' }),
    jsonResponse(index, { headers: {
      'content-type': 'application/vnd.oci.image.index.v1+json',
      'docker-content-digest': indexDigest,
    } }),
    jsonResponse(child, { headers: {
      'content-type': 'application/vnd.oci.image.manifest.v1+json',
      'docker-content-digest': childDigest,
    } }),
    jsonResponse(config),
  ]);

  const result = await inspectImmutableTag(options(fetchImpl));
  assert.equal(result.image_digest, indexDigest);
  assert.equal(result.manifest_digest, childDigest);

  const mutatedIndex = JSON.parse(index);
  mutatedIndex.manifests.push({
    ...mutatedIndex.manifests[0],
    platform: { os: 'linux', architecture: 'arm64' },
  });
  const mutatedBody = JSON.stringify(mutatedIndex);
  const rejectedFetch = sequenceFetch([
    jsonResponse({ token: 'opaque-registry-token' }),
    jsonResponse(mutatedBody, { headers: {
      'content-type': 'application/vnd.oci.image.index.v1+json',
      'docker-content-digest': digest(mutatedBody),
    } }),
  ]);
  await assert.rejects(
      inspectImmutableTag(options(rejectedFetch)),
      /unexpected runnable platform/);

  for (const extraKey of ['variant', 'os.version']) {
    await t.test(`rejects extra platform key ${extraKey}`, async () => {
      const extraPlatformIndex = JSON.parse(index);
      extraPlatformIndex.manifests[0].platform[extraKey] = 'unexpected';
      const extraPlatformBody = JSON.stringify(extraPlatformIndex);
      const extraPlatformFetch = sequenceFetch([
        jsonResponse({ token: 'opaque-registry-token' }),
        jsonResponse(extraPlatformBody, { headers: {
          'content-type': 'application/vnd.oci.image.index.v1+json',
          'docker-content-digest': digest(extraPlatformBody),
        } }),
      ]);
      await assert.rejects(
          inspectImmutableTag(options(extraPlatformFetch)),
          /platform must contain only os and architecture/);
    });
  }
});

test('evidence binds the exact producer without credentials or mutable fields', async () => {
  const present = presentSequence();
  const result = await inspectImmutableTag(options(
      sequenceFetch([...present.responses])));
  const evidence = createImmutableImageEvidence(result, {
    repository: 'DevPathAi/devpath-sandbox-svc',
    workflowPath: '.github/workflows/ci.yml',
    workflowSha256: 'e'.repeat(64),
    runId: '123456789',
    runAttempt: '1',
  });

  assert.deepEqual(Object.keys(evidence), [
    'schema_version',
    'status',
    'repository',
    'source_sha',
    'image_repository',
    'image_digest',
    'manifest_digest',
    'config_digest',
    'platform',
    'rootfs_diff_ids',
    'oci_labels',
    'producer_workflow_path',
    'producer_workflow_sha256',
    'producer_run_id',
    'producer_run_attempt',
  ]);
  assert.equal(evidence.status, 'passed');
  assert.equal(evidence.repository, 'DevPathAi/devpath-sandbox-svc');
  assert.equal(evidence.producer_run_id, 123456789);
  assert.equal(evidence.producer_run_attempt, 1);
  assert.deepEqual(Object.keys(evidence.platform), ['os', 'architecture']);
  assert.deepEqual(Object.keys(evidence.oci_labels), [
    'org.opencontainers.image.source',
    'org.opencontainers.image.revision',
  ]);
  assert.doesNotMatch(JSON.stringify(evidence), /password|token|authorization/i);

  for (const producer of [
    { repository: 'DevPathAi/other' },
    { workflowPath: '.github/workflows/other.yml' },
    { workflowSha256: 'not-a-digest' },
    { runId: '0' },
    { runId: true },
    { runId: String(Number.MAX_SAFE_INTEGER + 1) },
    { runAttempt: '2' },
    { runAttempt: false },
  ]) {
    await assert.throws(() => createImmutableImageEvidence(result, {
      repository: 'DevPathAi/devpath-sandbox-svc',
      workflowPath: '.github/workflows/ci.yml',
      workflowSha256: 'e'.repeat(64),
      runId: '123456789',
      runAttempt: '1',
      ...producer,
    }), RegistryContractError);
  }
});

test('rootfs identity is an explicit compare-and-swap fence', async () => {
  const present = presentSequence();
  await assert.rejects(
      inspectImmutableTag(options(sequenceFetch([...present.responses]), {
        expectedRootfsDiffIds: [`sha256:${'f'.repeat(64)}`],
      })),
      /expected rootfs diff IDs/);
});

test('an expected digest is a compare-and-swap fence', async () => {
  const present = presentSequence();
  await assert.rejects(
      inspectImmutableTag(options(sequenceFetch([...present.responses]), {
        expectedImageDigest: `sha256:${'f'.repeat(64)}`,
      })),
      /expected image digest/);
});

test('expected manifest and config digests are compare-and-swap fences', async (t) => {
  for (const [name, option, error] of [
    [
      'manifest',
      { expectedManifestDigest: `sha256:${'f'.repeat(64)}` },
      /expected manifest digest/,
    ],
    [
      'config',
      { expectedConfigDigest: `sha256:${'f'.repeat(64)}` },
      /expected config digest/,
    ],
  ]) {
    await t.test(name, async () => {
      const present = presentSequence();
      await assert.rejects(
          inspectImmutableTag(options(sequenceFetch([...present.responses]), option)),
          error);
    });
  }
});
