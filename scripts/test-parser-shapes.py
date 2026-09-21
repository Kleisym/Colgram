"""Validate parseAsObject() against the response shapes mail.tm ACTUALLY returns.

Captured live from api.mail.tm (2026-09-21), not invented:

  GET  /domains      -> 200  [{"id":"6a99...","domain":"uberip.com","isActive":true}]   (bare array)
  POST /accounts     -> 201  {"id":"6ab0...","address":"...@uberip.com", ...}
  POST /token        -> 200  {"token":"eyJ..."}
  GET  /messages     -> 200  []                       <-- empty inbox is a BARE ARRAY of length 2
  GET  /messages     -> 401  {"code":401,"message":"Invalid JWT Token"}   (expired token)

The important one is the empty inbox: it is `[]`, NOT an empty body. org.json reports
"End of input at character 0 of" only for a ZERO-LENGTH body, so the user's error proves the
response was truly empty — a transport failure, not an API rejection. That distinction is the
whole reason readResponse() checks the status before parsing.
"""
import json


def parse_as_object(body):
    """Port of ColgramTempMailActivity.parseAsObject."""
    trimmed = (body or "").strip()
    if trimmed.startswith("["):
        return {"hydra:member": json.loads(trimmed)}
    if trimmed == "":
        return {}
    return json.loads(trimmed)


CASES = [
    ("empty inbox /messages  -> []",            "[]"),
    ("domains  -> bare array",                  '[{"id":"6a99","domain":"uberip.com","isActive":true}]'),
    ("account created  -> 201 object",          '{"id":"6ab0c5ec","address":"x@uberip.com"}'),
    ("expired token  -> 401 object",            '{"code":401,"message":"Invalid JWT Token"}'),
    ("THE BUG: truly empty body",               ""),
    ("messages with one item",                  '[{"id":"m1","subject":"hi"}]'),
]

EXPECT = {
    "empty inbox /messages  -> []": 0,      # normalised, zero messages, NOT an exception
    "domains  -> bare array": 1,
    "messages with one item": 1,
}


def main():
    print("=== parseAsObject() against real mail.tm shapes ===\n")
    for label, body in CASES:
        got = parse_as_object(body)
        member = got.get("hydra:member")
        if label in EXPECT:
            assert member is not None, "%s did not normalise to hydra:member" % label
            assert len(member) == EXPECT[label], (label, member)
            print("%-38s -> hydra:member: %d item(s)" % (label, len(member)))
        elif member is not None:
            print("%-38s -> hydra:member: %d item(s)" % (label, len(member)))
        else:
            print("%-38s -> %s" % (label, json.dumps(got)[:46]))

    # The decisive assertion: an empty body must NOT raise.
    assert parse_as_object("") == {}, "empty body must be 'no data', not a parse error"

    # And the empty inbox must reach the UI as zero messages rather than dying.
    inbox = parse_as_object("[]").get("hydra:member")
    assert inbox == [], inbox

    print("\nAll shape checks passed.")
    print("KEY: '[]' (2 bytes) is an empty inbox; 'End of input at character 0' means a")
    print("     0-byte body, i.e. a transport failure — never the empty-inbox case.")


if __name__ == "__main__":
    main()
