#!/usr/bin/env node

import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { TextDecoder } from 'node:util';

const SHA = /^[0-9a-f]{40}$/;
const DIGEST = /^sha256:[0-9a-f]{64}$/;
const SHA256_HEX = /^[0-9a-f]{64}$/;
const DECIMAL_ID = /^[1-9][0-9]*$/;
const CANONICAL_SOURCE = 'https://github.com/DevPathAi/devpath-sandbox-svc';
const CANONICAL_REPOSITORY = 'DevPathAi/devpath-sandbox-svc';
const CANONICAL_IMAGE = 'ghcr.io/devpathai/devpath-sandbox-svc';
const PRODUCER_WORKFLOW_PATH = '.github/workflows/ci.yml';
const MANIFEST_MEDIA_TYPES = new Set([
  'application/vnd.oci.image.manifest.v1+json',
  'application/vnd.docker.distribution.manifest.v2+json',
]);
const INDEX_MEDIA_TYPES = new Set([
  'application/vnd.oci.image.index.v1+json',
  'application/vnd.docker.distribution.manifest.list.v2+json',
]);
const CONFIG_MEDIA_TYPES = new Set([
  'application/vnd.oci.image.config.v1+json',
  'application/vnd.docker.container.image.v1+json',
]);
const LAYER_MEDIA_TYPES = new Set([
  'application/vnd.oci.image.layer.v1.tar',
  'application/vnd.oci.image.layer.v1.tar+gzip',
  'application/vnd.oci.image.layer.v1.tar+zstd',
  'application/vnd.docker.image.rootfs.diff.tar.gzip',
]);
const ACCEPT = [...INDEX_MEDIA_TYPES, ...MANIFEST_MEDIA_TYPES].join(', ');
const TOKEN_MAX_BYTES = 64 * 1024;
const ERROR_MAX_BYTES = 64 * 1024;
const MANIFEST_MAX_BYTES = 1024 * 1024;
const CONFIG_MAX_BYTES = 4 * 1024 * 1024;
const REQUEST_TIMEOUT_MS = 30 * 1000;

export class RegistryContractError extends Error {}

function fail(message) {
  throw new RegistryContractError(message);
}

function exactString(value, label) {
  if (typeof value !== 'string' || value.length === 0) {
    fail(`${label} is required`);
  }
  return value;
}

function positiveSafeInteger(value, label) {
  if (typeof value === 'boolean') fail(`${label} is malformed`);
  const normalized = typeof value === 'number' ? String(value) : value;
  if (!DECIMAL_ID.test(normalized ?? '')) fail(`${label} is malformed`);
  const parsed = Number(normalized);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) fail(`${label} is malformed`);
  return parsed;
}

function validateDigest(value, label) {
  if (!DIGEST.test(value ?? '')) fail(`${label} is not an exact sha256 digest`);
  return value;
}

function sha256(body) {
  return `sha256:${createHash('sha256').update(body).digest('hex')}`;
}

function mediaType(response, document, label) {
  const header = (response.headers.get('content-type') ?? '').split(';', 1)[0].trim();
  const embedded = typeof document.mediaType === 'string' ? document.mediaType : '';
  const selected = embedded || header;
  if (embedded && header && embedded !== header) {
    fail(`${label} media type header does not match document`);
  }
  return selected;
}

function parseJson(body, label) {
  try {
    const text = new TextDecoder('utf-8', { fatal: true }).decode(body);
    return JSON.parse(text);
  } catch {
    fail(`${label} is not valid UTF-8 JSON`);
  }
}

async function timedFetch(fetchImpl, endpoint, init, timeoutMs, label) {
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  let response;
  try {
    response = await fetchImpl(endpoint, { ...init, signal: controller.signal });
  } catch {
    clearTimeout(timer);
    if (timedOut) fail(`${label} timed out`);
    fail(`${label} request failed`);
  }
  return {
    response,
    async consume(operation) {
      try {
        return await operation(response);
      } catch (error) {
        if (timedOut) fail(`${label} timed out`);
        throw error;
      } finally {
        clearTimeout(timer);
      }
    },
  };
}

