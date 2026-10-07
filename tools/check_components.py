import re, zipfile
man = r'C:\Colgram\Telegram-Src\TMessagesProj_AppStandalone\build\intermediates\merged_manifest\afatRelease\processAfatReleaseMainManifest\AndroidManifest.xml'
apk = r'C:\Colgram\Telegram-Src\TMessagesProj_AppStandalone\build\outputs\apk\afat\release\app.apk'
d = open(man, encoding='utf-8', errors='replace').read()
comp = {}
for tag, n in re.findall(r'<\s*(activity|service|receiver|provider)(?![-\w])[^>]*?android:name="([^"]+)"', d, re.S):
    comp[n] = tag
print('real components:', len(comp))
z = zipfile.ZipFile(apk)
blob = b''.join(z.read(x) for x in z.namelist() if re.fullmatch(r'classes\d*\.dex', x)).decode('latin-1')
missing = sorted(n for n in comp if n.startswith(('org.', 'com.')) and ('L' + n.replace('.', '/') + ';') not in blob)
print('MISSING', len(missing))
for n in missing:
    print('  ', comp[n], n)
