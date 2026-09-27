import re
data = open(r'C:\Colgram\ci-artifact\dexcheck\classes.dex', 'rb').read()
# A dex file stores every type descriptor as a plain string: Lio/nekohasekai/libbox/Libbox;
hits = sorted(set(x.decode('ascii','replace') for x in re.findall(rb'Lio/nekohasekai/libbox/[A-Za-z0-9_$]+;', data)))
print('--- libbox classes inside the shipped dex:', len(hits))
for h in hits[:25]:
    print('  ', h)
