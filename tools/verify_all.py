import os, re

# Each item from the original list, checked against the source rather than against a write-up.
root = os.path.join('C:' + os.sep, 'Colgram', 'Telegram-Src')
checks = [
    ('call proxy default on', r'proxy_enabled_calls', [os.path.join(root, 'colgram-core', 'src', 'main', 'java')]),
    ('search history', r'ColgramSearchHistory', [os.path.join(root, 'TMessagesProj', 'src')]),
    ('plugin import .plugin', r'\.plugin', [os.path.join(root, 'colgram-core', 'src', 'main', 'java')]),
    ('built-in vs user split', r'builtIn|builtin', [os.path.join(root, 'TMessagesProj', 'src', 'main', 'java', 'org', 'telegram', 'ui')]),
]
for label, pattern, bases in checks:
    rx = re.compile(pattern)
    found = []
    for base in bases:
        for dp, dn, fn in os.walk(base):
            if 'build' in dp.split(os.sep):
                continue
            for f in fn:
                if not f.endswith(('.java', '.kt', '.xml')):
                    continue
                p = os.path.join(dp, f)
                try:
                    d = open(p, encoding='utf-8', errors='replace').read()
                except Exception:
                    continue
                if rx.search(d):
                    found.append(f)
    print('%-26s %d file(s): %s' % (label, len(found), ', '.join(sorted(set(found))[:4])))