async function responseBytes(response, label, {
  expectedStatus = 200,
  maxBytes,
  expectedBytes,
} = {}) {
  if (response.status !== expectedStatus) {
    fail(`${label} returned HTTP ${response.status}`);
  }
  if (!Number.isSafeInteger(maxBytes) || maxBytes <= 0) {
    fail(`${label} byte limit is invalid`);
  }
  const encoding = response.headers.get('content-encoding');
  if (encoding !== null && encoding !== 'identity') {
    fail(`${label} content encoding must be identity`);
  }
  const rawLength = response.headers.get('content-length');
  if (!/^(0|[1-9][0-9]*)$/.test(rawLength ?? '')) {
    fail(`${label} Content-Length is missing or malformed`);
  }
  const declaredLength = Number(rawLength);
  if (!Number.isSafeInteger(declaredLength) || declaredLength > maxBytes) {
    fail(`${label} Content-Length exceeds the byte limit`);
  }
  if (expectedBytes !== undefined && declaredLength !== expectedBytes) {
    fail(`${label} Content-Length does not match the descriptor size`);
  }
  if (declaredLength === 0 && response.body === null) {
    return Buffer.alloc(0);
  }
  if (!response.body || typeof response.body.getReader !== 'function') {
    fail(`${label} response body is unavailable`);
  }
  const reader = response.body.getReader();
  const body = Buffer.allocUnsafe(declaredLength);
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    if (!(value instanceof Uint8Array) || value.byteLength === 0) {
      await reader.cancel();
      fail(`${label} response body contains a malformed stream chunk`);
    }
    const nextTotal = total + value.byteLength;
    if (nextTotal > maxBytes || nextTotal > declaredLength) {
      await reader.cancel();
      fail(`${label} response body exceeds its declared or allowed size`);
    }
    Buffer.from(value).copy(body, total);
    total = nextTotal;
  }
  if (total !== declaredLength) {
    fail(`${label} response body is truncated relative to Content-Length`);
  }
  if (expectedBytes !== undefined && total !== expectedBytes) {
    fail(`${label} response body does not match the descriptor size`);
  }
  return body;
}

function bindResponseUrl(response, expected, label) {
  let actual;
  try {
    actual = new URL(response.url).href;
  } catch {
    fail(`${label} response URL is missing or invalid`);
  }
  if (actual !== new URL(expected).href) {
    fail(`${label} response URL does not match the requested endpoint`);
  }
}

function splitRepository(repository) {
  const match = /^ghcr\.io\/([a-z0-9]+(?:[._-][a-z0-9]+)*(?:\/[a-z0-9]+(?:[._-][a-z0-9]+)*)+)$/.exec(
      repository ?? '');
  if (!match) fail('image repository must be a lowercase ghcr.io repository');
  return match[1];
}

async function registryToken({ repositoryPath, username, password, fetchImpl }) {
  const endpoint = new URL('https://ghcr.io/token');
  endpoint.searchParams.set('service', 'ghcr.io');
  endpoint.searchParams.set('scope', `repository:${repositoryPath}:pull`);
  const authorization = Buffer.from(`${username}:${password}`).toString('base64');
  const request = await fetchImpl(endpoint, {
    method: 'GET',
    redirect: 'error',
    headers: {
      accept: 'application/json',
      'accept-encoding': 'identity',
      authorization: `Basic ${authorization}`,
    },
  }, 'registry token endpoint');
  return request.consume(async (response) => {
    bindResponseUrl(response, endpoint, 'registry token endpoint');
    const body = await responseBytes(response, 'registry token endpoint', {
      maxBytes: TOKEN_MAX_BYTES,
    });
    const document = parseJson(body, 'registry token response');
    const token = document.token ?? document.access_token;
    return exactString(token, 'registry bearer token');
  });
}

