import os
base = r'C:\Colgram\Telegram-Src\TMessagesProj_AppTests\src\androidTest\java'
names = []
for root, dirs, files in os.walk(base):
    for f in files:
        if f.endswith('DeviceTest.java'):
            rel = os.path.relpath(os.path.join(root, f), base)
            names.append(rel[:-5].replace(os.sep, '.'))
names.sort()
open(r'C:\Colgram\tools\all_tests.txt','w').write(','.join(names))
print(len(names), 'classes written')
