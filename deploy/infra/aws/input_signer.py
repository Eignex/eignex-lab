#!/usr/bin/env python3
"""Loopback-only URL signing for native input-cache access. No corpus bytes pass through this service."""
import argparse
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re


def signature_request(body, bucket):
    method, key = body['method'], body['key']
    if method not in ('HEAD', 'GET', 'PUT') or not isinstance(key, str):
        raise ValueError('invalid operation')
    parts = key.split('/')
    if any(part in ('', '.', '..') for part in parts) or '\\' in key or '\x00' in key:
        raise ValueError('invalid key')
    blob = re.fullmatch(r'input-blobs/([a-f0-9]{64})', key)
    pack = re.fullmatch(r'corpus-packs/[A-Za-z0-9][A-Za-z0-9._-]*\.tar', key)
    if not blob and not pack and not key.startswith('corpus/'):
        raise ValueError('key outside input cache')
    params = dict(Bucket=bucket, Key=key)
    headers = {}
    if method == 'PUT':
        sha = body.get('sha256', '')
        if not re.fullmatch(r'[a-f0-9]{64}', sha) or (not pack and (not blob or sha != blob[1])):
            raise ValueError('writes require a content hash')
        checksum = base64.b64encode(bytes.fromhex(sha)).decode()
        params.update(IfNoneMatch='*', Metadata={'sha256': sha}, ChecksumSHA256=checksum)
        headers = {'If-None-Match': '*', 'x-amz-meta-sha256': sha, 'x-amz-checksum-sha256': checksum}
    return {'HEAD': 'head_object', 'GET': 'get_object', 'PUT': 'put_object'}[method], params, headers


def main():
    import boto3
    from botocore.config import Config
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    client = boto3.Session(profile_name=config['profile'], region_name=config['region']).client(
        's3', config=Config(signature_version='s3v4', s3={'addressing_style': 'virtual'}))

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if self.path != '/' or not 0 < length <= 8192:
                    raise ValueError('invalid signing request')
                operation, params, headers = signature_request(json.loads(self.rfile.read(length)), config['bucket'])
                url = client.generate_presigned_url(operation, Params=params, ExpiresIn=300)
                data = json.dumps(dict(url=url, headers=headers)).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except (ValueError, KeyError, TypeError):
                self.send_error(400, 'invalid signing request')
            except Exception:
                self.send_error(503, 'input signing unavailable')

    ThreadingHTTPServer(('127.0.0.1', config.get('port', 8093)), Handler).serve_forever()


if __name__ == '__main__':
    main()