async function registryGet({
  repositoryPath,
  reference,
  kind,
  token,
  fetchImpl,
}) {
  if (kind === 'manifests') {
    if (!SHA.test(reference) && !DIGEST.test(reference)) {
      fail('manifest reference is not an exact source SHA or content digest');
    }
  } else if (kind === 'blobs') {
    validateDigest(reference, 'blob reference');
  } else {
    fail('unsupported registry object kind');
  }
  const endpoint = `https://ghcr.io/v2/${repositoryPath}/${kind}/${reference}`;
  const request = await fetchImpl(endpoint, {
    method: 'GET',
    redirect: kind === 'blobs' ? 'manual' : 'error',
    headers: {
      accept: kind === 'manifests' ? ACCEPT : 'application/octet-stream',
      'accept-encoding': 'identity',
      authorization: `Bearer ${token}`,
    },
  }, `${kind} endpoint`);
  return {
    endpoint,
    consume(operation) {
      return request.consume(async (response) => {
        bindResponseUrl(response, endpoint, `${kind} endpoint`);
        return operation(response, endpoint);
      });
    },
  };
}

async function readBlob({
  repositoryPath,
  digest,
  expectedSize,
  token,
  fetchImpl,
}) {
  if (!Number.isSafeInteger(expectedSize) || expectedSize <= 0
      || expectedSize > CONFIG_MAX_BYTES) {
    fail('config blob descriptor size is missing or invalid');
  }
  const initial = await registryGet({
    repositoryPath,
    reference: digest,
    kind: 'blobs',
    token,
    fetchImpl,
  });
  const initialResult = await initial.consume(async (response) => {
    if (response.status === 307) {
      await responseBytes(response, 'config blob redirect', {
        expectedStatus: 307,
        maxBytes: ERROR_MAX_BYTES,
        expectedBytes: 0,
      });
      const location = response.headers.get('location');
      if (typeof location !== 'string' || !location.startsWith('https://')) {
        fail('config blob redirect Location must be an absolute HTTPS URL');
      }
      let target;
      try {
        target = new URL(location);
      } catch {
        fail('config blob redirect Location is invalid');
      }
      if (target.protocol !== 'https:'
          || target.hostname !== 'pkg-containers.githubusercontent.com'
          || target.port !== ''
          || target.username !== ''
          || target.password !== ''
          || target.hash !== '') {
        fail('config blob redirect target is not the exact trusted package host');
      }
      return { target };
    }
    const body = await responseBytes(response, 'config blob', {
      maxBytes: CONFIG_MAX_BYTES,
      expectedBytes: expectedSize,
    });
    return { body };
  });
  let body = initialResult.body;
  if (initialResult.target) {
    const redirected = await fetchImpl(initialResult.target.href, {
      method: 'GET',
      redirect: 'error',
      headers: {
        accept: 'application/octet-stream',
        'accept-encoding': 'identity',
      },
    }, 'redirected config blob');
    body = await redirected.consume(async (response) => {
      bindResponseUrl(response, initialResult.target, 'redirected config blob');
      return responseBytes(response, 'config blob', {
        maxBytes: CONFIG_MAX_BYTES,
        expectedBytes: expectedSize,
      });
    });
  }
  if (sha256(body) !== digest) fail('config blob bytes do not match its digest');
  return body;
}

async function requireManifestUnknown(response) {
  const contentType = (response.headers.get('content-type') ?? '')
      .split(';', 1)[0].trim();
  if (contentType !== 'application/json') {
    fail('404 tag response is not a canonical JSON registry error');
  }
  const document = parseJson(await responseBytes(response, '404 tag response', {
    expectedStatus: 404,
    maxBytes: ERROR_MAX_BYTES,
  }), '404 tag response');
  if (document === null || typeof document !== 'object' || Array.isArray(document)
      || Object.keys(document).length !== 1
      || !Array.isArray(document.errors)
      || document.errors.length !== 1) {
    fail('404 tag response is not a canonical registry error envelope');
  }
  const error = document.errors[0];
  const keys = error && typeof error === 'object' && !Array.isArray(error)
    ? Object.keys(error)
    : [];
  if (keys.length !== 2 || !keys.includes('code') || !keys.includes('message')
      || error.code !== 'MANIFEST_UNKNOWN'
      || error.message !== 'manifest unknown') {
    fail('404 tag response is not MANIFEST_UNKNOWN');
  }
}

