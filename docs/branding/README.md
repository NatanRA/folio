# Folio branding

Folio is the display name on Android and iOS. The approved mark is a stack of three closed books with rounded spines and smooth, filled joins.

- `folio-rounded-stack.svg`: editable vector master; all paths inherit the foreground colour.
- Run `python3 scripts/generate-branding.py` from the repository root after changing the master. It generates Android vectors, the iOS template image, and the opaque 1024px iOS app icon.
- In-app marks use the selected accent. Android 13+ also supports the launcher's wallpaper-themed icon option. Standard launcher icons use aquamarine on a dark background.

Application identifiers, storage keys, sync protocol markers and repository URLs retain their existing values for compatibility. Releases use stable Folio download filenames and also publish the old Media Portal filenames as aliases.
