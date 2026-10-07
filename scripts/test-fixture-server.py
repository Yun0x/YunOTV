#!/usr/bin/env python3
"""Local CMS/IPTV fixture, only used by tests. Never included in the application."""
import argparse
import http.server
import json
from pathlib import Path
from urllib.parse import urlparse, parse_qs

parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=18765)
parser.add_argument('--video', required=True)
args = parser.parse_args()
base = f'http://10.0.2.2:{args.port}'
video = Path(args.video).read_bytes()

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_GET(self):
        path = urlparse(self.path).path.rstrip('/')
        if path == '/broken.json':
            self.send_response(503); self.end_headers(); return
        if path == '/clip.mp4':
            start, end = 0, len(video) - 1
            if self.headers.get('Range', '').startswith('bytes='):
                value = self.headers['Range'][6:].split('-'); start = int(value[0] or 0)
                end = min(end, int(value[1])) if value[1] else end
                self.send_response(206); self.send_header('Content-Range', f'bytes {start}-{end}/{len(video)}')
            else: self.send_response(200)
            body = video[start:end + 1]
            self.send_header('Content-Type', 'video/mp4'); self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body); return
        if path == '/cms':
            query = parse_qs(urlparse(self.path).query)
            items = [dict(vod_id=str(i), vod_name=f'Yuno Test {i}', vod_year='2026', type_name='电影', vod_area='测试',
                vod_director='Yuno', vod_actor='Synthetic pattern', vod_score='8.5', vod_content='原创合成测试画面，不含第三方影视内容。',
                vod_play_from='Direct$$$Backup', vod_play_url=f'第一集${base}/clip.mp4#第二集${base}/clip.mp4?ep=2$$$第一集${base}/clip.mp4#第二集${base}/clip.mp4?ep=2') for i in range(1, 4)]
            items[2]['vod_play_url'] = f'第一集${base}/missing.mp4$$$第一集${base}/clip.mp4'
            if 'ids' in query: items = [item for item in items if item['vod_id'] == query['ids'][0]]
            if 'wd' in query: items = [item for item in items if query['wd'][0].lower() in item['vod_name'].lower()]
            obj = {'code': 1, 'list': items, 'class': [dict(type_id='1', type_name='电影')]}
            body = json.dumps(obj, ensure_ascii=False).encode(); content_type = 'application/json'
        elif path == '/config.json':
            body = json.dumps(dict(sites=[dict(key='qa', name='QA CMS', type=1, api=base + '/cms/')],
                lives=[dict(name='QA IPTV', type=0, url=base + '/live.m3u')], parses=[])).encode(); content_type = 'application/json'
        elif path == '/sources.txt':
            body = f'# comment\r\nQA duplicate|{base}/cms\r\n'.encode(); content_type = 'text/plain'
        elif path == '/live.m3u':
            body = f'#EXTM3U\n#EXTINF:-1 group-title="测试频道",Yuno Live\n{base}/clip.mp4\n'.encode(); content_type = 'application/x-mpegurl'
        else:
            self.send_response(404); self.end_headers(); return
        etag = '"yuno-qa-v1"'
        if self.headers.get('If-None-Match') == etag:
            self.send_response(304); self.send_header('ETag', etag); self.end_headers(); return
        self.send_response(200); self.send_header('ETag', etag); self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)

print(f'Local test fixture listening on 127.0.0.1:{args.port}', flush=True)
http.server.ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()
