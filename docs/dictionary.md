# Offline English dictionary

Android and iOS bundle Princeton WordNet 3.0. Define opens a sheet in the reader (Material 3 on Android, a native sheet on iOS); lookup works from first use without internet or another app. Both platforms share the same database and the same lookup rules, so a word gives the same senses on either device. Saved words offer Define again from the Saved words list.

WordNet covers English nouns, verbs, adjectives and adverbs, including many phrases. Lookups check exact entries, exception lists and inflected forms. Names, recent vocabulary and other languages may not be included. No selected text is sent to a server.

The bundled database adds approximately 10 MiB to the APK and IPA and uses 23 MiB of app-private storage after first lookup. It contains 147,306 headwords. The source license is included in `app/src/main/assets/dictionary/LICENSE.txt` and `ios/Resources/dictionary/WordNet-LICENSE.txt`.

To rebuild the database, download the [official WordNet 3.0 archive](https://wordnetcode.princeton.edu/3.0/WordNet-3.0.tar.gz), then run:

```sh
python3 scripts/build-dictionary.py /path/to/WordNet-3.0.tar.gz
```

Then copy `app/src/main/assets/dictionary/wordnet.db.gzip` to `ios/Resources/dictionary/` so both apps ship the same database.

The generator preserves the original definitions, examples, sense order and exception forms in an indexed SQLite database. Runtime lookups use read-only database access.
