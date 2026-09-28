"""Extract the socket types a Python relay actually accepts connections on.

Written as a standalone helper so it can be tested on its own, which matters here: two earlier
versions of this logic were wrong in ways that reported a broken join as fixed, and the only reason
that was caught is that each version was run against the real file rather than trusted.
"""
import ast


def listener_transports(source):
    """The socket types bound and listened on, per function.

    Narrower than "every socket the program constructs" on purpose. The relay does construct a
    SOCK_DGRAM socket - it sends to Cloudflare over UDP - and counting that as a listener is
    exactly the mistake that made a broken join look fine.
    """
    tree = ast.parse(source)
    accepted = set()
    for function in [n for n in ast.walk(tree) if isinstance(n, ast.FunctionDef)]:
        bound = set()
        for node in ast.walk(function):
            if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                    and node.func.attr in ("bind", "listen")
                    and isinstance(node.func.value, ast.Name)):
                bound.add(node.func.value.id)
        if not bound:
            continue
        for node in ast.walk(function):
            if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                    and node.func.attr == "socket"):
                continue
            for parent in ast.walk(function):
                if not isinstance(parent, ast.Assign) or parent.value is not node:
                    continue
                if not isinstance(parent.targets[0], ast.Name):
                    continue
                if parent.targets[0].id in bound:
                    accepted.update(
                        a.attr for a in node.args
                        if isinstance(a, ast.Attribute) and a.attr.startswith("SOCK_"))
    return accepted