async function readManifest({
  repositoryPath,
  reference,
  token,
  fetchImpl,
  label,
  expectedDigest,
  expectedSize,
}) {
  const request = await registryGet({
    repositoryPath,
    reference,
    kind: 'manifests',
    token,
    fetchImpl,
  });
  return request.consume(async (response) => {
    const body = await responseBytes(response, label, {
      maxBytes: MANIFEST_MAX_BYTES,
      expectedBytes: expectedSize,
    });
    const calculated = sha256(body);
    const header = validateDigest(
        response.headers.get('docker-content-digest'), `${label} digest header`);
    if (header !== calculated) {
      fail(`${label} digest header does not match the response bytes`);
    }
    if (expectedDigest && calculated !== expectedDigest) {
      fail(`${label} bytes do not match the selected descriptor digest`);
    }
    const document = parseJson(body, label);
    if (document.schemaVersion !== 2) fail(`${label} schemaVersion must be 2`);
    return {
      digest: calculated,
      document,
      mediaType: mediaType(response, document, label),
    };
  });
}

function selectLinuxAmd64(index) {
  if (!Array.isArray(index.manifests) || index.manifests.length === 0) {
    fail('OCI index has no descriptors');
  }
  const runnable = [];
  for (const descriptor of index.manifests) {
    if (descriptor === null || typeof descriptor !== 'object') {
      fail('OCI index contains a malformed descriptor');
    }
    validateDigest(descriptor.digest, 'OCI descriptor digest');
    if (!MANIFEST_MEDIA_TYPES.has(descriptor.mediaType)) {
      fail('OCI index runnable descriptor is not an image manifest');
    }
    if (!Number.isSafeInteger(descriptor.size) || descriptor.size <= 0
        || descriptor.size > MANIFEST_MAX_BYTES) {
      fail('OCI index descriptor size is missing or invalid');
    }
    const platform = descriptor.platform;
    if (platform === null || typeof platform !== 'object' || Array.isArray(platform)) {
      fail('OCI index descriptor platform is missing or malformed');
    }
    const platformKeys = Object.keys(platform);
    if (platformKeys.length !== 2
        || !platformKeys.includes('os')
        || !platformKeys.includes('architecture')) {
      fail('OCI index descriptor platform must contain only os and architecture');
    }
    const os = platform.os;
    const architecture = platform.architecture;
    if (os === 'unknown' && architecture === 'unknown'
        && descriptor.annotations?.['vnd.docker.reference.type'] === 'attestation-manifest') {
      continue;
    }
    if (os !== 'linux' || architecture !== 'amd64') {
      fail(`OCI index contains an unexpected runnable platform ${os}/${architecture}`);
    }
    runnable.push(descriptor);
  }
  if (runnable.length !== 1) {
    fail('OCI index must contain exactly one runnable linux/amd64 descriptor');
  }
  return runnable[0];
}

async function inspectConfig({
  repositoryPath,
  manifest,
  token,
  fetchImpl,
  sourceSha,
  expectedOciSource,
}) {
  if (!MANIFEST_MEDIA_TYPES.has(manifest.mediaType)) {
    fail(`unsupported image manifest media type ${manifest.mediaType}`);
  }
  if (!CONFIG_MEDIA_TYPES.has(manifest.document.config?.mediaType)) {
    fail('image config descriptor media type is unsupported');
  }
  const configDigest = validateDigest(
      manifest.document.config?.digest, 'image config digest');
  const body = await readBlob({
    repositoryPath,
    digest: configDigest,
    expectedSize: manifest.document.config?.size,
    token,
    fetchImpl,
  });
  const config = parseJson(body, 'config blob');
  if (config.os !== 'linux' || config.architecture !== 'amd64') {
    fail('image config platform must be linux/amd64');
  }
  if (config.rootfs?.type !== 'layers'
      || !Array.isArray(config.rootfs.diff_ids)
      || config.rootfs.diff_ids.length === 0
      || !config.rootfs.diff_ids.every((value) => DIGEST.test(value))) {
    fail('image config rootfs diff IDs are missing or malformed');
  }
  if (new Set(config.rootfs.diff_ids).size !== config.rootfs.diff_ids.length) {
    fail('image config rootfs diff IDs must be unique');
  }
  if (!Array.isArray(manifest.document.layers)
      || manifest.document.layers.length === 0
      || manifest.document.layers.length !== config.rootfs.diff_ids.length) {
    fail('image manifest layers do not match config rootfs diff IDs');
  }
  for (const layer of manifest.document.layers) {
    validateDigest(layer?.digest, 'image layer digest');
    if (!LAYER_MEDIA_TYPES.has(layer?.mediaType)) {
      fail('image layer media type is unsupported');
    }
    if (Object.hasOwn(layer, 'urls')) {
      fail('image layer descriptor must not contain external URLs');
    }
    if (!Number.isSafeInteger(layer?.size) || layer.size <= 0) {
      fail('image layer size is missing or invalid');
    }
  }
  const labels = config.config?.Labels;
  if (labels === null || typeof labels !== 'object' || Array.isArray(labels)) {
    fail('image config labels are missing');
  }
  if (labels['org.opencontainers.image.revision'] !== sourceSha) {
    fail('OCI revision does not match the exact source SHA');
  }
  if (labels['org.opencontainers.image.source'] !== expectedOciSource) {
    fail('OCI source does not match the exact source repository');
  }
  return {
    configDigest,
    labels,
    rootfsDiffIds: [...config.rootfs.diff_ids],
  };
}

