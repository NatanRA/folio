# Connecting a library without typing

BookLore's OPDS feed accepts only HTTP Basic authentication against an OPDS account, which is
separate from the BookLore web login. There is no OAuth, token or device-pairing flow to sign in
with, so the address, username and password have to reach the phone somehow. Pairing moves them
without typing them on a phone keyboard.

## Making a code

Run this on the computer that already knows the details:

```sh
swift scripts/make-pairing-code.swift
```

It asks for the address, the OPDS username and the OPDS password, hiding the password as it is
typed, and draws a QR code in the terminal. The code is never written to a file: closing the
window is enough to be rid of it. Before printing anything the script scans its own code back and
refuses to continue unless it decodes to exactly the link it built, so a code it prints is a code
that reads.

## Using it

Point the phone's own Camera app at the code. It offers to open the link in Folio, and Folio then
shows which server it points at and which account it would sign in as, and asks. Nothing is saved
until the server accepts the credentials. Folio does not ask for camera access: the system camera
does the scanning, and the link arrives through `folio-pair://`.

Typing still works, and so does the Paste button on the BookLore form, which fills all three
fields from a copied link, a JSON object, or three whitespace-separated values.

## What a code is worth

**A pairing code contains the password, so the code is the password.** Anyone who photographs it,
or sees it over your shoulder, can read your library. Expiry would not change that: whoever holds
the code holds the credentials it carries.

What does help is that BookLore OPDS accounts are separate and revocable. Create one per device —
`opds-iphone`, `opds-pixel` — so a code that gets out can be cancelled server-side without
disturbing anything else, and so you can tell which device to blame. Use `https://` if the server
offers it; over `http://` the password crosses the network in the clear, and the script says so.

Folio never displays the password from a code, and never connects without being asked.

## Link format

```
folio-pair://booklore?address=<percent-encoded>&user=<percent-encoded>&password=<percent-encoded>
```

Every character outside `A-Z a-z 0-9 - . _ ~` is percent-encoded, so passwords containing `&`,
`#`, `+` or `/` survive. Both apps decode percent escapes themselves rather than through a
form decoder, because a form decoder reads `+` as a space and would sign in with the wrong
password. `PairingLinkTest` and `ConnectionPasteTests` cover that, along with rejecting links
that are not ours and making sure an opened book is never mistaken for a request to connect.
