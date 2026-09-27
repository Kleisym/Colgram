import re
data = open(r'C:\Colgram\ci-artifact\libbox-x86_64.so', 'rb').read()

# gomobile builds a Go interface type whose runtime descriptor holds the method names in order.
# Look for the interface's itab/type block by anchoring on a known method name and reading the
# contiguous run of names around it.
anchor = b'AutoDetectInterfaceControl'
at = data.find(anchor)
print('anchor at', at)
if at > 0:
    window = data[max(0, at - 2000): at + 2000]
    names = re.findall(rb'[A-Z][A-Za-z]{3,40}', window)
    seen, order = set(), []
    for n in names:
        s = n.decode()
        if s not in seen:
            seen.add(s); order.append(s)
    print('--- names in the surrounding descriptor ---')
    for n in order:
        print('  ', n)
