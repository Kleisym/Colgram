"""Exercise the retry/parse logic that the Temp Mail and Versions fixes depend on.

Both fixes rest on reasoning about *when* a retry helps and *what* an empty body means.
That reasoning is testable without a device, and worth testing — the failure mode of a bad
retry policy is subtle: it either gives up too early (the original bug) or hammers a server
that already answered (rate limiting, which is what broke the bot rename).

This transpiles the relevant Java into Python by hand, mirroring the semantics exactly, and
asserts the behavioural contract:

  1. a transient failure is retried and can succeed
  2. a 4xx is NOT retried (the server already decided)
  3. retries are bounded — never unbounded
  4. an empty body is NOT a parse error
  5. status is reported, not a JSON syntax message, for a non-2xx with an empty body

The Python here is a faithful port: same attempt count, same backoff schedule, same
"permanent" classification, same empty-body handling.
"""
import json

NET_ATTEMPTS = 3
NET_RETRY_BASE_MS = 1000


class Permanent(Exception):
    """Stands in for HttpException — carries the status as DATA, not as prose."""

    def __init__(self, code, message):
        super(Permanent, self).__init__(message)
        self.code = code


def is_permanent(e):
    """Port of ColgramTempMailActivity.isPermanent — structural, not textual."""
    if isinstance(e, Permanent):
        c = e.code
        # 4xx = the server decided. 408/429 are transient by definition, so they retry.
        return 400 <= c < 500 and c not in (408, 429)
    return False


def with_retry(label, body, sleep=None, log=None):
    """Port of ColgramTempMailActivity.withRetry — same shape, same bounds."""
    last = None
    for attempt in range(1, NET_ATTEMPTS + 1):
        try:
            return body()
        except Exception as e:          # noqa: BLE001 - mirrors `catch (Exception)`
            last = e
            if is_permanent(e):
                raise
            if attempt < NET_ATTEMPTS:
                if log:
                    log.append("%s attempt %d failed, retrying" % (label, attempt))
                if sleep:
                    sleep(NET_RETRY_BASE_MS * attempt)
    raise last if last is not None else IOError(label + " failed")


def parse_as_object(body):
    """Port of parseAsObject — an empty body is 'no data', not a syntax error."""
    trimmed = (body or "").strip()
    if trimmed.startswith("["):
        return {"hydra:member": json.loads(trimmed)}
    if trimmed == "":
        return {}
    return json.loads(trimmed)


def read_response(code, body):
    """Port of readResponse — status is decided BEFORE the body is parsed."""
    body = (body or "").strip()
    if code < 200 or code >= 300:
        if body == "":
            raise IOError("HTTP %d (пустой ответ сервера)" % code)
        raise IOError("HTTP %d" % code)
    if body == "":
        return {"code": code, "json": {}}
    return {"code": code, "json": parse_as_object(body)}


# ---------------------------------------------------------------- tests

def test_transient_is_retried_and_recovers():
    calls = {"n": 0}
    sleeps = []

    def body():
        calls["n"] += 1
        if calls["n"] < 3:
            raise IOError("End of input at character 0 of")
        return "OK"

    got = with_retry("domains", body, sleep=sleeps.append)
    assert got == "OK", got
    assert calls["n"] == 3, calls
    assert sleeps == [1000, 2000], sleeps
    print("PASS  transient failure retried, succeeded on attempt 3, backoff [1000, 2000]")


def test_4xx_is_not_retried():
    calls = {"n": 0}

    def body():
        calls["n"] += 1
        raise Permanent(422, "address already used")

    try:
        with_retry("create", body)
    except Permanent as e:
        assert e.code == 422, e
    else:
        raise AssertionError("expected the 4xx to propagate")
    assert calls["n"] == 1, "a 4xx must not be retried (got %d calls)" % calls["n"]
    print("PASS  4xx raised immediately, exactly 1 attempt (no rate-limit hammering)")


def test_429_and_408_ARE_retried():
    # These are transient by definition. The old message-matching version treated 429 as
    # permanent, which meant a rate-limited request was never retried at all.
    for code in (429, 408):
        calls = {"n": 0}

        def body(c=code):
            calls["n"] += 1
            if calls["n"] < 2:
                raise Permanent(c, "transient")
            return "OK"

        got = with_retry("retry-%d" % code, body)
        assert got == "OK", (code, got)
        assert calls["n"] == 2, (code, calls)
    print("PASS  429 and 408 ARE retried (transient by definition, not treated as permanent)")


