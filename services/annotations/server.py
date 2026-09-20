"""Private annotation sync, served behind an HTTPS reverse proxy. No external dependencies."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BYTES = 8_000_000

def validate(bundle, document):
    if not isinstance(bundle, dict) or bundle.get('version') != 1 or bundle.get('document') != document:
        raise ValueError('Invalid document')
    records = bundle.get('records')
    if not isinstance(records, list) or len(records) > 10000:
        raise ValueError('Too many annotations')
    for r in records:
        if not isinstance(r, dict) or not isinstance(r.get('id'), str) or not 1 <= len(r['id']) <= 100:
            raise ValueError('Invalid annotation id')
        if r.get('kind') not in ('note', 'bookmark', 'word') or not isinstance(r.get('note'), str) or len(r['note']) > 10000:
            raise ValueError('Invalid annotation')
        if type(r.get('modified')) is not int or not 0 <= r['modified'] <= 2**53 or type(r.get('deleted')) is not bool:
            raise ValueError('Invalid revision')
        if not isinstance(r.get('revision'), str) or len(r['revision']) > 100:
            raise ValueError('Invalid revision id')
        if not isinstance(r.get('locator'), str) or len(r['locator']) > 100000:
            raise ValueError('Invalid location')
        locator = json.loads(r['locator'])
        if not isinstance(locator, dict) or not isinstance(locator.get('href'), str) or not isinstance(locator.get('type'), str):
            raise ValueError('Invalid location')
    return records

def rank(record):
    return record['modified'], record['deleted'], record['revision']

def merge(database, owner, document, bundle):
    records = validate(bundle, document)
    with sqlite3.connect(database, timeout=30) as db:
        db.execute('CREATE TABLE IF NOT EXISTS notes (owner TEXT, document TEXT, id TEXT, value TEXT, PRIMARY KEY(owner,document,id))')
        db.execute('BEGIN IMMEDIATE')
        known = {r[0]: json.loads(r[1]) for r in db.execute('SELECT id,value FROM notes WHERE owner=? AND document=?', (owner, document))}
        for record in records:
            old = known.get(record['id'])
            if old is None or rank(record) > rank(old):
                known[record['id']] = record
        result = {'version': 1, 'document': document, 'records': list(known.values())}
        encoded = json.dumps(result, ensure_ascii=False).encode()
        if len(known) > 10000 or len(encoded) > MAX_BYTES:
            raise ValueError('Annotation capacity reached')
        db.executemany('INSERT OR REPLACE INTO notes VALUES (?,?,?,?)', [(owner, document, r['id'], json.dumps(r)) for r in known.values()])
        return encoded

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Never log tokens or annotation contents.
    def reply(self, status, data):
        self.send_response(status); self.send_header('Content-Type', 'application/json')
        self.send_header('Cache-Control', 'no-store'); self.send_header('Content-Length', str(len(data)))
        self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        token = self.headers.get('Authorization', '').removeprefix('Bearer ')
        if not hmac.compare_digest(token.encode(), self.server.token.encode()):
            return self.reply(401, b'{"error":"Unauthorized"}')
        document = self.path.rsplit('/', 1)[-1]
        if not re.fullmatch('[0-9a-f]{64}', document):
            return self.reply(400, b'{"error":"Invalid document"}')
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= MAX_BYTES:
                return self.reply(413, b'{"error":"Invalid payload size"}')
            self.connection.settimeout(30)
            bundle = json.loads(self.rfile.read(length))
            # One private household library per deployment. Rotate token without changing ownership.
            self.reply(200, merge(self.server.database, 'library', document, bundle))
        except (ValueError, TypeError, KeyError, TimeoutError):
            self.reply(400, b'{"error":"Invalid annotations"}')
        except sqlite3.Error:
            self.reply(503, b'{"error":"Storage unavailable"}')

if __name__ == '__main__':
    token = os.environ.get('ANNOTATION_TOKEN', '')
    if len(token) < 24:
        raise SystemExit('Set ANNOTATION_TOKEN to a random token of at least 24 characters')
    database = os.environ.get('ANNOTATION_DB', '/data/annotations.sqlite3')
    Path(database).parent.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(('0.0.0.0', int(os.environ.get('PORT', '8080'))), Handler)
    server.token, server.database = token, database
    server.serve_forever()
