#!/usr/bin/env python3
"""Explicit build-time downloads and transactional assets publication. No third-party Python packages."""
import argparse
import collections
import concurrent.futures
import ctypes
import hashlib
import gzip
import io
import ipaddress
import json
import os
import pathlib
import re
import shutil
import socket
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import zlib

CONFIG_LIMIT = 4 * 1024 * 1024
JS_LIMIT = 1024 * 1024
JAR_LIMIT = 24 * 1024 * 1024
TOTAL_LIMIT = 128 * 1024 * 1024
MAX_DOCUMENTS = 2048


def digest(data):
    return hashlib.sha256(data).hexdigest()


def resolve(base, value):
    """Resolve while the original remote URL still exists; never against an asset path."""
    if not isinstance(value, str):
        return ''
    raw = value.strip()
    if raw.startswith('csp_') or not raw:
        return raw
    parts = raw.split(';md5;', 1)
    url = urllib.parse.urljoin(base, parts[0])
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme.lower() not in ('http', 'https') or not parsed.hostname:
        return ''
    # urllib requires an ASCII URL; do not lose meaningful query values.
    host = parsed.hostname.encode('idna').decode('ascii').lower()
    if ':' in host:
        host = '[' + host + ']'
    if parsed.username or parsed.password:
        raise ValueError('URL_CREDENTIALS')
    authority = host + (':' + str(parsed.port) if parsed.port else '')
    encoded = urllib.parse.urlunsplit((parsed.scheme.lower(), authority,
        urllib.parse.quote(parsed.path or '/', safe="/%:@!$&'()*+,;=-._~"),
        urllib.parse.quote(parsed.query, safe="%=&?/:@!$'()*+,;[]-._~"), ''))
    return encoded + (';md5;' + parts[1] if len(parts) == 2 else '')


def public_url(url):
    host = urllib.parse.urlsplit(url).hostname
    if not host or host.lower() == 'localhost':
        raise ValueError('PRIVATE_ADDRESS')
    for address in socket.getaddrinfo(host, None, type=socket.SOCK_STREAM):
        if not ipaddress.ip_address(address[4][0]).is_global:
            raise ValueError('PRIVATE_ADDRESS')


class PublicRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        public_url(newurl)
        return super().redirect_request(request, fp, code, message, headers, newurl)


