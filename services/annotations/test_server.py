import copy
import json
import tempfile
import unittest
import threading
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path
from server import merge, Handler

class SyncTests(unittest.TestCase):
    def test_http_authentication_and_round_trip(self):
        with tempfile.TemporaryDirectory() as directory:
            server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            server.token = 'test-only-token-with-enough-characters'
            server.database = str(Path(directory) / 'notes.db')
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            try:
                book = 'a' * 64
                body = json.dumps({'version': 1, 'document': book, 'records': []})
                for token, expected in [('wrong', 401), (server.token, 200)]:
                    connection = HTTPConnection('127.0.0.1', server.server_port, timeout=3)
                    try:
                        connection.request('POST', '/' + book, body, {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
                        response = connection.getresponse(); payload = response.read()
                        self.assertEqual(response.status, expected)
                        if expected == 200: self.assertEqual(json.loads(payload)['document'], book)
                    finally: connection.close()
            finally:
                server.shutdown(); server.server_close(); thread.join()
    def test_two_devices_edit_delete_and_wrong_book(self):
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / 'notes.db'
            book = 'a' * 64
            first = {'version': 1, 'document': book, 'records': [{'id': 'note-1', 'kind': 'note', 'locator': '{"href":"chapter.xhtml","type":"application/xhtml+xml"}', 'note': 'Android note', 'modified': 1, 'revision': 'a', 'deleted': False}]}
            self.assertEqual(json.loads(merge(db, 'library', book, first))['records'][0]['note'], 'Android note')
            second = copy.deepcopy(first); second['records'][0].update(note='Edited on iOS', modified=2, revision='b')
            merge(db, 'library', book, second)
            self.assertEqual(json.loads(merge(db, 'library', book, first))['records'][0]['note'], 'Edited on iOS')
            second['records'][0].update(deleted=True, modified=3)
            merge(db, 'library', book, second)
            self.assertTrue(json.loads(merge(db, 'library', book, first))['records'][0]['deleted'])
            with self.assertRaises(ValueError): merge(db, 'library', 'b' * 64, first)
    def test_equal_time_tombstone_wins_and_invalid_batch_is_atomic(self):
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / 'notes.db'; book = 'a' * 64
            row = {'id':'1','kind':'bookmark','locator':'{"href":"x","type":"text/html"}','note':'','modified':0,'revision':'','deleted':False}
            bundle = {'version':1,'document':book,'records':[row]}; merge(db, 'library', book, bundle)
            row['deleted'] = True; merge(db, 'library', book, bundle)
            row['deleted'] = False
            self.assertTrue(json.loads(merge(db, 'library', book, bundle))['records'][0]['deleted'])
            bundle['records'].append({'id':'bad'})
            with self.assertRaises(ValueError): merge(db, 'library', book, bundle)

if __name__ == '__main__': unittest.main()
