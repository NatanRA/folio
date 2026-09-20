import copy
import json
import tempfile
import unittest
import threading
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path
from server import merge, merge_positions, Handler

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

class PositionTests(unittest.TestCase):
    def position(self, key='tg-1-2-3', position=1000, duration=60000, modified=1):
        return {'version': 1, 'records': [{'key': key, 'position': position, 'duration': duration, 'modified': modified}]}

    def test_http_route_and_authentication(self):
        with tempfile.TemporaryDirectory() as directory:
            server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            server.token = 'test-only-token-with-enough-characters'
            server.database = str(Path(directory) / 'notes.db')
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            try:
                body = json.dumps(self.position())
                for token, expected in [('wrong', 401), (server.token, 200)]:
                    connection = HTTPConnection('127.0.0.1', server.server_port, timeout=3)
                    try:
                        connection.request('POST', '/annotations/positions', body, {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
                        response = connection.getresponse(); payload = response.read()
                        self.assertEqual(response.status, expected)
                        if expected == 200:
                            self.assertEqual(json.loads(payload)['records'][0]['position'], 1000)
                    finally: connection.close()
                # The annotations route still works and is kept in its own table.
                book = 'a' * 64
                connection = HTTPConnection('127.0.0.1', server.server_port, timeout=3)
                try:
                    connection.request('POST', '/annotations/' + book, json.dumps({'version': 1, 'document': book, 'records': []}),
                                       {'Authorization': 'Bearer ' + server.token, 'Content-Type': 'application/json'})
                    response = connection.getresponse(); payload = response.read()
                    self.assertEqual(response.status, 200)
                    self.assertEqual(json.loads(payload)['document'], book)
                finally: connection.close()
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_later_write_wins_and_stale_push_never_rewinds(self):
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / 'notes.db'
            merge_positions(db, 'library', self.position(position=1000, modified=1))
            merge_positions(db, 'library', self.position(position=8241000, modified=2))
            # A device that was offline replays its old place; the newer one must survive.
            merged = json.loads(merge_positions(db, 'library', self.position(position=1000, modified=1)))
            self.assertEqual(merged['records'][0]['position'], 8241000)
            self.assertEqual(len(merged['records']), 1)

    def test_equal_time_prefers_further_position(self):
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / 'notes.db'
            merge_positions(db, 'library', self.position(position=5000, modified=7))
            merged = json.loads(merge_positions(db, 'library', self.position(position=4000, modified=7)))
            self.assertEqual(merged['records'][0]['position'], 5000)

    def test_many_books_round_trip_and_pull_returns_all(self):
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / 'notes.db'
            batch = {'version': 1, 'records': [{'key': f'tg-1-2-{n}', 'position': n * 10, 'duration': 60000, 'modified': n} for n in range(1, 13)]}
            merge_positions(db, 'library', batch)
            # An empty push is how a fresh device pulls everything it has never seen.
            merged = json.loads(merge_positions(db, 'library', {'version': 1, 'records': []}))
            self.assertEqual(len(merged['records']), 12)
            self.assertEqual({r['key'] for r in merged['records']}, {f'tg-1-2-{n}' for n in range(1, 13)})

    def test_invalid_records_are_rejected_atomically(self):
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / 'notes.db'
            merge_positions(db, 'library', self.position(position=1000, modified=1))
            for bad in [self.position(key='../etc/passwd'), self.position(key=''), self.position(position=-1),
                        self.position(duration=2_592_000_001), self.position(modified=-1)]:
                with self.assertRaises(ValueError): merge_positions(db, 'library', bad)
            bad_batch = self.position(position=9000, modified=9)
            bad_batch['records'].append({'key': 'tg-9-9-9'})
            with self.assertRaises(ValueError): merge_positions(db, 'library', bad_batch)
            # Nothing from the rejected batch was written.
            merged = json.loads(merge_positions(db, 'library', {'version': 1, 'records': []}))
            self.assertEqual(len(merged['records']), 1)
            self.assertEqual(merged['records'][0]['position'], 1000)

    def test_booleans_are_not_accepted_as_numbers(self):
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / 'notes.db'
            with self.assertRaises(ValueError): merge_positions(db, 'library', self.position(position=True))

if __name__ == '__main__': unittest.main()