def download(url, maximum, timeout=8):
    actual = url.split(';md5;', 1)[0]
    public_url(actual)
    opener = urllib.request.build_opener(PublicRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    request = urllib.request.Request(actual, headers={'User-Agent': 'YunoTV-SourceCompiler/1.0', 'Accept-Encoding': 'identity'})
    with opener.open(request, timeout=timeout) as response:
        if int(response.headers.get('Content-Length', '0')) > maximum:
            raise ValueError('SIZE_LIMIT')
        deadline = time.monotonic() + timeout
        chunks = []
        size = 0
        while True:
            if time.monotonic() > deadline:
                raise ValueError('TOTAL_DOWNLOAD_TIMEOUT')
            chunk = response.read1(65536)
            if not chunk:
                break
            size += len(chunk)
            if size > maximum:
                raise ValueError('SIZE_LIMIT')
            chunks.append(chunk)
        data = b''.join(chunks)
        if not data or len(data) > maximum:
            raise ValueError('SIZE_LIMIT')
        return data, response.headers.get('Content-Type', ''), response.geturl()


def failure(error):
    if isinstance(error, urllib.error.HTTPError):
        return 'HTTP_' + str(error.code)
    return str(error) if isinstance(error, ValueError) else type(error).__name__


def live_references(rows):
    for row in rows if isinstance(rows, list) else []:
        if not isinstance(row, dict) or row.get('enabled') is False:
            continue
        if 'channels' in row:
            yield from live_references(row['channels'])
        else:
            yield row.get('url') or next(iter(row.get('urls') or []), '')


def children(body, base):
    """Only follow configuration/playlist references, never media segments or VOD links."""
    try:
        root = json.loads(body)
    except (ValueError, RecursionError):
        return []
    if not isinstance(root, dict):
        return []
    entries = root.get('sources', []) if root.get('format') == 'yunotv-source-bundle' else root.get('urls', root.get('configs', []))
    result = []
    for entry in entries[:64]:
        if isinstance(entry, dict) and entry.get('enabled') is False:
            continue
        raw = entry.get('url', '') if isinstance(entry, dict) else entry
        url = resolve(base, raw)
        if url:
            result.append((url, False))
    for raw in live_references(root.get('lives', root.get('channels', []))):
        url = resolve(base, raw)
        if url and urllib.parse.urlsplit(url).path.lower().endswith(('.m3u', '.txt')):
            result.append((url, True))
    return result


def fetch(root):
    staging = root / 'build/yuno-source-staging'
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    manifest = json.loads((root / 'sources.txt.json').read_text(encoding='utf-8-sig'))
    mandatory = {resolve('', row['url']) for row in manifest['sources'] if row.get('enabled', True)}
    if not mandatory:
        raise ValueError('EMPTY_ROOT_CONFIG')
    pending = [(url, False) for url in sorted(mandatory)]
    documents = {}
    max_depth = min(8, max(1, int(manifest.get('mergePolicy', {}).get('maxDepth', 4))))
    def load(item):
        url, live = item
        try:
            data, content_type, final_url = download(url, CONFIG_LIMIT)
            body = data.decode('utf-8-sig', errors='strict')
            return url, {'body': body, 'contentType': 'application/x-yuno-live' if live else content_type,
                         'originalUrl': url, 'responseUrl': final_url}
        except Exception as error:
            return url, {'body': '', 'error': failure(error), 'contentType': ''}
    for depth in range(max_depth):
        batch = {url: live for url, live in pending if url not in documents}
        if len(documents) + len(batch) > MAX_DOCUMENTS:
            raise ValueError('CONFIG_COUNT_LIMIT')
        if not batch:
            break
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
            rows = list(pool.map(load, batch.items()))
        pending = []
        for url, row in rows:
            documents[url] = row
            if 'error' not in row:
                pending.extend(children(row['body'], url))
        if sum(len(row['body'].encode('utf-8')) for row in documents.values()) > 256 * 1024 * 1024:
            raise ValueError('TOTAL_CONFIG_SIZE_LIMIT')
        print(f'Config depth {depth + 1}: downloaded {sum("error" not in r for r in documents.values())}; failed {sum("error" in r for r in documents.values())}', flush=True)
    (staging / 'downloaded-configs.json').write_text(json.dumps({'documents': documents}, ensure_ascii=False), encoding='utf-8')
    # A root failure is fatal. Optional child/playlist failures are retained as explicit diagnostics.
    if any('error' in documents[url] for url in mandatory):
        raise ValueError('ROOT_CONFIG_FAILED_PREVIOUS_ASSETS_RETAINED')
    (staging / 'fetch-summary.json').write_text(json.dumps({'rootConfig': len(mandatory),
        'downloadedConfig': sum('error' not in row for row in documents.values()),
        'failedConfig': sum('error' in row for row in documents.values())}))


def verify_jar(data):
    total = 0
    dex_count = 0
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        if len(jar.infolist()) > 4096:
            raise ValueError('JAR_ENTRY_LIMIT')
        for entry in jar.infolist():
            if entry.filename.startswith('/') or '..' in entry.filename.split('/') or entry.filename.endswith('.so'):
                raise ValueError('UNSAFE_JAR')
            if entry.file_size > 32 * 1024 * 1024:
                raise ValueError('ZIP_SIZE_LIMIT')
            total += entry.file_size
            if total > 64 * 1024 * 1024:
                raise ValueError('ZIP_SIZE_LIMIT')
            if re.fullmatch(r'classes\d*\.dex', entry.filename):
                dex_count += 1
                content = jar.read(entry)
                if len(content) < 112 or content[:4] != b'dex\n' or content[7] != 0 or int.from_bytes(content[32:36], 'little') != len(content) or int.from_bytes(content[36:40], 'little') != 112:
                    raise ValueError('INVALID_DEX')
                if content[12:32] != hashlib.sha1(content[32:]).digest() or int.from_bytes(content[8:12], 'little') != zlib.adler32(content[12:]):
                    raise ValueError('INVALID_DEX_HASH')
        if not 1 <= dex_count <= 8:
            raise ValueError('INVALID_JAR')


def validate_assets(directory):
    bundle_file = directory / 'yunotv-bundle.json'
    if bundle_file.stat().st_size > 16 * 1024 * 1024:
        raise ValueError('BUNDLE_SIZE_LIMIT')
    bundle = json.loads(bundle_file.read_text())
    if bundle['format'] != 'yunotv-compiled-bundle' or not bundle['config']['sites']:
        raise ValueError('INVALID_BUNDLE')
    index = json.loads((directory / 'resource-index.json').read_text())
    if index['version'] != 1 or index['bundleSha256'] != digest(bundle_file.read_bytes()):
        raise ValueError('INVALID_INDEX')
    for row in index['resources'].values():
        expected = f'yunotv/live/{row["sha256"]}.playlist' if row['type'] == 'PLAYLIST' else f'yunotv/{row["type"].lower()}/{row["sha256"]}.{row["type"].lower()}'
        if row['type'] not in ('JS', 'JAR', 'PLAYLIST') or not re.fullmatch('[a-f0-9]{64}', row['sha256']) or row['asset'] != expected:
            raise ValueError('UNSAFE_ASSET_PATH')
        data = (directory / pathlib.PurePosixPath(row['asset']).relative_to('yunotv')).read_bytes()
        if row['type'] == 'PLAYLIST':
            with gzip.GzipFile(fileobj=io.BytesIO(data)) as stream:
                data = stream.read(CONFIG_LIMIT + 1)
        if len(data) != row['size'] or digest(data) != row['sha256']:
            raise ValueError('ASSET_HASH_MISMATCH')
        if row['type'] == 'JAR':
            verify_jar(data)
    if sum(p.stat().st_size for p in directory.rglob('*') if p.is_file()) > TOTAL_LIMIT:
        raise ValueError('ASSET_TOTAL_SIZE_LIMIT')


def atomic_publish(staging, target):
    """Directory exchange has no window where the old bundle disappears (macOS / Linux)."""
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        os.replace(staging, target)
        return
    libc = ctypes.CDLL(None, use_errno=True)
    if sys.platform == 'darwin':
        swap = libc.renamex_np
        swap.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        status = swap(os.fsencode(staging), os.fsencode(target), 2)  # RENAME_SWAP
    elif sys.platform.startswith('linux') and hasattr(libc, 'renameat2'):
        swap = libc.renameat2
        swap.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        status = swap(-100, os.fsencode(staging), -100, os.fsencode(target), 2)  # RENAME_EXCHANGE
    else:
        raise ValueError('ATOMIC_DIRECTORY_EXCHANGE_UNAVAILABLE')
    if status != 0:
        raise OSError(ctypes.get_errno(), 'Atomic publish failed; previous assets retained')
    # After exchange, staging holds the previous complete bundle. Retain it for rollback.


def publish(root):
    staging = root / 'build/yuno-source-staging/yunotv'
    target = root / 'app/src/main/assets/yunotv'
    bundle = json.loads((staging / 'yunotv-bundle.json').read_text())
    sites = bundle['config']['sites']
    references = {}
    expected_md5 = collections.defaultdict(set)
    def add(raw, kind, source_id):
        if not raw or not raw.startswith(('http://', 'https://')):
            return
        url = resolve('', raw).split(';md5;', 1)[0]
        key = (url, kind)
        references.setdefault(key, set()).add(source_id)
        suffix = raw.partition(';md5;')[2].lower()
        if re.fullmatch('[a-f0-9]{32}', suffix):
            expected_md5[key].add(suffix)
    for site in sites:
        kind = site['capability']['kind']
        if kind == 'DRPY_JS':
            add(site['api'], 'JS', site['id'])
            if str(site['ext']).startswith(('http://', 'https://')):
                add(site['ext'], 'JS', site['id'])
        elif kind == 'SPIDER_JAR':
            add(site['metadata']['spider'] or site['api'].split(';')[0], 'JAR', site['id'])
    documents = json.loads((staging.parent / 'downloaded-configs.json').read_text())['documents']
    for playlist in bundle['config'].get('playlists', []):
        add(playlist['url'], 'PLAYLIST', playlist['id'])
    previous = {}
    if (target / 'resource-index.json').is_file():
        previous = json.loads((target / 'resource-index.json').read_text()).get('resources', {})
    def load(item):
        (url, kind), owners = item
        try:
            if kind == 'PLAYLIST':
                row = documents.get(url)
                if not row or row.get('error'):
                    raise ValueError('PLAYLIST_DOWNLOAD_FAILED')
                data = row['body'].encode('utf-8')
                if not data.strip() or data.lstrip().startswith(b'<'):
                    raise ValueError('INVALID_PLAYLIST')
            else:
                data, _, _ = download(url, JS_LIMIT if kind == 'JS' else JAR_LIMIT)
            if expected_md5[(url, kind)] and hashlib.md5(data).hexdigest() not in expected_md5[(url, kind)]:
                raise ValueError('HASH_MISMATCH')
            if kind == 'JS':
                text = data.decode('utf-8-sig', errors='strict')
                if text.lstrip().startswith('<'):
                    raise ValueError('INVALID_JS')
            elif kind == 'JAR':
                verify_jar(data)
            return url, kind, owners, data, 'DOWNLOADED', ''
        except Exception as error:
            old = previous.get(url)
            if old and old.get('type') == kind:
                try:
                    data = (target / pathlib.PurePosixPath(old['asset']).relative_to('yunotv')).read_bytes()
                    if kind == 'PLAYLIST':
                        data = gzip.decompress(data)
                    if digest(data) == old['sha256'] and (not expected_md5[(url, kind)] or hashlib.md5(data).hexdigest() in expected_md5[(url, kind)]):
                        return url, kind, owners, data, 'LAST_GOOD', failure(error)
                except Exception:
                    pass
            return url, kind, owners, None, 'FAILED', failure(error)
    resources = {}
    failures = []
    statuses = collections.Counter()
    processed = set()
    stored_bytes = (staging / 'yunotv-bundle.json').stat().st_size
    # Follow explicit static imports and the engine's known template, without evaluating code.
    for depth in range(4):
        todo = {key: owners for key, owners in references.items() if key not in processed}
        if not todo:
            break
        if len(references) > 4096:
            raise ValueError('RESOURCE_COUNT_LIMIT')
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(load, sorted(todo.items())))
        for url, kind, owners, data, status, error in results:
            processed.add((url, kind))
            statuses[(kind, status)] += 1
            if data is None:
                # Diagnostic URLs exclude credentials/query strings.
                failures.append({'url': urllib.parse.urlunsplit((*urllib.parse.urlsplit(url)[:3], '', '')), 'type': kind, 'error': error})
                continue
            sha = digest(data)
            # AAPT automatically decompresses/renames *.gz. Keep a distinct suffix for indexed gzip bytes.
            asset = f'yunotv/live/{sha}.playlist' if kind == 'PLAYLIST' else f'yunotv/{kind.lower()}/{sha}.{kind.lower()}'
            destination = staging / pathlib.PurePosixPath(asset).relative_to('yunotv')
            destination.parent.mkdir(parents=True, exist_ok=True)
            stored = gzip.compress(data, mtime=0) if kind == 'PLAYLIST' else data
            if not destination.exists():
                stored_bytes += len(stored)
                if stored_bytes > TOTAL_LIMIT:
                    raise ValueError('ASSET_TOTAL_SIZE_LIMIT')
                destination.write_bytes(stored)
            resources[url] = {'originalUrl': url, 'type': kind, 'sha256': sha, 'asset': asset, 'size': len(data), 'sourceSites': sorted(owners), 'status': status}
            if kind == 'JS':
                text = data.decode('utf-8-sig')
                imports = re.findall(r'\bimport\s+(?:[^;\n]*?\s+from\s+)?[\"\']([^\"\']+)[\"\']', text)
                if '模板.js' in text:
                    imports.append('模板.js')
                for relative in imports:
                    if relative.startswith(('./', '../', '/', 'http://', 'https://')) or relative.split('?')[0].endswith('.js'):
                        child = resolve(url, relative)
                        for owner in owners:
                            add(child, 'JS', owner)
        print(f'Resource pass {depth + 1}: JS {sum(r["type"] == "JS" for r in resources.values())}; JAR {sum(r["type"] == "JAR" for r in resources.values())}; failed {len(failures)}', flush=True)
    generated_at = bundle['generatedAt']
    index = {'version': 1, 'generatedAt': generated_at, 'bundleSha256': digest((staging / 'yunotv-bundle.json').read_bytes()), 'resources': dict(sorted(resources.items())), 'failures': failures}
    (staging / 'resource-index.json').write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding='utf-8')
    jars = {url: value for url, value in resources.items() if value['type'] == 'JAR'}
    (staging / 'spider-asset-index.json').write_text(json.dumps({'version': 1, 'jars': jars}, ensure_ascii=False, indent=2), encoding='utf-8')
    validate_assets(staging)
    summary = json.loads((staging.parent / 'fetch-summary.json').read_text())
    summary.update({'parsedSites': bundle['statistics']['parsedSiteCount'], 'deduplicatedSites': len(sites),
        'drpySites': sum(s['capability']['kind'] == 'DRPY_JS' for s in sites),
        'jsUrls': sum(kind == 'JS' for _, kind in references), 'jsDownloaded': statuses['JS', 'DOWNLOADED'],
        'uniqueJs': len({r['sha256'] for r in resources.values() if r['type'] == 'JS'}), 'jsFailed': statuses['JS', 'FAILED'],
        'spiderSites': sum(s['capability']['kind'] == 'SPIDER_JAR' for s in sites),
        'jarUrls': sum(kind == 'JAR' for _, kind in references), 'jarDownloaded': statuses['JAR', 'DOWNLOADED'],
        'uniqueJar': len({r['sha256'] for r in resources.values() if r['type'] == 'JAR'}), 'jarFailed': statuses['JAR', 'FAILED'],
        'lastGoodResources': statuses['JS', 'LAST_GOOD'] + statuses['JAR', 'LAST_GOOD'],
        'playlistUrls': sum(kind == 'PLAYLIST' for _, kind in references),
        'playlistDownloaded': statuses['PLAYLIST', 'DOWNLOADED'], 'playlistFailed': statuses['PLAYLIST', 'FAILED'],
        'assetsBytes': sum(p.stat().st_size for p in staging.rglob('*') if p.is_file())})
    (staging / 'sync-summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    summary['assetsBytes'] = sum(p.stat().st_size for p in staging.rglob('*') if p.is_file())
    (staging / 'sync-summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    validate_assets(staging)
    atomic_publish(staging, target)
    print(json.dumps(summary, indent=2), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['fetch', 'publish', 'validate'])
    parser.add_argument('--root', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    try:
        if args.mode == 'fetch':
            fetch(args.root.resolve())
        elif args.mode == 'publish':
            publish(args.root.resolve())
        else:
            validate_assets(args.root / 'app/src/main/assets/yunotv')
    except Exception as error:
        print('Source sync failed: ' + failure(error) + '; previous packaged assets retained.', file=sys.stderr)
        sys.exit(1)
