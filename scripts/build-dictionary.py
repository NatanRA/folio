#!/usr/bin/env python3
"""Build the bundled English dictionary from Princeton WordNet 3.0.

Usage: python3 scripts/build-dictionary.py /path/to/WordNet-3.0.tar.gz
Source: https://wordnetcode.princeton.edu/3.0/WordNet-3.0.tar.gz
"""
import gzip
from pathlib import Path
import sqlite3
import sys
import tarfile
import tempfile

root = Path(__file__).resolve().parents[1]
destination = root / 'app/src/main/assets/dictionary'
destination.mkdir(parents=True, exist_ok=True)
with tarfile.open(sys.argv[1], 'r:gz') as archive, tempfile.TemporaryDirectory() as temp:
    def read(name):
        return archive.extractfile('WordNet-3.0/' + name).read().decode('utf-8')

    license_text = read('LICENSE')
    (destination / 'LICENSE.txt').write_text(license_text)
    database = Path(temp) / 'wordnet.db'
    connection = sqlite3.connect(database)
    connection.executescript('''
        CREATE TABLE senses (pos TEXT, offset INTEGER, gloss TEXT, PRIMARY KEY(pos, offset));
        CREATE TABLE entries (word TEXT, pos TEXT, offset INTEGER, rank INTEGER);
        CREATE TABLE exceptions (word TEXT, pos TEXT, lemma TEXT);
        PRAGMA user_version = 1;
    ''')
    for pos in ['noun', 'verb', 'adj', 'adv']:
        for line in read('dict/data.' + pos).splitlines():
            if not line or line[0].isspace():
                continue
            metadata, gloss = line.split('|', 1)
            connection.execute('INSERT INTO senses VALUES (?, ?, ?)', (pos, int(metadata.split()[0]), gloss.strip()))
        for line in read('dict/index.' + pos).splitlines():
            if not line or line[0].isspace():
                continue
            fields = line.split()
            count = int(fields[2])
            for rank, offset in enumerate(fields[-count:]):
                connection.execute('INSERT INTO entries VALUES (?, ?, ?, ?)', (fields[0].replace('_', ' '), pos, int(offset), rank))
        for line in read('dict/' + pos + '.exc').splitlines():
            word, *lemmas = line.split()
            for lemma in lemmas:
                connection.execute('INSERT INTO exceptions VALUES (?, ?, ?)', (word.replace('_', ' '), pos, lemma.replace('_', ' ')))
    connection.executescript('CREATE INDEX entry_word ON entries(word, pos); CREATE INDEX exception_word ON exceptions(word, pos);')
    connection.commit()
    assert connection.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
    connection.execute('VACUUM')
    connection.close()
    # No filename or timestamp in gzip metadata, so rebuilds are deterministic.
    # Android's asset packager transparently expands/renames .gz assets; use .gzip.
    with (destination / 'wordnet.db.gzip').open('wb') as output:
        with gzip.GzipFile(fileobj=output, mode='wb', filename='', mtime=0) as compressed:
            compressed.write(database.read_bytes())
    print('Bundled', destination / 'wordnet.db.gzip')