export async function inspectImmutableTag(options) {
  const repository = exactString(options.repository, 'image repository');
  if (repository !== CANONICAL_IMAGE) {
    fail('image repository is not the canonical repository');
  }
  const repositoryPath = splitRepository(repository);
  const tag = exactString(options.tag, 'image tag');
  const sourceSha = exactString(options.sourceSha, 'source SHA');
  const expectedOciSource = exactString(options.expectedOciSource, 'expected OCI source');
  const username = exactString(options.username, 'registry username');
  const password = exactString(options.password, 'registry password');
  const rawFetchImpl = options.fetchImpl ?? globalThis.fetch;
  if (typeof rawFetchImpl !== 'function') fail('fetch implementation is unavailable');
  const requestTimeoutMs = options.requestTimeoutMs ?? REQUEST_TIMEOUT_MS;
  if (!Number.isSafeInteger(requestTimeoutMs)
      || requestTimeoutMs <= 0 || requestTimeoutMs > 120 * 1000) {
    fail('registry request timeout is invalid');
  }
  const fetchImpl = (endpoint, init, label) => timedFetch(
      rawFetchImpl, endpoint, init, requestTimeoutMs, label);
  if (!SHA.test(sourceSha) || tag !== sourceSha) {
    fail('image tag and source SHA must be the same full lowercase commit SHA');
  }
  if (expectedOciSource !== CANONICAL_SOURCE) {
    fail('expected OCI source is not the canonical repository');
  }
  if (options.expectedImageDigest) {
    validateDigest(options.expectedImageDigest, 'expected image digest');
  }
  if (options.expectedManifestDigest) {
    validateDigest(options.expectedManifestDigest, 'expected manifest digest');
  }
  if (options.expectedConfigDigest) {
    validateDigest(options.expectedConfigDigest, 'expected config digest');
  }
  if (options.expectedRootfsDiffIds !== undefined) {
    if (!Array.isArray(options.expectedRootfsDiffIds)
        || options.expectedRootfsDiffIds.length === 0
        || !options.expectedRootfsDiffIds.every((value) => DIGEST.test(value))) {
      fail('expected rootfs diff IDs are missing or malformed');
    }
  }

  const token = await registryToken({
    repositoryPath, username, password, fetchImpl,
  });
  const tagRequest = await registryGet({
    repositoryPath,
    reference: tag,
    kind: 'manifests',
    token,
    fetchImpl,
  });
  const rootResult = await tagRequest.consume(async (tagResponse) => {
    if (tagResponse.status === 404) {
      await requireManifestUnknown(tagResponse);
      return { absent: true };
    }
    const rootBody = await responseBytes(tagResponse, 'tag manifest', {
      maxBytes: MANIFEST_MAX_BYTES,
    });
    const rootDigest = sha256(rootBody);
    const rootHeader = validateDigest(
        tagResponse.headers.get('docker-content-digest'), 'tag manifest digest header');
    if (rootHeader !== rootDigest) {
      fail('tag manifest digest header does not match the response bytes');
    }
    if (options.expectedImageDigest && rootDigest !== options.expectedImageDigest) {
      fail('tag does not match the expected image digest');
    }
    const rootDocument = parseJson(rootBody, 'tag manifest');
    if (rootDocument.schemaVersion !== 2) fail('tag manifest schemaVersion must be 2');
    return {
      rootDigest,
      rootDocument,
      rootMediaType: mediaType(tagResponse, rootDocument, 'tag manifest'),
    };
  });
  if (rootResult.absent) {
    if (!options.allowAbsent) fail('immutable image tag is absent (HTTP 404)');
    return {
      schema_version: 'devpath.immutable-image.v1',
      state: 'absent',
      source_sha: sourceSha,
      image_repository: repository,
      image_tag: tag,
      image_digest: null,
      manifest_digest: null,
      config_digest: null,
      platform: null,
      rootfs_diff_ids: null,
      oci_labels: {},
    };
  }
  const { rootDigest, rootDocument, rootMediaType } = rootResult;
  let manifest = {
    digest: rootDigest,
    document: rootDocument,
    mediaType: rootMediaType,
  };
  if (INDEX_MEDIA_TYPES.has(rootMediaType)) {
    const selected = selectLinuxAmd64(rootDocument);
    manifest = await readManifest({
      repositoryPath,
      reference: selected.digest,
      token,
      fetchImpl,
      label: 'linux/amd64 child manifest',
      expectedDigest: selected.digest,
      expectedSize: selected.size,
    });
  } else if (!MANIFEST_MEDIA_TYPES.has(rootMediaType)) {
    fail(`unsupported tag manifest media type ${rootMediaType}`);
  }

  const inspected = await inspectConfig({
    repositoryPath,
    manifest,
    token,
    fetchImpl,
    sourceSha,
    expectedOciSource,
  });
  if (options.expectedConfigDigest
      && inspected.configDigest !== options.expectedConfigDigest) {
    fail('image does not match the expected config digest');
  }
  if (options.expectedRootfsDiffIds
      && JSON.stringify(inspected.rootfsDiffIds)
        !== JSON.stringify(options.expectedRootfsDiffIds)) {
    fail('image does not match the expected rootfs diff IDs');
  }
  if (options.expectedManifestDigest
      && manifest.digest !== options.expectedManifestDigest) {
    fail('image does not match the expected manifest digest');
  }
  return {
    schema_version: 'devpath.immutable-image.v1',
    state: 'present',
    source_sha: sourceSha,
    image_repository: repository,
    image_tag: tag,
    image_digest: rootDigest,
    manifest_digest: manifest.digest,
    config_digest: inspected.configDigest,
    platform: { os: 'linux', architecture: 'amd64' },
    rootfs_diff_ids: inspected.rootfsDiffIds,
    oci_labels: {
      'org.opencontainers.image.source': inspected.labels[
          'org.opencontainers.image.source'],
      'org.opencontainers.image.revision': inspected.labels[
          'org.opencontainers.image.revision'],
    },
  };
}

