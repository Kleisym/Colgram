"""Rebrands user-visible strings from Telegram to Colgram.

Two rules, and the second one is a list rather than a pattern.

URLs are never touched. telegram.org and t.me are real addresses people follow, and rewriting one
either 404s or sends them somewhere that is not us. A URL sitting inside a sentence does not freeze
the sentence, though - "Unlike other apps, Telegram never uses your private data... [Learn more]
(https://telegram.org/privacy)" was skipped whole by the previous rule, which left the brand sitting
in the middle of otherwise readable prose. So URLs are split out and the rest is rewritten.

The third party is a list of resource names. The earlier rule was "a line carrying a %placeholder
names someone other than us", and it skipped 26 lines per locale - of which exactly three were
right:

    VoipPeerVideoOutdated                "**%1$s** is using an old version of Telegram"
    VoipVideoNotAvailableAdmin           "New participants must use the latest version of Telegram"
    VoipChannelVideoNotAvailableAdmin

Those name the OTHER person's client, which Colgram cannot upgrade. The other twenty-three use a
placeholder for a phone number, a username, a level, a byte limit or a price - and the word beside it
is us:

    CallText                   "Telegram will call you in %1$d:%2$02d"
    NotificationContactJoined  "%1$s joined Telegram!"
    LimitReachedFolders        "...double the limit by subscribing to **Telegram Premium**"

PaymentWarningText is the one that reads worst when left alone and is worth being explicit about:
there Colgram IS the platform and %1$s is the developer, so "Neither Colgram, nor <dev>" is the
correct reading. The old rule treated the platform as a third party and kept both names in one
sentence.

Resource KEYS containing Telegram are never renamed. They are identifiers, not text, and renaming
TelegramPassport breaks every R.string.* in the tree that refers to it.

Run with --check to report without writing, which is what a build should do.
"""

import argparse
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(REPO, "Telegram-Src", "TMessagesProj", "src", "main", "res")

# Every locale is processed; the list exists so a locale added later shows up as untouched rather
# than silently skipped.
VALUE_DIRS = [
    "values", "values-ru", "values-ar", "values-de", "values-es", "values-it",
    "values-ko", "values-nl", "values-pt-rBR", "values-uk", "values-night",
]

# Resource names whose "Telegram" is somebody else's client rather than ours.
THIRD_PARTY = {
    "VoipPeerVideoOutdated",
    "VoipVideoNotAvailableAdmin",
    "VoipChannelVideoNotAvailableAdmin",
}

NAME = re.compile(r'<string\s+name="([^"]+)"')
# The element's own text: after the name attribute and its opening tag, up to the closing tag.
#
# This used to be found with >(.*?)<, and that silently skipped every string whose text contains an
# arrow. The permission prompts are the whole set of them - "**Telegram** needs camera access ...
# Tap Settings -> Permissions, and turn **Camera** on." - because [^<>]* cannot cross the '>' in
# '->', so the only span that matched ran from that arrow to the closing tag, which contains no brand
# at all, and the sentence naming us was never rewritten. A regex over markup is the wrong tool when
# the content may contain the same characters the markup uses.
BODY = re.compile(r'(<string\s+name="[^"]+"\s*>)(.*?)(</string>)')
# A URL, or anything shaped like one, kept byte-identical.
URLISH = re.compile(r'https?://\S+|t\.me/\S*')


def rewrite(text: str) -> str:
    """Replaces the brand in one span of text, leaving every URL inside it alone."""
    if "Telegram" not in text:
        return text
    out = []
    cursor = 0
    for match in URLISH.finditer(text):
        out.append(text[cursor:match.start()].replace("Telegram", "Colgram"))
        out.append(match.group(0))
        cursor = match.end()
    out.append(text[cursor:].replace("Telegram", "Colgram"))
    return "".join(out)


def rebrand_line(line: str) -> str:
    """Rewrites one <string> line, or returns it unchanged.

    Only the element's own text is rewritten; the name attribute is left alone, which is what keeps
    TelegramPassport and TelegramVersion working as identifiers.
    """
    if "Telegram" not in line:
        return line
    name = NAME.search(line)
    if name and name.group(1) in THIRD_PARTY:
        return line
    return BODY.sub(lambda m: m.group(1) + rewrite(m.group(2)) + m.group(3), line)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true",
                    help="report what would change and exit non-zero if anything would")
    args = ap.parse_args()

    total_changed = 0
    total_remaining = 0
    for d in VALUE_DIRS:
        path = os.path.join(RES, d, "strings.xml")
        if not os.path.exists(path):
            continue
        with open(path, "r", encoding="utf-8") as f:
            lines = f.readlines()

        changed = 0
        out = []
        for line in lines:
            new = rebrand_line(line)
            if new != line:
                changed += 1
            out.append(new)

        remaining = sum(1 for l in out if "Telegram" in l)
        total_changed += changed
        total_remaining += remaining
        print(f"{d:14s} changed={changed:4d} still mentions Telegram={remaining:4d}")

        if changed and not args.check:
            with open(path, "w", encoding="utf-8") as f:
                f.writelines(out)

    print(f"\ntotal changed={total_changed}  remaining={total_remaining}")
    if args.check and total_changed:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
