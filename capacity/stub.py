"""Local-only external provider fault stub. Never forwards requests to real providers."""
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import BoundedSemaphore

assert os.environ.get('AIRD_CAPACITY_RUN_ID', '').startswith('aird-capacity-')
state = {'delayMs': 0, 'status': 200}
slots = BoundedSemaphore(8)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        if not slots.acquire(False):
            self.send_response(503)
            self.end_headers()
            return
        try:
            length = min(int(self.headers.get('Content-Length', 0)), 32 * 1024 * 1024)
            payload = json.loads(self.rfile.read(length) or '{}')
            if self.path == '/fault':
                state.update(delayMs=min(120000, max(0, int(payload.get('delayMs', 0)))),
                             status=int(payload.get('status', 200)))
                body = state.copy()
            else:
                time.sleep(state['delayMs'] / 1000)
                body = {'id': 'capacity-stub', 'choices': [{'message': {'role': 'assistant', 'content': '{}'}}],
                        'data': [{'index': 0, 'embedding': [0.0] * 1024}]}
            self.send_response(200 if self.path == '/fault' else state['status'])
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(body).encode())
        finally:
            slots.release()


ThreadingHTTPServer(('0.0.0.0', 8099), Handler).serve_forever()