export function createImmutableImageEvidence(result, producer) {
  if (result?.state !== 'present') {
    fail('cannot create evidence for an absent tag');
  }
  if (producer?.repository !== CANONICAL_REPOSITORY) {
    fail('producer repository is not canonical');
  }
  if (producer?.workflowPath !== PRODUCER_WORKFLOW_PATH) {
    fail('producer workflow path is not canonical');
  }
  if (!SHA256_HEX.test(producer?.workflowSha256 ?? '')) {
    fail('producer workflow SHA-256 is malformed');
  }
  const runId = positiveSafeInteger(producer?.runId, 'producer run ID');
  const runAttempt = positiveSafeInteger(
      producer?.runAttempt, 'producer run attempt');
  if (runAttempt !== 1) {
    fail('producer run attempt must be exactly 1');
  }
  return {
    schema_version: 'devpath.immutable-image.v1',
    status: 'passed',
    repository: CANONICAL_REPOSITORY,
    source_sha: result.source_sha,
    image_repository: result.image_repository,
    image_digest: result.image_digest,
    manifest_digest: result.manifest_digest,
    config_digest: result.config_digest,
    platform: result.platform,
    rootfs_diff_ids: result.rootfs_diff_ids,
    oci_labels: result.oci_labels,
    producer_workflow_path: PRODUCER_WORKFLOW_PATH,
    producer_workflow_sha256: producer.workflowSha256,
    producer_run_id: runId,
    producer_run_attempt: runAttempt,
  };
}