def test_versions_retry_short_circuits_on_4xx():
    """Port of ColgramVersionsActivity.withRetry — the Boolean-returning variant.

    This one has no `isPermanent` helper; the classification lives inline in the catch.
    Worth testing separately because the two screens diverged and only one was covered.
    """
    FETCH_ATTEMPTS = 3

    class HttpStatus(Exception):
        def __init__(self, code):
            super(HttpStatus, self).__init__("HTTP %d" % code)
            self.code = code

    def with_retry(body, sleep=None):
        for attempt in range(1, FETCH_ATTEMPTS + 1):
            try:
                if body() is True:
                    return True
            except HttpStatus as hse:
                permanent = 400 <= hse.code < 500 and hse.code not in (408, 429)
                if permanent:
                    return False
            if attempt < FETCH_ATTEMPTS and sleep:
                sleep(1)
        return False

    # a 403 must give up on the FIRST attempt (no backoff burned)
    calls = {"n": 0}

    def forbidden():
        calls["n"] += 1
        raise HttpStatus(403)

    assert with_retry(forbidden) is False
    assert calls["n"] == 1, "403 must not be retried (got %d attempts)" % calls["n"]

    # a 429 must be retried
    calls2 = {"n": 0}

    def limited():
        calls2["n"] += 1
        raise HttpStatus(429)

    assert with_retry(limited) is False
    assert calls2["n"] == FETCH_ATTEMPTS, "429 must be retried (got %d)" % calls2["n"]
    print("PASS  Versions retry: 403 gives up once, 429 retries %d times" % FETCH_ATTEMPTS)


def test_no_shared_mutable_state_in_retry_path():
    """The side-channel field had to go — assert it is not referenced anywhere."""
    import os
    for name in ("ColgramVersionsActivity.java", "ColgramTempMailActivity.java"):
        path = os.path.join(r"C:\Colgram\scripts\templates", name)
        src = open(path, encoding="utf-8").read()
        assert "lastHttpStatus" not in src, "%s still uses the lastHttpStatus side-channel" % name
    # and the structural type must be present in both
    vol = open(os.path.join(r"C:\Colgram\scripts\templates", "ColgramVersionsActivity.java"),
               encoding="utf-8").read()
    tmp = open(os.path.join(r"C:\Colgram\scripts\templates", "ColgramTempMailActivity.java"),
               encoding="utf-8").read()
    assert "HttpStatusException" in vol, "Versions must carry the status on the exception"
    assert "HttpException" in tmp, "TempMail must carry the status on the exception"
    print("PASS  no shared mutable state; both screens carry the status structurally")


def test_status_is_data_not_prose():
    # The whole point: a reworded message must not change the retry decision.
    e = Permanent(403, "totally different wording than before")
    assert is_permanent(e) is True, "a 403 must stay non-retryable regardless of wording"
    e2 = Permanent(503, "service unavailable")
    assert is_permanent(e2) is False, "a 503 must stay retryable"
    print("PASS  retry decision is structural: reworded message cannot change it")


def test_retries_are_bounded():
    calls = {"n": 0}

    def body():
        calls["n"] += 1
        raise IOError("timed out")

    try:
        with_retry("poll", body)
    except IOError:
        pass
    assert calls["n"] == NET_ATTEMPTS, calls
    print("PASS  bounded: exactly %d attempts, never unbounded" % NET_ATTEMPTS)


def test_empty_2xx_is_not_an_error():
    r = read_response(200, "")
    assert r["code"] == 200 and r["json"] == {}, r
    r = read_response(204, "")
    assert r["json"] == {}, r
    print("PASS  empty 2xx returns empty object (a SUCCESS is not reported as broken)")


def test_non2xx_empty_body_reports_status_not_json():
    try:
        read_response(500, "")
    except IOError as e:
        msg = str(e)
        assert "HTTP 500" in msg, msg
        assert "End of input" not in msg, "must not surface a JSON parser message"
    else:
        raise AssertionError("expected an error")
    print("PASS  non-2xx empty body reports 'HTTP 500', not 'End of input at character 0'")


def test_empty_body_is_no_longer_a_parse_error():
    # The exact user-visible string came from new JSONObject("").
    assert parse_as_object("") == {}
    assert parse_as_object(None) == {}
    assert parse_as_object("  ") == {}
    print("PASS  parse_as_object('') no longer throws 'End of input at character 0 of'")


def test_bare_array_still_normalised():
    # mail.tm returns a bare array for some endpoints; the wrapper must survive.
    got = parse_as_object('[{"id":"a","domain":"berip.com"}]')
    assert "hydra:member" in got, got
    assert got["hydra:member"][0]["domain"] == "berip.com", got
    print("PASS  bare array still normalised to {'hydra:member': [...]}")


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for t in tests:
        t()
    print("\nAll %d behavioural checks passed." % len(tests))
