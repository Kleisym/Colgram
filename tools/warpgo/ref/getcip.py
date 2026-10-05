import urllib.request
base='https://raw.githubusercontent.com/Diniboy1123/connect-ip-go/master/'
for name in ['client_h2.go','request.go','client.go']:
    data=urllib.request.urlopen(urllib.request.Request(base+name, headers={'User-Agent':'colgram-dev'}), timeout=20).read()
    open(r'C:\Colgram\tools\warpgo\ref\cip_'+name,'wb').write(data)
    print(name, len(data))