function parseArguments(argv) {
  let allowAbsent = false;
  let evidencePath;
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--allow-absent') {
      allowAbsent = true;
    } else if (argument === '--evidence') {
      evidencePath = argv[index + 1];
      index += 1;
      if (!evidencePath) fail('--evidence requires a path');
    } else {
      fail(`unknown argument: ${argument}`);
    }
  }
  return { allowAbsent, evidencePath };
}

function appendOutputs(path, result) {
  if (!path) fail('GITHUB_OUTPUT is required');
  const values = {
    state: result.state,
    image_digest: result.image_digest ?? '',
    manifest_digest: result.manifest_digest ?? '',
    config_digest: result.config_digest ?? '',
    rootfs_diff_ids: result.rootfs_diff_ids
      ? JSON.stringify(result.rootfs_diff_ids)
      : '',
  };
  appendFileSync(path,
      Object.entries(values).map(([key, value]) => `${key}=${value}\n`).join(''),
      { encoding: 'utf8' });
}

async function main() {
  const arguments_ = parseArguments(process.argv.slice(2));
  let expectedRootfsDiffIds;
  if (process.env.EXPECTED_ROOTFS_DIFF_IDS_JSON) {
    try {
      expectedRootfsDiffIds = JSON.parse(
          process.env.EXPECTED_ROOTFS_DIFF_IDS_JSON);
    } catch {
      fail('expected rootfs diff IDs are not valid JSON');
    }
  }
  const result = await inspectImmutableTag({
    repository: process.env.IMAGE_REPOSITORY,
    tag: process.env.IMAGE_TAG,
    sourceSha: process.env.SOURCE_SHA,
    expectedOciSource: process.env.EXPECTED_OCI_SOURCE,
    username: process.env.REGISTRY_USERNAME,
    password: process.env.REGISTRY_PASSWORD,
    expectedImageDigest: process.env.EXPECTED_IMAGE_DIGEST || undefined,
    expectedManifestDigest: process.env.EXPECTED_MANIFEST_DIGEST || undefined,
    expectedConfigDigest: process.env.EXPECTED_CONFIG_DIGEST || undefined,
    expectedRootfsDiffIds,
    allowAbsent: arguments_.allowAbsent,
  });
  if (arguments_.evidencePath) {
    const evidence = createImmutableImageEvidence(result, {
      repository: process.env.PRODUCER_REPOSITORY,
      workflowPath: process.env.PRODUCER_WORKFLOW_PATH,
      workflowSha256: process.env.PRODUCER_WORKFLOW_SHA256,
      runId: process.env.PRODUCER_RUN_ID,
      runAttempt: process.env.PRODUCER_RUN_ATTEMPT,
    });
    mkdirSync(dirname(arguments_.evidencePath), { recursive: true });
    writeFileSync(arguments_.evidencePath, `${JSON.stringify(evidence, null, 2)}\n`, {
      encoding: 'utf8',
      mode: 0o600,
      flag: 'wx',
    });
  }
  appendOutputs(process.env.GITHUB_OUTPUT, result);
  process.stdout.write(
      `immutable tag ${result.state}: ${result.image_digest ?? 'absent'}\n`);
}

const entry = process.argv[1]
  ? pathToFileURL(resolve(process.argv[1])).href
  : '';
if (import.meta.url === entry) {
  main().catch((error) => {
    const message = error instanceof RegistryContractError
      ? error.message
      : 'unexpected registry verification failure';
    process.stderr.write(`immutable image registry verification failed: ${message}\n`);
    process.exitCode = 1;
  });
}
